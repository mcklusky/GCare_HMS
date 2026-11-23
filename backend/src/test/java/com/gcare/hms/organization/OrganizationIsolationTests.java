package com.gcare.hms.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gcare.hms.TestcontainersConfiguration;
import com.gcare.hms.shared.tenancy.OrganizationContext;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Organization isolation is enforced by PostgreSQL row-level security (ADR-010), not only by
 * application code. Phase 1 exit criterion: tests prove tenant/facility boundaries.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class OrganizationIsolationTests {

  @Autowired private FacilityRepository facilities;
  @Autowired private DepartmentRepository departments;
  @Autowired private OrganizationRepository organizations;
  @Autowired private JdbcClient jdbc;

  private UUID orgA;
  private UUID orgB;
  private UUID facilityA;
  private UUID facilityB;

  @BeforeEach
  void createTwoOrganizations() {
    orgA = createOrganization();
    orgB = createOrganization();
    facilityA = OrganizationContext.callAs(orgA, () -> saveFacility(orgA).getId());
    facilityB = OrganizationContext.callAs(orgB, () -> saveFacility(orgB).getId());
  }

  @Test
  void organizationSeesOnlyItsOwnFacilities() {
    List<Facility> visible = OrganizationContext.callAs(orgA, facilities::findAll);

    assertThat(visible).extracting(Facility::getOrganizationId).containsOnly(orgA);
    assertThat(visible).extracting(Facility::getId).contains(facilityA).doesNotContain(facilityB);
  }

  @Test
  void otherOrganizationsRecordsLookNonexistent() {
    assertThat(OrganizationContext.callAs(orgA, () -> facilities.findById(facilityB))).isEmpty();
    assertThat(OrganizationContext.callAs(orgA, () -> organizations.findById(orgB))).isEmpty();
    assertThat(OrganizationContext.callAs(orgA, () -> organizations.findById(orgA))).isPresent();
  }

  @Test
  void withoutOrganizationContextNothingIsVisible() {
    assertThat(facilities.findAll()).isEmpty();
    assertThat(departments.findAll()).isEmpty();
    assertThat(organizations.findAll()).isEmpty();
  }

  @Test
  void cannotCreateFacilityForAnotherOrganization() {
    assertThatThrownBy(() -> OrganizationContext.runAs(orgA, () -> saveFacility(orgB)))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void cannotAttachDepartmentToAnotherOrganizationsFacility() {
    assertThatThrownBy(() ->
            OrganizationContext.runAs(
                orgA,
                () -> departments.saveAndFlush(new Department(orgA, facilityB, "OPD", "OPD"))))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void departmentsWithinOwnFacilityWork() {
    List<Department> found =
        OrganizationContext.callAs(
            orgA,
            () -> {
              departments.saveAndFlush(new Department(orgA, facilityA, "OPD", "Outpatient"));
              departments.saveAndFlush(new Department(orgA, facilityA, "REG", "Registration"));
              return departments.findByFacilityIdOrderByCode(facilityA);
            });

    assertThat(found).extracting(Department::getCode).containsExactly("OPD", "REG");
  }

  @Test
  void hardDeleteIsNotPermitted() {
    assertThatThrownBy(() ->
            OrganizationContext.runAs(
                orgA,
                () -> {
                  facilities.deleteById(facilityA);
                  facilities.flush();
                }))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void updatesWithinOwnOrganizationIncrementVersion() {
    Facility updated =
        OrganizationContext.callAs(
            orgA,
            () -> {
              Facility facility = facilities.findById(facilityA).orElseThrow();
              facility.rename("Renamed Hospital (synthetic)");
              return facilities.saveAndFlush(facility);
            });

    assertThat(updated.getName()).isEqualTo("Renamed Hospital (synthetic)");
    assertThat(updated.getVersion()).isEqualTo(1);
  }

  @Test
  void organizationContextCannotBeSwitchedMidUnitOfWork() {
    assertThatThrownBy(
            () -> OrganizationContext.runAs(orgA, () -> OrganizationContext.runAs(orgB, () -> {})))
        .isInstanceOf(IllegalStateException.class);
  }

  private Facility saveFacility(UUID organizationId) {
    return facilities.saveAndFlush(
        new Facility(
            organizationId,
            "FAC-" + shortId(),
            "Test Hospital (synthetic)",
            ZoneId.of("Africa/Monrovia")));
  }

  /** Organizations are created by platform operations, which run as the schema owner. */
  private UUID createOrganization() {
    UUID id = UUID.randomUUID();
    OrganizationContext.callAsSchemaOwner(() ->
        jdbc.sql(
                "INSERT INTO organizations (id, code, name, country_code, status, hosting_model)"
                    + " VALUES (:id, :code, 'Test Org (synthetic)', 'LR', 'ACTIVE', 'DEDICATED')")
            .param("id", id)
            .param("code", "ORG-" + shortId())
            .update());
    return id;
  }

  private static String shortId() {
    return UUID.randomUUID().toString().substring(0, 8);
  }
}
