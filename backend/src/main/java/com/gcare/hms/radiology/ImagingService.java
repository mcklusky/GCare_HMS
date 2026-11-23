package com.gcare.hms.radiology;

import com.gcare.hms.audit.AuditEvent;
import com.gcare.hms.audit.AuditOutcome;
import com.gcare.hms.audit.AuditService;
import com.gcare.hms.orders.OrderApi.VersionRequest;
import com.gcare.hms.orders.OrderService;
import com.gcare.hms.radiology.ImagingStore.OrderRow;
import com.gcare.hms.radiology.ImagingStore.StudyRow;
import com.gcare.hms.radiology.ImagingWorkApi.DecisionRequest;
import com.gcare.hms.radiology.ImagingWorkApi.ProposeLinkRequest;
import com.gcare.hms.radiology.ImagingWorkApi.StepRequest;
import com.gcare.hms.radiology.ImagingWorkApi.StudyEvent;
import com.gcare.hms.radiology.ImagingWorkApi.StudyStatus;
import com.gcare.hms.radiology.ImagingWorkApi.StudyView;
import com.gcare.hms.radiology.ImagingWorkApi.SyncResult;
import com.gcare.hms.radiology.ImagingWorkApi.WorklistItem;
import com.gcare.hms.shared.security.CurrentUser;
import com.gcare.hms.shared.security.Permissions;
import com.gcare.hms.shared.web.ApiException;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Imaging worklist, radiographer steps and study linking (task 6.3; docs/25).
 *
 * <ul>
 *   <li>Every imaging order gets an HMS accession number (ACC + 9 digits) the first time the
 *       worklist or the archive check sees it.
 *   <li>Arrived and examination done move the order through OrderService (start, complete), so the
 *       order's own rules and history apply; "images available" follows from a linked study.
 *   <li>A study links automatically only when its accession number AND patient ID equal the order's
 *       accession number and patient number. Everything else waits in the queue: one person
 *       proposes a link, a different person approves it; unlinking needs a reason.
 * </ul>
 */
@Service
public class ImagingService {

  private final ImagingStore store;
  private final ImagingArchive archive;
  private final OrderService orders;
  private final AuditService audit;

  ImagingService(
      ImagingStore store, ImagingArchive archive, OrderService orders, AuditService audit) {
    this.store = store;
    this.archive = archive;
    this.orders = orders;
    this.audit = audit;
  }

  // --- worklist and steps ----------------------------------------------------------------------

  @PreAuthorize("hasAuthority('" + Permissions.IMAGING_WORKLIST + "')")
  @Transactional
  public List<WorklistItem> worklist(boolean includeFinished) {
    CurrentUser user = CurrentUser.require();
    assignAccessions(user.organizationId());
    Set<String> statuses =
        includeFinished
            ? Set.of("REQUESTED", "IN_PROGRESS", "COMPLETED", "CANCELLED")
            : Set.of("REQUESTED", "IN_PROGRESS", "COMPLETED");
    List<WorklistItem> items =
        store.worklist(statuses, 300).stream()
            .filter(o -> user.scopeOf(Permissions.IMAGING_WORKLIST).includes(o.facilityId()))
            .map(this::item)
            // Finished = examined with images linked, or cancelled; hidden unless asked for.
            .filter(i ->
                includeFinished
                    || !("IMAGES_AVAILABLE".equals(i.step()) || "CANCELLED".equals(i.step())))
            .toList();
    audit(
        user,
        null,
        "IMAGING_WORKLIST_VIEWED",
        "ImagingWorklist",
        null,
        Map.of("count", Integer.toString(items.size())));
    return items;
  }

