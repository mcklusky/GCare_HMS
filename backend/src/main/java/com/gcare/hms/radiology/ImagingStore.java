package com.gcare.hms.radiology;

import com.gcare.hms.patient.PatientApi.PatientSummary;
import com.gcare.hms.patient.PatientStatus;
import com.gcare.hms.patient.Sex;
import com.gcare.hms.radiology.ImagingWorkApi.StudyEvent;
import com.gcare.hms.radiology.ImagingWorkApi.StudyStatus;
import com.gcare.hms.radiology.ImagingWorkApi.UserRef;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Imaging orders and study metadata. Reads the orders and patients tables to build the worklist and
 * to match studies; order status changes go through OrderService. Row-level security limits every
 * statement to the organization.
 */
@Repository
class ImagingStore {

  /** An imaging order with what matching and the worklist need. */
  record OrderRow(
      UUID orderId,
      UUID encounterId,
      UUID facilityId,
      String accessionNumber,
      PatientSummary patient,
      String itemCode,
      String itemName,
      String priority,
      String indication,
      String orderStatus,
      long orderVersion,
      Instant requestedAt,
      Instant arrivedAt,
      UserRef arrivedBy,
      Instant examinedAt,
      UserRef examinedBy,
      long version) {}

  /** A stored study. */
  record StudyRow(
      UUID id,
      String studyInstanceUid,
      String accessionNumber,
      String dicomPatientId,
      List<String> modalities,
      LocalDate studyDate,
      String description,
      Integer seriesCount,
      Integer instanceCount,
      Instant firstSeenAt,
      StudyStatus status,
      UUID orderId,
      String linkMethod,
      Instant linkedAt,
      UserRef linkedBy,
      UUID proposedOrderId,
      String proposedAccession,
      UserRef proposedBy,
      String proposalNote,
      long version) {}

  private static final String ORDER =
      """
      SELECT io.*, o.encounter_id, o.item_code, o.item_name, o.priority, o.indication, o.status AS order_status,
             o.version AS order_version, o.requested_at,
             p.id AS p_id, p.patient_number, p.status AS p_status, p.family_name, p.given_names, p.birth_date,
             p.birth_date_estimated, p.sex, p.details_incomplete,
             au.display_name AS arriver, eu.display_name AS examiner
      FROM imaging_orders io
      JOIN clinical_orders o ON o.id = io.order_id
      JOIN patients p ON p.id = o.patient_id
      LEFT JOIN users au ON au.id = io.arrived_by
      LEFT JOIN users eu ON eu.id = io.examined_by
      """;

  private static final String STUDY =
      """
      SELECT s.*, lu.display_name AS linker, pu.display_name AS proposer, po.accession_number AS proposed_accession
      FROM imaging_studies s
      LEFT JOIN users lu ON lu.id = s.linked_by
      LEFT JOIN users pu ON pu.id = s.proposed_by
      LEFT JOIN imaging_orders po ON po.order_id = s.proposed_order_id
      """;

  private final JdbcClient jdbc;

  ImagingStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // --- accession numbers -----------------------------------------------------------------------

  /** Imaging orders (not cancelled) that have no accession number yet. */
  List<UUID[]> ordersWithoutAccession() {
    return jdbc.sql(
            """
            SELECT o.id, o.facility_id FROM clinical_orders o
            WHERE o.item_category = 'IMAGING' AND o.status <> 'CANCELLED'
              AND NOT EXISTS (SELECT 1 FROM imaging_orders io WHERE io.order_id = o.id)
            ORDER BY o.requested_at
            """)
        .query((rs, i) ->
            new UUID[] {rs.getObject("id", UUID.class), rs.getObject("facility_id", UUID.class)})
        .list();
  }

