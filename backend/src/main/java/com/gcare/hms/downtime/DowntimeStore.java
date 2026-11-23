package com.gcare.hms.downtime;

import com.gcare.hms.downtime.DowntimeApi.DowntimeSource;
import com.gcare.hms.downtime.DowntimeApi.EntryEvent;
import com.gcare.hms.downtime.DowntimeApi.EntryStatus;
import com.gcare.hms.downtime.DowntimeApi.EntryView;
import com.gcare.hms.downtime.DowntimeApi.EpisodeStatus;
import com.gcare.hms.downtime.DowntimeApi.EpisodeView;
import com.gcare.hms.downtime.DowntimeApi.FormBatchView;
import com.gcare.hms.downtime.DowntimeApi.FormType;
import com.gcare.hms.downtime.DowntimeApi.Option;
import com.gcare.hms.downtime.DowntimeApi.UserRef;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * Downtime episodes, form numbers and keyed forms. Row-level security limits every statement to the
 * organization; keyed data never changes (database trigger and column grants). Reads a few other
 * modules' tables (departments, catalog items, payment methods, encounters, invoices) only to
 * validate choices; records in those modules are created through their services.
 */
@Repository
class DowntimeStore {

  record EncounterRef(UUID id, UUID patientId, UUID facilityId, String status) {}

  record InvoiceRef(UUID id, UUID encounterId, String currency, String status) {}

  private static final String EPISODE =
      """
      SELECT e.*, d.display_name AS declarer, c.display_name AS closer
      FROM downtime_episodes e
      JOIN users d ON d.id = e.declared_by
      LEFT JOIN users c ON c.id = e.closed_by
      """;

  private static final String ENTRY =
      """
      SELECT x.*, eu.display_name AS enterer, pu.display_name AS proposer, du.display_name AS decider
      FROM downtime_entries x
      JOIN users eu ON eu.id = x.entered_by
      LEFT JOIN users pu ON pu.id = x.proposed_by
      LEFT JOIN users du ON du.id = x.decided_by
      """;

  private final JdbcClient jdbc;
  private final JsonMapper json;

