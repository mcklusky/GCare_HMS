package com.gcare.hms.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gcare.hms.TestcontainersConfiguration;
import com.gcare.hms.testsupport.IdentityFixtures;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The OpenAPI document covers /api/v1 and is only served to signed-in users. The build writes it to
 * {@code target/openapi/openapi.json} for review.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class OpenApiTests {

  @Autowired private WebApplicationContext context;
  @Autowired private IdentityFixtures fixtures;

  @Test
  void documentRequiresSignInAndDescribesApiV1() throws Exception {
    MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

    mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());

    var user = fixtures.user(fixtures.organization());
    String document =
        mvc.perform(get("/v3/api-docs").with(user.signedIn()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);

    assertThat(document)
        .contains("\"/api/v1/me\"")
        .contains("\"/api/v1/facilities\"")
        .contains("\"/api/v1/facilities/{facilityId}/departments\"")
        .doesNotContain("/actuator");

    Path out = Path.of("target", "openapi", "openapi.json");
    Files.createDirectories(out.getParent());
    Files.writeString(out, document, StandardCharsets.UTF_8);
  }
}
