package com.gcare.hms.patient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gcare.hms.TestcontainersConfiguration;
import com.gcare.hms.shared.tenancy.OrganizationContext;
import com.gcare.hms.testsupport.IdentityFixtures;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Database guarantees for migration V3 (organization isolation, no deletes, number format). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PatientSchemaTests {

  private static final List<String> TABLES =
      List.of(
          "patients", "patient_identifiers", "patient_number_counters", "patient_duplicate_flags");

  @Autowired private JdbcClient jdbc;
  @Autowired private IdentityFixtures fixtures;

  @Test
  void patientTablesAreIsolatedAndCannotBeDeleted() {
    UUID orgA = fixtures.organization();
    UUID orgB = fixtures.organization();
    UUID facilityB = fixtures.facility(orgB, "B");
    var userB = fixtures.user(orgB);
    UUID patientB = UUID.randomUUID();
    OrganizationContext.callAsSchemaOwner(() -> {
      jdbc.sql(
              "INSERT INTO patients (id, organization_id, patient_number, registration_facility_id,"
                  + " status, sex, details_incomplete, registration_source, created_by)"
                  + " VALUES (?, ?, 'P1000001-6', ?, 'ACTIVE', 'UNKNOWN', true, 'WALK_IN', ?)")
          .params(patientB, orgB, facilityB, userB.userId())
          .update();
      jdbc.sql(
              "INSERT INTO patient_identifiers (id, organization_id, patient_id, identifier_type,"
                  + " value, value_norm, status, source, created_by)"
                  + " VALUES (?, ?, ?, 'OTHER', 'X1', 'X1', 'ACTIVE', 'REGISTRATION', ?)")
          .params(UUID.randomUUID(), orgB, patientB, userB.userId())
          .update();
      jdbc.sql("INSERT INTO patient_number_counters VALUES (?, 1000002)").params(orgB).update();
      return null;
    });

    for (String table : TABLES) {
      long visibleToA =
          OrganizationContext.callAs(
              orgA,
              () ->
                  jdbc.sql("SELECT count(*) FROM " + table + " WHERE organization_id = ?")
                      .params(orgB)
                      .query(Long.class)
                      .single());
      assertThat(visibleToA).as(table).isZero();
      assertThatThrownBy(() ->
              OrganizationContext.callAs(orgB, () -> jdbc.sql("DELETE FROM " + table).update()))
          .as(table)
          .isInstanceOf(DataAccessException.class);
    }
  }

  @Test
  void patientNumberFormatIsEnforced() {
    UUID org = fixtures.organization();
    UUID facility = fixtures.facility(org, "F");
    var user = fixtures.user(org);

    assertThatThrownBy(() ->
            OrganizationContext.callAsSchemaOwner(() ->
                jdbc.sql(
                        "INSERT INTO patients (id, organization_id, patient_number,"
                            + " registration_facility_id, status, sex, details_incomplete,"
                            + " registration_source, created_by)"
                            + " VALUES (?, ?, '12345', ?, 'ACTIVE', 'UNKNOWN', true, 'WALK_IN', ?)")
                    .params(UUID.randomUUID(), org, facility, user.userId())
                    .update()))
        .isInstanceOf(DataAccessException.class);
  }
}