  long nextAccession(UUID org) {
    return jdbc.sql(
            """
            INSERT INTO imaging_accession_counters (organization_id, last_number) VALUES (:org, 1)
            ON CONFLICT (organization_id) DO UPDATE SET last_number = imaging_accession_counters.last_number + 1
            RETURNING last_number
            """)
        .param("org", org)
        .query(Long.class)
        .single();
  }

  /** False when another request assigned this order first. */
  boolean insertImagingOrder(UUID org, UUID order, UUID facility, String accession) {
    return jdbc.sql(
                """
                INSERT INTO imaging_orders (order_id, organization_id, facility_id, accession_number)
                VALUES (:order, :org, :f, :acc) ON CONFLICT (order_id) DO NOTHING
                """)
            .param("order", order)
            .param("org", org)
            .param("f", facility)
            .param("acc", accession)
            .update()
        == 1;
  }

  // --- worklist --------------------------------------------------------------------------------

  List<OrderRow> worklist(Collection<String> orderStatuses, int limit) {
    return jdbc.sql(
            ORDER
                + " WHERE o.status IN (:st) ORDER BY (o.priority = 'URGENT') DESC, o.requested_at LIMIT :limit")
        .param("st", orderStatuses)
        .param("limit", limit)
        .query(this::orderRow)
        .list();
  }

  Optional<OrderRow> order(UUID orderId) {
    return jdbc.sql(ORDER + " WHERE io.order_id = :id")
        .param("id", orderId)
        .query(this::orderRow)
        .optional();
  }

  Optional<OrderRow> orderByAccession(String accession) {
    return jdbc.sql(ORDER + " WHERE io.accession_number = :acc")
        .param("acc", accession)
        .query(this::orderRow)
        .optional();
  }

  void lockOrder(UUID orderId) {
    jdbc.sql("SELECT order_id FROM imaging_orders WHERE order_id = :id FOR UPDATE")
        .param("id", orderId)
        .query(UUID.class)
        .optional();
  }

  int markArrived(UUID orderId, long version, UUID by) {
    return jdbc.sql(
            """
            UPDATE imaging_orders SET arrived_at = now(), arrived_by = :by, version = version + 1
            WHERE order_id = :id AND version = :v AND arrived_at IS NULL
            """)
        .param("by", by)
        .param("id", orderId)
        .param("v", version)
        .update();
  }

  int markExamined(UUID orderId, long version, UUID by) {
    return jdbc.sql(
            """
            UPDATE imaging_orders SET examined_at = now(), examined_by = :by, version = version + 1
            WHERE order_id = :id AND version = :v AND arrived_at IS NOT NULL AND examined_at IS NULL
            """)
        .param("by", by)
        .param("id", orderId)
        .param("v", version)
        .update();
  }

  // --- studies ---------------------------------------------------------------------------------

  /** Inserts or refreshes a study; returns its id and whether it was new. */
  Object[] upsertStudy(UUID org, ImagingArchive.Study s) {
    return jdbc.sql(
            """
            INSERT INTO imaging_studies (id, organization_id, study_instance_uid, accession_number, dicom_patient_id,
                modalities, study_date, description, series_count, instance_count, status)
            VALUES (:id, :org, :uid, :acc, :pid, :mod, :date, :descr, :series, :inst, 'UNMATCHED')
            ON CONFLICT (organization_id, study_instance_uid) DO UPDATE SET
                accession_number = EXCLUDED.accession_number, dicom_patient_id = EXCLUDED.dicom_patient_id,
                modalities = EXCLUDED.modalities, study_date = EXCLUDED.study_date,
                description = EXCLUDED.description, series_count = EXCLUDED.series_count,
                instance_count = EXCLUDED.instance_count, last_seen_at = now()
            RETURNING id, (xmax = 0) AS inserted
            """)
        .param("id", UUID.randomUUID())
        .param("org", org)
        .param("uid", s.studyInstanceUid())
        .param("acc", clip(s.accessionNumber(), 64))
        .param("pid", clip(s.patientId(), 64))
        .param("mod", clip(String.join(",", s.modalities()), 200))
        .param("date", s.studyDate())
        .param("descr", clip(s.description(), 200))
        .param("series", s.seriesCount())
        .param("inst", s.instanceCount())
        .query((rs, i) -> new Object[] {rs.getObject("id", UUID.class), rs.getBoolean("inserted")})
        .single();
  }

