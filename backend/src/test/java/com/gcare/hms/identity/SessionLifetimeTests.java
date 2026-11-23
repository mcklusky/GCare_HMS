package com.gcare.hms.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gcare.hms.TestcontainersConfiguration;
import com.gcare.hms.shared.security.Permissions;
import com.gcare.hms.testsupport.IdentityFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** A session older than the absolute lifetime is ended even while the user is active. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "gcare.identity.session-max-lifetime=50ms")
class SessionLifetimeTests {

  @Autowired private WebApplicationContext context;
  @Autowired private IdentityFixtures fixtures;

  @Test
  void sessionPastMaxLifetimeIsEndedAndAudited() throws Exception {
    MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    var user = fixtures.user(fixtures.organization());
    fixtures.grant(user, null, Permissions.FACILITY_READ);
    MockHttpSession session = new MockHttpSession();

    mvc.perform(get("/api/v1/me").session(session).with(user.signedIn()))
        .andExpect(status().isOk());

    Thread.sleep(100);

    mvc.perform(get("/api/v1/me").session(session).with(user.signedIn()))
        .andExpect(status().isUnauthorized());
    assertThat(session.isInvalid()).isTrue();
    assertThat(fixtures.auditActions(user.userId())).contains("SESSION_ENDED");
  }
}
