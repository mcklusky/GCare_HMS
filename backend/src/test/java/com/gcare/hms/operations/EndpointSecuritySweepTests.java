package com.gcare.hms.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gcare.hms.TestcontainersConfiguration;
import com.gcare.hms.testsupport.IdentityFixtures;
import com.gcare.hms.testsupport.IdentityFixtures.TestUser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;

/**
 * Security review (task 5.4; docs/23): every API operation, discovered from the running
 * application, is checked for sign-in, CSRF and deny-by-default authorization, so a new endpoint
 * without a permission check fails here.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class EndpointSecuritySweepTests {

  /**
   * Operations a signed-in user without any permission may still use, with the reason. Everything
   * else must answer 4xx to such a user.
   */
  private static final Map<String, String> OPEN_TO_ANY_SIGNED_IN_USER =
      Map.of("GET /api/v1/me", "who am I: identity, permissions and facilities of the caller only");

  @Autowired private WebApplicationContext context;
  @Autowired private IdentityFixtures fixtures;

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping mappings;

  private MockMvc mvc;
  private TestUser nobody;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    UUID org = fixtures.organization();
    nobody = fixtures.user(org);
  }

  private record Operation(HttpMethod method, String pattern) {
    String key() {
      return method.name() + " " + pattern;
    }

    String url() {
      // Path variables become unknown ids (or a harmless word for non-id variables).
      return pattern
          .replaceAll("\\{[^}]*(id|Id)}", UUID.randomUUID().toString())
          .replaceAll("\\{[^}]+}", "UNKNOWN");
    }
  }

  private List<Operation> apiOperations() {
    Set<String> seen = new TreeSet<>();
    List<Operation> ops = new ArrayList<>();
    for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
      if (info.getPathPatternsCondition() == null) {
        continue;
      }
      Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
      for (PathPattern p : info.getPathPatternsCondition().getPatterns()) {
        String pattern = p.getPatternString();
        if (!pattern.startsWith("/api/v1/")) {
          continue;
        }
        for (RequestMethod m : methods.isEmpty() ? Set.of(RequestMethod.GET) : methods) {
          if (seen.add(m.name() + " " + pattern)) {
            ops.add(new Operation(HttpMethod.valueOf(m.name()), pattern));
          }
        }
      }
    }
    return ops;
  }

  private MockHttpServletRequestBuilder build(Operation op) {
    MockHttpServletRequestBuilder b = request(op.method(), op.url());
    if (op.method() != HttpMethod.GET) {
      b.contentType(MediaType.APPLICATION_JSON).content("{}");
    }
    return b;
  }

  @Test
  void everyApiOperationIsDiscovered() {
    assertThat(apiOperations()).hasSizeGreaterThan(100);
  }

  @Test
  void everyApiOperationNeedsSignIn() throws Exception {
    List<String> open = new ArrayList<>();
    for (Operation op : apiOperations()) {
      int s = mvc.perform(build(op).with(csrf())).andReturn().getResponse().getStatus();
      if (s != 401) {
        open.add(op.key() + " -> " + s);
      }
    }
    assertThat(open).isEmpty();
  }

  @Test
  void everyStateChangeNeedsTheCsrfToken() throws Exception {
    List<String> unprotected = new ArrayList<>();
    for (Operation op : apiOperations()) {
      if (op.method() == HttpMethod.GET) {
        continue;
      }
      int s = mvc.perform(build(op).with(nobody.signedIn())).andReturn().getResponse().getStatus();
      if (s != 403) {
        unprotected.add(op.key() + " -> " + s);
      }
    }
    assertThat(unprotected).isEmpty();
  }

  @Test
  void aSignedInUserWithoutPermissionsGetsNothing() throws Exception {
    List<String> problems = new ArrayList<>();
    for (Operation op : apiOperations()) {
      if (OPEN_TO_ANY_SIGNED_IN_USER.containsKey(op.key())) {
        continue;
      }
      int s =
          mvc.perform(build(op).with(nobody.signedIn()).with(csrf()))
              .andReturn()
              .getResponse()
              .getStatus();
      if (s < 400 || s >= 500) {
        problems.add(op.key() + " -> " + s);
      }
    }
    assertThat(problems).isEmpty();
  }

  @Test
  void apiResponsesCarryHardeningHeaders() throws Exception {
    mvc.perform(get("/api/v1/me").with(nobody.signedIn()))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("X-Frame-Options", "DENY"))
        .andExpect(header().string("Referrer-Policy", "no-referrer"))
        .andExpect(
            header()
                .string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"))
        .andExpect(header().exists("Permissions-Policy"))
        .andExpect(
            header()
                .stringValues(
                    "Cache-Control",
                    org.hamcrest.Matchers.hasItem(
                        org.hamcrest.Matchers.containsString("no-store"))));
  }
}