  @PreAuthorize("hasAuthority('" + Permissions.IMAGING_WORKLIST + "')")
  @Transactional
  public WorklistItem arrived(UUID orderId, StepRequest request) {
    CurrentUser user = CurrentUser.require();
    OrderRow o = lockedOrder(user, orderId, Permissions.IMAGING_WORKLIST);
    if (o.arrivedAt() != null) {
      throw new ApiException.InvalidTransition("Arrival is already recorded");
    }
    checkVersion(o, request.version());
    orders.start(orderId, new VersionRequest(o.orderVersion()));
    if (store.markArrived(orderId, request.version(), user.userId()) == 0) {
      throw new ApiException.Conflict("This order was changed. Reload it.");
    }
    audit(user, o.facilityId(), "IMAGING_PATIENT_ARRIVED", "ImagingOrder", orderId, Map.of());
    return item(store.order(orderId).orElseThrow());
  }

  @PreAuthorize("hasAuthority('" + Permissions.IMAGING_WORKLIST + "')")
  @Transactional
  public WorklistItem examined(UUID orderId, StepRequest request) {
    CurrentUser user = CurrentUser.require();
    OrderRow o = lockedOrder(user, orderId, Permissions.IMAGING_WORKLIST);
    if (o.arrivedAt() == null) {
      throw new ApiException.InvalidTransition("Record the patient's arrival first");
    }
    if (o.examinedAt() != null) {
      throw new ApiException.InvalidTransition("The examination is already recorded as done");
    }
    checkVersion(o, request.version());
    orders.complete(orderId, new VersionRequest(o.orderVersion()));
    if (store.markExamined(orderId, request.version(), user.userId()) == 0) {
      throw new ApiException.Conflict("This order was changed. Reload it.");
    }
    audit(user, o.facilityId(), "IMAGING_EXAMINATION_DONE", "ImagingOrder", orderId, Map.of());
    return item(store.order(orderId).orElseThrow());
  }

  // --- archive checks --------------------------------------------------------------------------

  /** Asks the archive for studies with this order's accession number and links what matches. */
  @PreAuthorize("hasAuthority('" + Permissions.IMAGING_LINK + "')")
  @Transactional
  public WorklistItem checkArchive(UUID orderId) {
    CurrentUser user = CurrentUser.require();
    OrderRow o = visibleOrder(user, orderId, Permissions.IMAGING_LINK);
    List<ImagingArchive.Study> found;
    try {
      found = archive.studiesByAccession(o.accessionNumber());
    } catch (ImagingArchive.UnavailableException e) {
      throw new ApiException.Unavailable("ARCHIVE_UNAVAILABLE", e.getMessage());
    }
    SyncResult r = absorb(user.organizationId(), found);
    audit(user, o.facilityId(), "IMAGING_ARCHIVE_CHECKED", "ImagingOrder", orderId, counts(r));
    return item(store.order(orderId).orElseThrow());
  }

  /**
   * Scheduled sweep (no signed-in user): assigns missing accession numbers, reads studies dated
   * within the look-back window and links what matches. Runs inside the organization's context.
   */
  @Transactional
  public SyncResult sweep(UUID organizationId, int lookbackDays) {
    assignAccessions(organizationId);
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    SyncResult r =
        absorb(
            organizationId,
            archive.studiesBetween(today.minusDays(lookbackDays), today.plusDays(1)));
    audit.record(
        new AuditEvent(
            organizationId,
            null,
            null,
            "IMAGING_ARCHIVE_SWEPT",
            "ImagingArchive",
            "studies",
            AuditOutcome.SUCCESS,
            null,
            Instant.now(),
            null,
            counts(r)));
    return r;
  }

  // --- mismatch queue --------------------------------------------------------------------------

  @PreAuthorize("hasAuthority('" + Permissions.IMAGING_LINK + "')")
  @Transactional(readOnly = true)
  public List<StudyView> queue() {
    CurrentUser user = CurrentUser.require();
    List<StudyView> list = store.studiesNotLinked().stream().map(this::studyView).toList();
    audit(
        user,
        null,
        "IMAGING_QUEUE_VIEWED",
        "ImagingStudy",
        null,
        Map.of("count", Integer.toString(list.size())));
    return list;
  }