  Optional<StudyRow> study(UUID id) {
    return jdbc.sql(STUDY + " WHERE s.id = :id").param("id", id).query(this::studyRow).optional();
  }

  void lockStudy(UUID id) {
    jdbc.sql("SELECT id FROM imaging_studies WHERE id = :id FOR UPDATE")
        .param("id", id)
        .query(UUID.class)
        .optional();
  }

  List<StudyRow> studiesNotLinked() {
    return jdbc.sql(STUDY + " WHERE s.status <> 'LINKED' ORDER BY s.first_seen_at LIMIT 500")
        .query(this::studyRow)
        .list();
  }

  List<StudyRow> studiesOfOrder(UUID orderId) {
    return jdbc.sql(STUDY + " WHERE s.order_id = :o ORDER BY s.study_date, s.first_seen_at")
        .param("o", orderId)
        .query(this::studyRow)
        .list();
  }

  int autoLink(UUID studyId, UUID orderId) {
    return jdbc.sql(
            """
            UPDATE imaging_studies SET status = 'LINKED', order_id = :o, link_method = 'AUTOMATIC', linked_at = now(),
                version = version + 1
            WHERE id = :id AND status = 'UNMATCHED'
            """)
        .param("o", orderId)
        .param("id", studyId)
        .update();
  }

  int propose(UUID studyId, long version, UUID orderId, String note, UUID by) {
    return jdbc.sql(
            """
            UPDATE imaging_studies SET status = 'PROPOSED', proposed_order_id = :o, proposed_by = :by,
                proposed_at = now(), proposal_note = :note, version = version + 1
            WHERE id = :id AND version = :v AND status = 'UNMATCHED'
            """)
        .param("o", orderId)
        .param("by", by)
        .param("note", note)
        .param("id", studyId)
        .param("v", version)
        .update();
  }

  int approve(UUID studyId, long version, UUID by) {
    return jdbc.sql(
            """
            UPDATE imaging_studies SET status = 'LINKED', order_id = proposed_order_id, link_method = 'REVIEWED',
                linked_at = now(), linked_by = :by, version = version + 1
            WHERE id = :id AND version = :v AND status = 'PROPOSED'
            """)
        .param("by", by)
        .param("id", studyId)
        .param("v", version)
        .update();
  }

  /** Back to the queue: from a proposal (send back) or from a link (unlink). */
  int reset(UUID studyId, long version, StudyStatus from) {
    return jdbc.sql(
            """
            UPDATE imaging_studies SET status = 'UNMATCHED', order_id = NULL, link_method = NULL, linked_at = NULL,
                linked_by = NULL, proposed_order_id = NULL, proposed_by = NULL, proposed_at = NULL,
                proposal_note = NULL, version = version + 1
            WHERE id = :id AND version = :v AND status = :from
            """)
        .param("id", studyId)
        .param("v", version)
        .param("from", from.name())
        .update();
  }

  boolean orderHasLinkedStudy(UUID orderId) {
    return jdbc.sql(
                "SELECT count(*) FROM imaging_studies WHERE order_id = :o AND status = 'LINKED'")
            .param("o", orderId)
            .query(Long.class)
            .single()
        > 0;
  }

  void insertEvent(UUID org, UUID study, String type, UUID order, String note, UUID by) {
    jdbc.sql(
            """
            INSERT INTO imaging_study_events (id, organization_id, study_id, event_type, order_id, note, by_user)
            VALUES (:id, :org, :s, :t, :o, :note, :by)
            """)
        .param("id", UUID.randomUUID())
        .param("org", org)
        .param("s", study)
        .param("t", type)
        .param("o", order)
        .param("note", note)
        .param("by", by)
        .update();
  }

