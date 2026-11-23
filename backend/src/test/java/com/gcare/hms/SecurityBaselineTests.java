package com.gcare.hms;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * against PostgreSQL with Flyway, health is public, and everything else is rejected.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SecurityBaselineTests {

  @Autowired private WebApplicationContext context;

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
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
  void unauthenticatedApiReadIsDenied() throws Exception {
    mvc.perform(get("/api/v1/patients")).andExpect(status().isForbidden());
  }

  @Test
  void unauthenticatedApiWriteIsDenied() throws Exception {
    mvc.perform(post("/api/v1/patients")).andExpect(status().isForbidden());
  }

  @Test
  void nonHealthActuatorEndpointsAreDenied() throws Exception {
    mvc.perform(get("/actuator/env")).andExpect(status().isForbidden());
    mvc.perform(get("/actuator/info")).andExpect(status().isForbidden());
  }
}