  @PreAuthorize("hasAuthority('" + Permissions.IMAGING_LINK + "')")
  @Transactional(readOnly = true)
  public List<StudyEvent> history(UUID studyId) {
    store.study(studyId).orElseThrow(() -> new ApiException.NotFound("Study"));
    return store.events(studyId);
  }

  @PreAuthorize("hasAuthority('" + Permissions.IMAGING_LINK + "')")
  @Transactional
  public StudyView propose(UUID studyId, ProposeLinkRequest request) {
    CurrentUser user = CurrentUser.require();
    store.lockStudy(studyId);
    StudyRow s = store.study(studyId).orElseThrow(() -> new ApiException.NotFound("Study"));
    if (s.status() != StudyStatus.UNMATCHED) {
      throw new ApiException.InvalidTransition(
          s.status() == StudyStatus.PROPOSED
              ? "A link is already proposed for this study"
              : "This study is already linked");
    }
    OrderRow o =
        store
            .orderByAccession(request.accessionNumber().trim().toUpperCase())
            .filter(x -> user.scopeOf(Permissions.IMAGING_LINK).includes(x.facilityId()))
            .orElseThrow(() ->
                new ApiException.Invalid(
                    "accession_number", "is not an imaging order at a facility you work at"));
    if ("CANCELLED".equals(o.orderStatus())) {
      throw new ApiException.Invalid("accession_number", "belongs to a cancelled order");
    }
    String note = request.note().trim();
    if (store.propose(studyId, request.version(), o.orderId(), note, user.userId()) == 0) {
      throw new ApiException.Conflict("This study was changed. Reload it.");
    }
    store.insertEvent(user.organizationId(), studyId, "PROPOSED", o.orderId(), note, user.userId());
    audit(
        user,
        o.facilityId(),
        "IMAGING_LINK_PROPOSED",
        "ImagingStudy",
        studyId,
        Map.of("order", o.orderId().toString()));
    return studyView(store.study(studyId).orElseThrow());
  }

  @PreAuthorize("hasAuthority('" + Permissions.IMAGING_LINK + "')")
  @Transactional
  public StudyView approve(UUID studyId, DecisionRequest request) {
    CurrentUser user = CurrentUser.require();
    store.lockStudy(studyId);
    StudyRow s = store.study(studyId).orElseThrow(() -> new ApiException.NotFound("Study"));
    if (s.status() != StudyStatus.PROPOSED) {
      throw new ApiException.InvalidTransition("This study has no proposed link");
    }
    if (s.proposedBy().id().equals(user.userId())) {
      throw new ApiException.InvalidTransition(
          "You proposed this link, so another person must approve it");
    }
    OrderRow o = visibleOrder(user, s.proposedOrderId(), Permissions.IMAGING_LINK);
    if ("CANCELLED".equals(o.orderStatus())) {
      throw new ApiException.InvalidTransition("The order was cancelled; send the proposal back");
    }
    if (store.approve(studyId, request.version(), user.userId()) == 0) {
      throw new ApiException.Conflict("This study was changed. Reload it.");
    }
    store.insertEvent(
        user.organizationId(),
        studyId,
        "LINKED",
        o.orderId(),
        request.note().trim(),
        user.userId());
    audit(
        user,
        o.facilityId(),
        "IMAGING_STUDY_LINKED",
        "ImagingStudy",
        studyId,
        Map.of("order", o.orderId().toString(), "method", "REVIEWED"));
    return studyView(store.study(studyId).orElseThrow());
  }

  @PreAuthorize("hasAuthority('" + Permissions.IMAGING_LINK + "')")
  @Transactional
  public StudyView sendBack(UUID studyId, DecisionRequest request) {
    CurrentUser user = CurrentUser.require();
    store.lockStudy(studyId);
    StudyRow s = store.study(studyId).orElseThrow(() -> new ApiException.NotFound("Study"));
    if (s.status() != StudyStatus.PROPOSED) {
      throw new ApiException.InvalidTransition("This study has no proposed link");
    }
    if (store.reset(studyId, request.version(), StudyStatus.PROPOSED) == 0) {
      throw new ApiException.Conflict("This study was changed. Reload it.");
    }
    store.insertEvent(
        user.organizationId(),
        studyId,
        "SENT_BACK",
        s.proposedOrderId(),
        request.note().trim(),
        user.userId());
    audit(user, null, "IMAGING_LINK_SENT_BACK", "ImagingStudy", studyId, Map.of());
    return studyView(store.study(studyId).orElseThrow());
  }

