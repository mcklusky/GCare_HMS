package com.gcare.hms;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gcare.hms.shared.web.CorrelationIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Proves the foundation is deny-by-default (docs/08-FIRST-SPRINT.md): the application starts
 * against PostgreSQL with Flyway, health is public, and everything else is rejected with the
 * standard error body and a correlation ID.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SecurityBaselineTests {

  @Autowired private WebApplicationContext context;

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(new CorrelationIdFilter())
            .apply(springSecurity())
            .build();
  }

  @Test
  void healthIsPublicAndUp() throws Exception {
    mvc.perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  @Test
  void healthDoesNotExposeComponentDetails() throws Exception {
    mvc.perform(get("/actuator/health")).andExpect(jsonPath("$.components").doesNotExist());
  }

  @Test
  void unauthenticatedApiReadIsRejectedWithStandardError() throws Exception {
    mvc.perform(get("/api/v1/patients").header(CorrelationIdFilter.HEADER, "test-corr-0001"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string(CorrelationIdFilter.HEADER, "test-corr-0001"))
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
        .andExpect(jsonPath("$.message").value("Authentication required"))
        .andExpect(jsonPath("$.field_errors").isMap())
        .andExpect(jsonPath("$.correlation_id").value("test-corr-0001"));
  }

  @Test
  void unauthenticatedApiWriteIsRejected() throws Exception {
    mvc.perform(post("/api/v1/patients").with(csrf()))
        .andExpect(status().isUnauthorized())
        .andExpect(header().exists(CorrelationIdFilter.HEADER))
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
  }

  /** CSRF stays on until the session model is chosen (OD-03). */
  @Test
  void writeWithoutCsrfTokenIsForbiddenWithStandardError() throws Exception {
    mvc.perform(post("/api/v1/patients"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"))
        .andExpect(jsonPath("$.correlation_id").exists());
  }

  @Test
  void nonHealthActuatorEndpointsAreRejected() throws Exception {
    mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    mvc.perform(get("/actuator/info")).andExpect(status().isUnauthorized());
  }
}
