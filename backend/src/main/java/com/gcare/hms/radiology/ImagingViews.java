package com.gcare.hms.radiology;

import com.gcare.hms.radiology.ImagingStore.OrderRow;
import com.gcare.hms.radiology.ImagingStore.StudyRow;
import com.gcare.hms.radiology.ImagingWorkApi.StudyStatus;
import com.gcare.hms.radiology.ImagingWorkApi.StudyView;
import com.gcare.hms.radiology.ImagingWorkApi.WorklistItem;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Builds imaging order and study views (shared by the worklist and radiology reports). */
@Component
class ImagingViews {

  private final ImagingStore store;
  private final ImagingArchive archive;

  ImagingViews(ImagingStore store, ImagingArchive archive) {
    this.store = store;
    this.archive = archive;
  }

  /** Why a study waits in the queue, in words a radiographer can act on. */
  String unmatchedReason(StudyRow s) {
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
    if (store.wasUnlinkedFrom(s.id(), o.get().orderId())) {
      return "A person unlinked it from this order; propose a link if it does belong there";
    }
    if (store.orderHasLinkedStudy(o.get().orderId())) {
      return "The order already has a linked study";
    }
    return "Waiting for the next archive check";
  }

  WorklistItem item(OrderRow o) {
    List<StudyView> studies = store.studiesOfOrder(o.orderId()).stream().map(this::study).toList();
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

  StudyView study(StudyRow s) {
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
}