  /** Undoes a link (automatic or reviewed), with a reason; the study returns to the queue. */
  @PreAuthorize("hasAuthority('" + Permissions.IMAGING_LINK + "')")
  @Transactional
  public StudyView unlink(UUID studyId, DecisionRequest request) {
    CurrentUser user = CurrentUser.require();
    store.lockStudy(studyId);
    StudyRow s = store.study(studyId).orElseThrow(() -> new ApiException.NotFound("Study"));
    if (s.status() != StudyStatus.LINKED) {
      throw new ApiException.InvalidTransition("This study is not linked");
    }
    OrderRow o = visibleOrder(user, s.orderId(), Permissions.IMAGING_LINK);
    if (store.reset(studyId, request.version(), StudyStatus.LINKED) == 0) {
      throw new ApiException.Conflict("This study was changed. Reload it.");
    }
    store.insertEvent(
        user.organizationId(),
        studyId,
        "UNLINKED",
        o.orderId(),
        request.note().trim(),
        user.userId());
    audit(
        user,
        o.facilityId(),
        "IMAGING_STUDY_UNLINKED",
        "ImagingStudy",
        studyId,
        Map.of("order", o.orderId().toString()));
    return studyView(store.study(studyId).orElseThrow());
  }

  // --- matching --------------------------------------------------------------------------------

  private SyncResult absorb(UUID org, List<ImagingArchive.Study> found) {
    int created = 0;
    int linked = 0;
    int unmatched = 0;
    for (ImagingArchive.Study s : found) {
      Object[] up = store.upsertStudy(org, s);
      UUID id = (UUID) up[0];
      if ((Boolean) up[1]) {
        created++;
        store.insertEvent(org, id, "DISCOVERED", null, null, null);
      }
      StudyRow row = store.study(id).orElseThrow();
      if (row.status() != StudyStatus.UNMATCHED) {
        continue;
      }
      Optional<OrderRow> order = exactMatch(row);
      if (order.isPresent() && store.autoLink(id, order.get().orderId()) == 1) {
        store.insertEvent(org, id, "AUTO_LINKED", order.get().orderId(), null, null);
        linked++;
      } else {
        unmatched++;
      }
    }
    return new SyncResult(found.size(), created, linked, unmatched);
  }

  /** The order this study belongs to, only on an exact accession AND patient number match. */
  private Optional<OrderRow> exactMatch(StudyRow s) {
    if (s.accessionNumber() == null || s.dicomPatientId() == null) {
      return Optional.empty();
    }
    return store
        .orderByAccession(s.accessionNumber().trim())
        .filter(o -> !"CANCELLED".equals(o.orderStatus()))
        .filter(o -> o.patient().patientNumber().equals(s.dicomPatientId().trim()))
        .filter(o -> !store.orderHasLinkedStudy(o.orderId()));
  }

  /** Why a study waits in the queue, in words a radiographer can act on. */
  private String unmatchedReason(StudyRow s) {
    if (s.status() != StudyStatus.UNMATCHED) {
      return null;
    }
    if (s.accessionNumber() == null) {
      return "The study has no accession number";
    }
    Optional<OrderRow> o = store.orderByAccession(s.accessionNumber().trim());
    if (o.isEmpty()) {
      return "No imaging order has this accession number";
    }
    if ("CANCELLED".equals(o.get().orderStatus())) {
      return "The order with this accession number was cancelled";
    }
    if (s.dicomPatientId() == null
        || !o.get().patient().patientNumber().equals(s.dicomPatientId().trim())) {
      return "The patient ID on the study differs from the order's patient number";
    }
    if (store.orderHasLinkedStudy(o.get().orderId())) {
      return "The order already has a linked study";
    }
    return "Waiting for the next archive check";
  }

