package com.gcare.hms.organization;

import static org.assertj.core.api.Assertions.assertThat;

import com.gcare.hms.TestcontainersConfiguration;
import com.gcare.hms.shared.tenancy.OrganizationContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** The local profile's synthetic seed applies cleanly on top of the real migrations. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("local")
class LocalSeedTests {

  private static final UUID DEMO_ORG = UUID.fromString("00000000-0000-4000-8000-000000000001");
  private static final UUID DEMO_FACILITY = UUID.fromString("00000000-0000-4000-8000-000000000101");

  @Autowired private OrganizationRepository organizations;
  @Autowired private DepartmentRepository departments;

  @Test
  void seedCreatesDemoOrganizationFacilityAndDepartments() {
    OrganizationContext.runAs(
        DEMO_ORG,
        () -> {
          assertThat(organizations.findById(DEMO_ORG))
              .hasValueSatisfying(org -> assertThat(org.getCode()).isEqualTo("DEMO-ORG"));
          assertThat(departments.findByFacilityIdOrderByCode(DEMO_FACILITY))
              .extracting(Department::getCode)
              .containsExactly("BILL", "OPD", "RAD", "REG");
        });
  }
}
