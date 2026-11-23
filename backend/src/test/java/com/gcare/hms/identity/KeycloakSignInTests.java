package com.gcare.hms.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.gcare.hms.TestcontainersConfiguration;
import com.gcare.hms.shared.security.Permissions;
import com.gcare.hms.testsupport.IdentityFixtures;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.net.CookieHandler;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.ClientRepresentation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * End-to-end sign-in against a real Keycloak running the local realm file
 * (infra/keycloak/gcare-realm.local.json): authorization code + PKCE, the GCare account check,
 * session cookie, CSRF-protected sign-out with provider logout URL.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "gcare.test.offline-idp=false")
class KeycloakSignInTests {

  private static final String PASSWORD = "Local-Demo-Only-1!";
  private static final String DEMO_ADMIN_SUBJECT = "00000000-0000-4000-8000-000000001001";

  static final KeycloakContainer KEYCLOAK =
      new KeycloakContainer("quay.io/keycloak/keycloak:26.8.2")
          .withRealmImportFile("keycloak/gcare-realm.local.json");

  static {
    KEYCLOAK.start();
  }

  @DynamicPropertySource
  static void keycloak(DynamicPropertyRegistry registry) {
    String issuer = issuer();
    registry.add("spring.security.oauth2.client.provider.keycloak.issuer-uri", () -> issuer);
    registry.add(
        "spring.security.oauth2.client.registration.keycloak.client-secret",
        () -> "local-dev-only-not-a-secret");
    registry.add("gcare.identity.post-login-redirect", () -> "/signed-in");
  }

  private static String issuer() {
    return KEYCLOAK.getAuthServerUrl().replaceAll("/$", "") + "/realms/gcare";
  }

  @LocalServerPort private int port;
  @Autowired private IdentityFixtures fixtures;

  private BrowserCookies cookies;
  private HttpClient http;
  private String base;

  @BeforeEach
  void setUp() {
    base = "http://localhost:" + port;
    cookies = new BrowserCookies();
    http =
        HttpClient.newBuilder()
            .cookieHandler(cookies)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    var clients = KEYCLOAK.getKeycloakAdminClient().realm("gcare").clients();
    ClientRepresentation client = clients.findByClientId("gcare-hms-backend").getFirst();
    client.setRedirectUris(List.of(base + "/login/oauth2/code/keycloak"));
    clients.get(client.getId()).update(client);
  }

  @Test
  void linkedUserSignsInUsesTheApiAndSignsOut() throws Exception {
    UUID org = fixtures.organization();
    fixtures.facility(org, "KC-FAC");
    var user = fixtures.user(org);
    fixtures.link(org, user.userId(), issuer(), DEMO_ADMIN_SUBJECT);
    fixtures.grant(user, null, Permissions.FACILITY_READ);

    HttpResponse<String> afterSignIn = signIn("demo.admin");

    assertThat(afterSignIn.statusCode()).isEqualTo(302);
    assertThat(location(afterSignIn)).isEqualTo("/signed-in");
    assertThat(cookies.attributes("GCARE_SESSION")).containsIgnoringCase("HttpOnly");

    HttpResponse<String> me = send(HttpRequest.newBuilder(URI.create(base + "/api/v1/me")));
    assertThat(me.statusCode()).isEqualTo(200);
    assertThat(me.body()).contains(user.userId().toString()).contains("FACILITY_READ");
    assertThat(fixtures.auditActions(user.userId())).contains("SIGNED_IN");

    HttpResponse<String> logout =
        send(
            HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/logout"))
                .header("X-XSRF-TOKEN", cookies.value("XSRF-TOKEN"))
                .POST(HttpRequest.BodyPublishers.noBody()));
    assertThat(logout.statusCode()).isEqualTo(200);
    assertThat(logout.body())
        .contains(issuer() + "/protocol/openid-connect/logout")
        .contains("id_token_hint");
    assertThat(fixtures.auditActions(user.userId())).contains("SIGNED_OUT");

    HttpResponse<String> afterLogout =
        send(HttpRequest.newBuilder(URI.create(base + "/api/v1/me")));
    assertThat(afterLogout.statusCode()).isEqualTo(401);
  }

  @Test
  void keycloakAccountWithoutGcareUserIsRefused() throws Exception {
    HttpResponse<String> afterSignIn = signIn("demo.unlinked");

    assertThat(afterSignIn.statusCode()).isEqualTo(302);
    assertThat(location(afterSignIn)).isEqualTo("/signed-in?login_error=account_not_enabled");
    assertThat(send(HttpRequest.newBuilder(URI.create(base + "/api/v1/me"))).statusCode())
        .isEqualTo(401);
  }

