package com.gcare.hms.shared.security;

import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationEventPublisher;
import org.springframework.security.authorization.SpringAuthorizationEventPublisher;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;

/**
 * Deny-by-default security (docs/08-FIRST-SPRINT.md; sign-in model ADR-012).
 *
 * <ul>
 *   <li>Public: health probes only.
 *   <li>Signed-in users: {@code /api/v1/**} and the OpenAPI document. What each user may do inside
 *       is decided per operation by permission checks in the services.
 *   <li>Everything else: denied.
 * </ul>
 *
 * Sign-in (Keycloak, OIDC) and the per-request user lookup are added by the identity module through
 * {@link HttpSecurityContributor}. The browser holds only an HttpOnly session cookie; tokens stay
 * on the server. CSRF protection is on for every state-changing request (SPA cookie mode: cookie
 * {@code XSRF-TOKEN}, header {@code X-XSRF-TOKEN}).
 *
 * <p>Do not loosen these rules to make a feature work; add a permission check instead.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http, JsonMapper json, List<HttpSecurityContributor> contributors)
      throws Exception {
    http.authorizeHttpRequests(auth ->
            auth.requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**")
                .permitAll()
                .requestMatchers("/api/v1/**")
                .authenticated()
                .requestMatchers(HttpMethod.GET, "/v3/api-docs", "/v3/api-docs/**")
                .authenticated()
                .anyRequest()
                .denyAll())
        .exceptionHandling(e ->
            e.authenticationEntryPoint(ApiErrorSecurityHandlers.unauthenticated(json))
                .accessDeniedHandler(ApiErrorSecurityHandlers.forbidden(json)))
        .csrf(csrf -> csrf.spa())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
        .httpBasic(b -> b.disable())
        .formLogin(f -> f.disable());
    for (HttpSecurityContributor contributor : contributors) {
      contributor.contribute(http);
    }
    return http.build();
  }

  /** Publishes denied authorization decisions so they can be audited. */
  @Bean
  AuthorizationEventPublisher authorizationEventPublisher(ApplicationEventPublisher publisher) {
    return new SpringAuthorizationEventPublisher(publisher);
  }

  /**
   * GCare has no local password accounts (ADR-012); this prevents Boot's generated default user.
   */
  @Bean
  UserDetailsService userDetailsService() {
    return new InMemoryUserDetailsManager();
  }
}