  DowntimeStore(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  // --- episodes --------------------------------------------------------------------------------

  int nextEpisodeNumber(UUID facility) {
    return jdbc.sql(
            "SELECT coalesce(max(episode_number), 0) + 1 FROM downtime_episodes WHERE facility_id = :f")
        .param("f", facility)
        .query(Integer.class)
        .single();
  }

  /** True when another episode of the facility overlaps the window (open episodes run to now). */
  boolean overlaps(UUID facility, Instant start, Instant end) {
    return jdbc.sql(
                """
                SELECT count(*) FROM downtime_episodes
                WHERE facility_id = :f
                  AND tstzrange(started_at, coalesce(ended_at, 'infinity'::timestamptz))
                      && tstzrange(CAST(:s AS timestamptz), coalesce(CAST(:e AS timestamptz), 'infinity'::timestamptz))
                """)
            .param("f", facility)
            .param("s", ts(start))
            .param("e", ts(end))
            .query(Long.class)
            .single()
        > 0;
  }

  void insertEpisode(
      UUID id,
      UUID org,
      UUID facility,
      int number,
      Instant start,
      Instant end,
      String areas,
      String reason,
      UUID by) {
    jdbc.sql(
            """
            INSERT INTO downtime_episodes (id, organization_id, facility_id, episode_number, started_at, ended_at,
                affected_areas, reason, status, declared_by, ended_by)
            VALUES (:id, :org, :f, :n, :s, :e, :areas, :reason, :status, :by, :endedBy)
            """)
        .param("id", id)
        .param("org", org)
        .param("f", facility)
        .param("n", number)
        .param("s", ts(start))
        .param("e", ts(end))
        .param("areas", areas)
        .param("reason", reason)
        .param("status", end == null ? EpisodeStatus.ACTIVE.name() : EpisodeStatus.ENDED.name())
        .param("by", by)
        .param("endedBy", end == null ? null : by)
        .update();
  }

  Optional<EpisodeView> episode(UUID id) {
    return jdbc.sql(EPISODE + " WHERE e.id = :id")
        .param("id", id)
        .query(this::mapEpisode)
        .optional();
  }

  List<EpisodeView> episodes() {
    return jdbc.sql(EPISODE + " ORDER BY e.started_at DESC LIMIT 200")
        .query(this::mapEpisode)
        .list();
  }

  int endEpisode(UUID id, long version, Instant end, UUID by) {
    return jdbc.sql(
            """
            UPDATE downtime_episodes SET ended_at = :e, ended_by = :by, status = 'ENDED', version = version + 1
            WHERE id = :id AND version = :v AND status = 'ACTIVE'
            """)
        .param("e", ts(end))
        .param("by", by)
        .param("id", id)
        .param("v", version)
        .update();
  }

  int closeEpisode(UUID id, long version, String note, UUID by) {
    return jdbc.sql(
            """
            UPDATE downtime_episodes SET status = 'CLOSED', closed_by = :by, closed_at = now(), closing_note = :note,
                version = version + 1
            WHERE id = :id AND version = :v AND status = 'ENDED'
            """)
        .param("by", by)
        .param("note", note)
        .param("id", id)
        .param("v", version)
        .update();
  }

  void lockEpisode(UUID id) {
    jdbc.sql("SELECT id FROM downtime_episodes WHERE id = :id FOR UPDATE")
        .param("id", id)
        .query(UUID.class)
        .optional();
  }

  long openEntries(UUID episode) {
    return jdbc.sql(
            "SELECT count(*) FROM downtime_entries WHERE episode_id = :e AND status IN ('PENDING', 'PROPOSED')")
        .param("e", episode)
        .query(Long.class)
        .single();
  }

  private Map<EntryStatus, Integer> counts(UUID episode) {
    Map<EntryStatus, Integer> counts = new EnumMap<>(EntryStatus.class);
    for (EntryStatus s : EntryStatus.values()) {
      counts.put(s, 0);
    }
    jdbc.sql(
            "SELECT status, count(*) AS n FROM downtime_entries WHERE episode_id = :e GROUP BY status")
        .param("e", episode)
        .query((rs, i) -> counts.put(EntryStatus.valueOf(rs.getString("status")), rs.getInt("n")))
        .list();
    return counts;
  }

  // --- forms -----------------------------------------------------------------------------------

  /** Reserves {@code count} numbers and returns the last one (gapless per facility and type). */
  int reserveNumbers(UUID org, UUID facility, FormType type, int count) {
    return jdbc.sql(
            """
            INSERT INTO downtime_form_counters (organization_id, facility_id, form_type, last_number)
            VALUES (:org, :f, :t, :n)
            ON CONFLICT (organization_id, facility_id, form_type)
                DO UPDATE SET last_number = downtime_form_counters.last_number + :n
            RETURNING last_number
            """)
        .param("org", org)
        .param("f", facility)
        .param("t", type.name())
        .param("n", count)
        .query(Integer.class)
        .single();
  }

  void insertBatch(UUID id, UUID org, UUID facility, FormType type, int first, int last, UUID by) {
    jdbc.sql(
            """
            INSERT INTO downtime_form_batches (id, organization_id, facility_id, form_type, first_number, last_number,
                issued_by)
            VALUES (:id, :org, :f, :t, :first, :last, :by)
            """)
        .param("id", id)
        .param("org", org)
        .param("f", facility)
        .param("t", type.name())
        .param("first", first)
        .param("last", last)
        .param("by", by)
        .update();
  }

  private static final String BATCH =
      """
      SELECT b.*, f.code AS facility_code, f.name AS facility_name, u.display_name AS issuer
      FROM downtime_form_batches b
      JOIN facilities f ON f.id = b.facility_id
      JOIN users u ON u.id = b.issued_by
      """;

  Optional<FormBatchView> batch(UUID id) {
    return jdbc.sql(BATCH + " WHERE b.id = :id").param("id", id).query(this::mapBatch).optional();
  }

  List<FormBatchView> batches(UUID facility) {
    return jdbc.sql(BATCH + " WHERE b.facility_id = :f ORDER BY b.issued_at DESC LIMIT 100")
        .param("f", facility)
        .query(this::mapBatch)
        .list();
  }

  boolean issued(UUID facility, FormType type, int number) {
    return jdbc.sql(
                """
                SELECT count(*) FROM downtime_form_batches
                WHERE facility_id = :f AND form_type = :t AND :n BETWEEN first_number AND last_number
                """)
            .param("f", facility)
            .param("t", type.name())
            .param("n", number)
            .query(Long.class)
            .single()
        > 0;
  }

  // --- options and checks on other modules' records ----------------------------------------------

  List<Option> departments(UUID facility) {
    return jdbc.sql(
            "SELECT id, code, name FROM departments WHERE facility_id = :f AND status = 'ACTIVE' ORDER BY name")
        .param("f", facility)
        .query(this::option)
        .list();
  }

  List<Option> catalogItems() {
    return jdbc.sql(
            "SELECT id, code, name FROM order_catalog_items WHERE status = 'ACTIVE' ORDER BY name")
        .query(this::option)
        .list();
  }

  List<Option> paymentMethods() {
    return jdbc.sql(
            "SELECT id, code, name FROM payment_methods WHERE status = 'ACTIVE' ORDER BY name")
        .query(this::option)
        .list();
  }

  Optional<EncounterRef> encounter(UUID id) {
    return jdbc.sql("SELECT id, patient_id, facility_id, status FROM encounters WHERE id = :id")
        .param("id", id)
        .query((rs, i) ->
            new EncounterRef(
                rs.getObject("id", UUID.class),
                rs.getObject("patient_id", UUID.class),
                rs.getObject("facility_id", UUID.class),
                rs.getString("status")))
        .optional();
  }

  Optional<InvoiceRef> invoice(UUID id) {
    return jdbc.sql("SELECT id, encounter_id, currency, status FROM invoices WHERE id = :id")
        .param("id", id)
        .query((rs, i) ->
            new InvoiceRef(
                rs.getObject("id", UUID.class),
                rs.getObject("encounter_id", UUID.class),
                rs.getString("currency"),
                rs.getString("status")))
        .optional();
  }

  // --- entries ---------------------------------------------------------------------------------

  void insertEntry(
      UUID id,
      UUID org,
      UUID facility,
      UUID episode,
      FormType type,
      int number,
      Integer registrationNumber,
      Instant occurredAt,
      String data,
      UUID by) {
    jdbc.sql(
            """
            INSERT INTO downtime_entries (id, organization_id, facility_id, episode_id, form_type, form_number,
                registration_form_number, occurred_at, data, status, entered_by)
            VALUES (:id, :org, :f, :ep, :t, :n, :reg, :at, CAST(:data AS jsonb), 'PENDING', :by)
            """)
        .param("id", id)
        .param("org", org)
        .param("f", facility)
        .param("ep", episode)
        .param("t", type.name())
        .param("n", number)
        .param("reg", registrationNumber)
        .param("at", ts(occurredAt))
        .param("data", data)
        .param("by", by)
        .update();
  }

  /** The live (not voided) keying of a paper form, if any. */
  Optional<EntryView> liveEntry(UUID facility, FormType type, int number) {
    return jdbc.sql(
            ENTRY
                + " WHERE x.facility_id = :f AND x.form_type = :t AND x.form_number = :n"
                + " AND x.status <> 'VOIDED'")
        .param("f", facility)
        .param("t", type.name())
        .param("n", number)
        .query(this::mapEntry)
        .optional();
  }

  /** True when the entry holds exactly this keying (jsonb equality ignores formatting). */
  boolean sameKeying(
      UUID id, UUID episode, Integer registrationNumber, Instant at, String data, UUID by) {
    return jdbc.sql(
                """
                SELECT count(*) FROM downtime_entries
                WHERE id = :id AND episode_id = :ep AND entered_by = :by AND occurred_at = :at
                  AND registration_form_number IS NOT DISTINCT FROM CAST(:reg AS integer)
                  AND data = CAST(:data AS jsonb)
                """)
            .param("id", id)
            .param("ep", episode)
            .param("by", by)
            .param("at", ts(at))
            .param("reg", registrationNumber)
            .param("data", data)
            .query(Long.class)
            .single()
        > 0;
  }

  Optional<EntryView> entry(UUID id) {
    return jdbc.sql(ENTRY + " WHERE x.id = :id").param("id", id).query(this::mapEntry).optional();
  }

  void lockEntry(UUID id) {
    jdbc.sql("SELECT id FROM downtime_entries WHERE id = :id FOR UPDATE")
        .param("id", id)
        .query(UUID.class)
        .optional();
  }

  List<EntryView> entries(UUID episode) {
    return jdbc.sql(ENTRY + " WHERE x.episode_id = :e ORDER BY x.form_type, x.form_number")
        .param("e", episode)
        .query(this::mapEntry)
        .list();
  }

  /** The reconciled registration form a triage, order or payment form refers to. */
  Optional<EntryView> reconciledRegistration(UUID facility, int number) {
    return jdbc.sql(
            ENTRY
                + " WHERE x.facility_id = :f AND x.form_type = 'REGISTRATION' AND x.form_number = :n"
                + " AND x.status = 'RECONCILED'")
        .param("f", facility)
        .param("n", number)
        .query(this::mapEntry)
        .optional();
  }

  List<EntryEvent> events(UUID entry) {
    return jdbc.sql(
            """
            SELECT v.event_type, v.note, v.by_user, u.display_name, v.occurred_at
            FROM downtime_entry_events v JOIN users u ON u.id = v.by_user
            WHERE v.entry_id = :e ORDER BY v.occurred_at, v.id
            """)
        .param("e", entry)
        .query((rs, i) ->
            new EntryEvent(
                rs.getString("event_type"),
                rs.getString("note"),
                new UserRef(rs.getObject("by_user", UUID.class), rs.getString("display_name")),
                instant(rs, "occurred_at")))
        .list();
  }

  void insertEvent(UUID org, UUID entry, String type, String note, UUID by) {
    jdbc.sql(
            """
            INSERT INTO downtime_entry_events (id, organization_id, entry_id, event_type, note, by_user)
            VALUES (:id, :org, :e, :t, :note, :by)
            """)
        .param("id", UUID.randomUUID())
        .param("org", org)
        .param("e", entry)
        .param("t", type)
        .param("note", note)
        .param("by", by)
        .update();
  }

  int propose(UUID id, long version, String proposal, String note, UUID by) {
    return jdbc.sql(
            """
            UPDATE downtime_entries SET status = 'PROPOSED', proposal = CAST(:p AS jsonb), proposed_by = :by,
                proposed_at = now(), proposal_note = :note, version = version + 1
            WHERE id = :id AND version = :v AND status = 'PENDING'
            """)
        .param("p", proposal)
        .param("by", by)
        .param("note", note)
        .param("id", id)
        .param("v", version)
        .update();
  }

  int sendBack(UUID id, long version) {
    return jdbc.sql(
            """
            UPDATE downtime_entries SET status = 'PENDING', proposal = NULL, proposed_by = NULL, proposed_at = NULL,
                proposal_note = NULL, version = version + 1
            WHERE id = :id AND version = :v AND status = 'PROPOSED'
            """)
        .param("id", id)
        .param("v", version)
        .update();
  }

  int decide(
      UUID id,
      long version,
      EntryStatus from,
      EntryStatus to,
      String note,
      UUID by,
      UUID patient,
      UUID encounter,
      UUID record) {
    return jdbc.sql(
            """
            UPDATE downtime_entries SET status = :to, decided_by = :by, decided_at = now(), decision_note = :note,
                patient_id = :patient, encounter_id = :encounter, record_id = :record, version = version + 1
            WHERE id = :id AND version = :v AND status = :from
            """)
        .param("to", to.name())
        .param("by", by)
        .param("note", note)
        .param("patient", patient)
        .param("encounter", encounter)
        .param("record", record)
        .param("id", id)
        .param("v", version)
        .param("from", from.name())
        .update();
  }

  List<DowntimeSource> sources(UUID encounter) {
    return jdbc.sql(
            """
            SELECT x.id, x.form_type, x.form_number, e.episode_number, x.occurred_at, x.decided_at, x.record_id
            FROM downtime_entries x JOIN downtime_episodes e ON e.id = x.episode_id
            WHERE x.encounter_id = :enc AND x.status = 'RECONCILED'
            ORDER BY x.occurred_at, x.form_type
            """)
        .param("enc", encounter)
        .query((rs, i) -> {
          FormType t = FormType.valueOf(rs.getString("form_type"));
          return new DowntimeSource(
              rs.getObject("id", UUID.class),
              t,
              t.label(rs.getInt("form_number")),
              rs.getInt("episode_number"),
              instant(rs, "occurred_at"),
              instant(rs, "decided_at"),
              rs.getObject("record_id", UUID.class));
        })
        .list();
  }

  // --- mapping ---------------------------------------------------------------------------------

  private EpisodeView mapEpisode(ResultSet rs, int i) throws SQLException {
    UUID id = rs.getObject("id", UUID.class);
    UUID closer = rs.getObject("closed_by", UUID.class);
    return new EpisodeView(
        id,
        rs.getObject("facility_id", UUID.class),
        rs.getInt("episode_number"),
        instant(rs, "started_at"),
        instant(rs, "ended_at"),
        rs.getString("affected_areas"),
        rs.getString("reason"),
        EpisodeStatus.valueOf(rs.getString("status")),
        new UserRef(rs.getObject("declared_by", UUID.class), rs.getString("declarer")),
        instant(rs, "declared_at"),
        closer == null ? null : new UserRef(closer, rs.getString("closer")),
        instant(rs, "closed_at"),
        rs.getString("closing_note"),
        counts(id),
        rs.getLong("version"));
  }

  private FormBatchView mapBatch(ResultSet rs, int i) throws SQLException {
    FormType t = FormType.valueOf(rs.getString("form_type"));
    int first = rs.getInt("first_number");
    int last = rs.getInt("last_number");
    return new FormBatchView(
        rs.getObject("id", UUID.class),
        rs.getObject("facility_id", UUID.class),
        rs.getString("facility_code"),
        rs.getString("facility_name"),
        t,
        first,
        last,
        t.label(first),
        t.label(last),
        new UserRef(rs.getObject("issued_by", UUID.class), rs.getString("issuer")),
        instant(rs, "issued_at"));
  }

  private Option option(ResultSet rs, int i) throws SQLException {
    return new Option(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"));
  }

  private EntryView mapEntry(ResultSet rs, int i) throws SQLException {
    FormType t = FormType.valueOf(rs.getString("form_type"));
    int reg = rs.getInt("registration_form_number");
    Integer registration = rs.wasNull() ? null : reg;
    String proposal = rs.getString("proposal");
    UUID proposer = rs.getObject("proposed_by", UUID.class);
    UUID decider = rs.getObject("decided_by", UUID.class);
    return new EntryView(
        rs.getObject("id", UUID.class),
        rs.getObject("episode_id", UUID.class),
        rs.getObject("facility_id", UUID.class),
        t,
        rs.getInt("form_number"),
        t.label(rs.getInt("form_number")),
        registration,
        registration == null ? null : FormType.REGISTRATION.label(registration),
        instant(rs, "occurred_at"),
        json.readTree(rs.getString("data")),
        EntryStatus.valueOf(rs.getString("status")),
        t.needsSecondPerson(),
        new UserRef(rs.getObject("entered_by", UUID.class), rs.getString("enterer")),
        instant(rs, "entered_at"),
        proposal == null ? null : json.readTree(proposal),
        proposer == null ? null : new UserRef(proposer, rs.getString("proposer")),
        instant(rs, "proposed_at"),
        rs.getString("proposal_note"),
        decider == null ? null : new UserRef(decider, rs.getString("decider")),
        instant(rs, "decided_at"),
        rs.getString("decision_note"),
        rs.getObject("patient_id", UUID.class),
        rs.getObject("encounter_id", UUID.class),
        rs.getObject("record_id", UUID.class),
        null,
        rs.getLong("version"));
  }

  private static OffsetDateTime ts(Instant at) {
    return at == null ? null : OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
    return t == null ? null : t.toInstant();
  }
}