  private void assignAccessions(UUID org) {
    for (UUID[] o : store.ordersWithoutAccession()) {
      String accession = String.format("ACC%09d", store.nextAccession(org));
      store.insertImagingOrder(org, o[0], o[1], accession);
    }
  }

  // --- views -----------------------------------------------------------------------------------

  private WorklistItem item(OrderRow o) {
    List<StudyView> studies =
        store.studiesOfOrder(o.orderId()).stream().map(this::studyView).toList();
    String step =
        "CANCELLED".equals(o.orderStatus())
            ? "CANCELLED"
            : !studies.isEmpty() && o.examinedAt() != null
                ? "IMAGES_AVAILABLE"
                : o.examinedAt() != null
                    ? "EXAMINED"
                    : o.arrivedAt() != null ? "ARRIVED" : "ORDERED";
    return new WorklistItem(
        o.orderId(),
        o.encounterId(),
        o.facilityId(),
        o.accessionNumber(),
        o.patient(),
        o.itemCode(),
        o.itemName(),
        o.priority(),
        o.indication(),
        o.orderStatus(),
        o.requestedAt(),
        o.arrivedAt(),
        o.arrivedBy(),
        o.examinedAt(),
        o.examinedBy(),
        step,
        studies,
        o.version());
  }

  private StudyView studyView(StudyRow s) {
    return new StudyView(
        s.id(),
        s.studyInstanceUid(),
        s.accessionNumber(),
        s.dicomPatientId(),
        s.modalities(),
        s.studyDate(),
        s.description(),
        s.seriesCount(),
        s.instanceCount(),
        s.firstSeenAt(),
        s.status(),
        unmatchedReason(s),
        s.orderId(),
        s.linkMethod(),
        s.linkedAt(),
        s.linkedBy(),
        s.proposedOrderId(),
        s.proposedAccession(),
        s.proposedBy(),
        s.proposalNote(),
        s.status() == StudyStatus.LINKED
            ? archive.viewerUrl(s.studyInstanceUid()).map(URI::toString).orElse(null)
            : null,
        s.version());
  }

  // --- access ----------------------------------------------------------------------------------

  private OrderRow visibleOrder(CurrentUser user, UUID orderId, String permission) {
    assignAccessions(user.organizationId());
    return store
        .order(orderId)
        .filter(o -> user.scopeOf(permission).includes(o.facilityId()))
        .orElseThrow(() -> new ApiException.NotFound("Imaging order"));
  }

  private OrderRow lockedOrder(CurrentUser user, UUID orderId, String permission) {
    visibleOrder(user, orderId, permission);
    store.lockOrder(orderId);
    return store.order(orderId).orElseThrow();
  }

  private static void checkVersion(OrderRow o, long version) {
    if (o.version() != version) {
      throw new ApiException.Conflict("This order was changed. Reload it.");
    }
  }

  private static Map<String, String> counts(SyncResult r) {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("studies_seen", Integer.toString(r.studiesSeen()));
    m.put("new_studies", Integer.toString(r.newStudies()));
    m.put("linked", Integer.toString(r.linked()));
    m.put("unmatched", Integer.toString(r.unmatched()));
    return m;
  }

  /** Audited after commit; metadata holds counts and ids, never patient or study values. */
  private void audit(
      CurrentUser user,
      UUID facilityId,
      String action,
      String type,
      UUID id,
      Map<String, String> metadata) {
    AuditEvent event =
        new AuditEvent(
            user.organizationId(),
            facilityId,
            user.userId(),
            action,
            type,
            id == null ? null : id.toString(),
            AuditOutcome.SUCCESS,
            null,
            Instant.now(),
            null,
            Map.copyOf(metadata));
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              audit.record(event);
            }
          });
    } else {
      audit.record(event);
    }
  }
}