  List<StudyEvent> events(UUID study) {
    return jdbc.sql(
            """
            SELECT v.event_type, v.order_id, v.note, v.by_user, u.display_name, v.occurred_at
            FROM imaging_study_events v LEFT JOIN users u ON u.id = v.by_user
            WHERE v.study_id = :s ORDER BY v.occurred_at, v.id
            """)
        .param("s", study)
        .query((rs, i) -> {
          UUID by = rs.getObject("by_user", UUID.class);
          return new StudyEvent(
              rs.getString("event_type"),
              rs.getObject("order_id", UUID.class),
              rs.getString("note"),
              by == null ? null : new UserRef(by, rs.getString("display_name")),
              instant(rs, "occurred_at"));
        })
        .list();
  }

  // --- mapping ---------------------------------------------------------------------------------

  private OrderRow orderRow(ResultSet rs, int i) throws SQLException {
    UUID arriver = rs.getObject("arrived_by", UUID.class);
    UUID examiner = rs.getObject("examined_by", UUID.class);
    return new OrderRow(
        rs.getObject("order_id", UUID.class),
        rs.getObject("encounter_id", UUID.class),
        rs.getObject("facility_id", UUID.class),
        rs.getString("accession_number"),
        new PatientSummary(
            rs.getObject("p_id", UUID.class),
            rs.getString("patient_number"),
            PatientStatus.valueOf(rs.getString("p_status")),
            rs.getString("family_name"),
            rs.getString("given_names"),
            rs.getObject("birth_date", LocalDate.class),
            rs.getBoolean("birth_date_estimated"),
            Sex.valueOf(rs.getString("sex")),
            rs.getBoolean("details_incomplete")),
        rs.getString("item_code"),
        rs.getString("item_name"),
        rs.getString("priority"),
        rs.getString("indication"),
        rs.getString("order_status"),
        rs.getLong("order_version"),
        instant(rs, "requested_at"),
        instant(rs, "arrived_at"),
        arriver == null ? null : new UserRef(arriver, rs.getString("arriver")),
        instant(rs, "examined_at"),
        examiner == null ? null : new UserRef(examiner, rs.getString("examiner")),
        rs.getLong("version"));
  }

  private StudyRow studyRow(ResultSet rs, int i) throws SQLException {
    UUID linker = rs.getObject("linked_by", UUID.class);
    UUID proposer = rs.getObject("proposed_by", UUID.class);
    String mods = rs.getString("modalities");
    int series = rs.getInt("series_count");
    Integer seriesCount = rs.wasNull() ? null : series;
    int inst = rs.getInt("instance_count");
    Integer instanceCount = rs.wasNull() ? null : inst;
    return new StudyRow(
        rs.getObject("id", UUID.class),
        rs.getString("study_instance_uid"),
        rs.getString("accession_number"),
        rs.getString("dicom_patient_id"),
        mods == null || mods.isBlank() ? List.of() : Arrays.asList(mods.split(",")),
        rs.getObject("study_date", LocalDate.class),
        rs.getString("description"),
        seriesCount,
        instanceCount,
        instant(rs, "first_seen_at"),
        StudyStatus.valueOf(rs.getString("status")),
        rs.getObject("order_id", UUID.class),
        rs.getString("link_method"),
        instant(rs, "linked_at"),
        linker == null ? null : new UserRef(linker, rs.getString("linker")),
        rs.getObject("proposed_order_id", UUID.class),
        rs.getString("proposed_accession"),
        proposer == null ? null : new UserRef(proposer, rs.getString("proposer")),
        rs.getString("proposal_note"),
        rs.getLong("version"));
  }

  private static String clip(String s, int max) {
    return s == null ? null : s.length() <= max ? s : s.substring(0, max);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
    return t == null ? null : t.toInstant();
  }
}