  @Test
  void wrongPasswordNeverReachesGcare() throws Exception {
    HttpResponse<String> start =
        send(HttpRequest.newBuilder(URI.create(base + "/oauth2/authorization/keycloak")));
    HttpResponse<String> loginPage = send(HttpRequest.newBuilder(URI.create(location(start))));
    HttpResponse<String> rejected =
        postForm(formAction(loginPage.body()), "demo.admin", "wrong-password-123");

    assertThat(rejected.statusCode()).isEqualTo(200); // Keycloak shows its login page again
    // GCare holds a session for the pending sign-in (state, PKCE verifier), but it is not signed
    // in.
    assertThat(send(HttpRequest.newBuilder(URI.create(base + "/api/v1/me"))).statusCode())
        .isEqualTo(401);
  }

  /** Runs the browser flow; returns GCare's response to the Keycloak callback. */
  private HttpResponse<String> signIn(String username) throws Exception {
    HttpResponse<String> start =
        send(HttpRequest.newBuilder(URI.create(base + "/oauth2/authorization/keycloak")));
    assertThat(start.statusCode()).isEqualTo(302);
    String authorize = location(start);
    assertThat(authorize)
        .startsWith(issuer() + "/protocol/openid-connect/auth")
        .contains("code_challenge=")
        .contains("code_challenge_method=S256");

    HttpResponse<String> loginPage = send(HttpRequest.newBuilder(URI.create(authorize)));
    assertThat(loginPage.statusCode()).isEqualTo(200);

    HttpResponse<String> afterCredentials =
        postForm(formAction(loginPage.body()), username, PASSWORD);
    assertThat(afterCredentials.statusCode()).isEqualTo(302);
    String callback = location(afterCredentials);
    assertThat(callback).startsWith(base + "/login/oauth2/code/keycloak");

    return send(HttpRequest.newBuilder(URI.create(callback)));
  }

  private HttpResponse<String> postForm(String action, String username, String password)
      throws Exception {
    String form =
        "username="
            + URLEncoder.encode(username, StandardCharsets.UTF_8)
            + "&password="
            + URLEncoder.encode(password, StandardCharsets.UTF_8)
            + "&credentialId=";
    return send(
        HttpRequest.newBuilder(URI.create(action))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form)));
  }

  private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String location(HttpResponse<?> response) {
    return response.headers().firstValue("Location").orElseThrow();
  }

  private static String formAction(String html) {
    Matcher m =
        Pattern.compile("<form[^>]*id=\"kc-form-login\"[^>]*action=\"([^\"]+)\"", Pattern.DOTALL)
            .matcher(html);
    if (!m.find()) {
      m = Pattern.compile("<form[^>]*action=\"([^\"]+)\"[^>]*id=\"kc-form-login\"").matcher(html);
      assertThat(m.find()).as("Keycloak login form").isTrue();
    }
    return m.group(1).replace("&amp;", "&");
  }

  /**
   * Minimal cookie jar that, like a browser, sends {@code Secure} cookies to http://localhost
   * (Keycloak's login-session cookie is Secure; java.net.CookieManager would drop it). Every cookie
   * is sent to every localhost port, which is fine for this test.
   */
  static final class BrowserCookies extends CookieHandler {

    private final Map<String, String> values = new LinkedHashMap<>();
    private final Map<String, String> attributes = new LinkedHashMap<>();

    @Override
    public synchronized Map<String, List<String>> get(URI uri, Map<String, List<String>> headers) {
      if (values.isEmpty()) {
        return Map.of();
      }
      StringBuilder header = new StringBuilder();
      values.forEach((name, value) ->
          header.append(header.isEmpty() ? "" : "; ").append(name).append('=').append(value));
      return Map.of("Cookie", List.of(header.toString()));
    }

    @Override
    public synchronized void put(URI uri, Map<String, List<String>> headers) {
      headers.forEach((key, list) -> {
        if (key == null || !key.equalsIgnoreCase("Set-Cookie")) {
          return;
        }
        for (String raw : list) {
          String[] parts = raw.split(";", 2);
          int eq = parts[0].indexOf('=');
          String name = parts[0].substring(0, eq).trim();
          String value = parts[0].substring(eq + 1).trim();
          String attrs = parts.length > 1 ? parts[1] : "";
          if (value.isEmpty() || attrs.matches("(?i).*max-age=0(;.*)?")) {
            values.remove(name);
            attributes.remove(name);
          } else {
            values.put(name, value);
            attributes.put(name, attrs);
          }
        }
      });
    }

    synchronized boolean has(String name) {
      return values.containsKey(name);
    }

    synchronized String value(String name) {
      String value = values.get(name);
      if (value == null) {
        throw new AssertionError("No cookie " + name + "; have " + values.keySet());
      }
      return value;
    }

    synchronized String attributes(String name) {
      value(name);
      return attributes.get(name);
    }
  }
}
