package com.gcare.hms.identity;

import com.gcare.hms.shared.security.HttpSecurityContributor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sign-in through Keycloak with OIDC (OD-03, ADR-012).
 *
 * <ul>
 *   <li>Start: browser goes to {@code /oauth2/authorization/keycloak}. Authorization code flow with
 *       PKCE; the backend is a confidential client.
 *   <li>Callback: {@code /login/oauth2/code/keycloak}; {@link SignInHandlers} accepts only linked,
 *       active GCare users.
 *   <li>Every request: {@link CurrentUserFilter} reloads the user's status and permissions.
 *   <li>Sign-out: {@code POST /api/v1/auth/logout} via {@link SignOutHandlers}.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdentityProperties.class)
class IdentitySecurityConfiguration {

  @Bean
  HttpSecurityContributor identitySecurity(
      IdentityProperties properties,
      UserDirectory directory,
      IdentityAudit audit,
      ClientRegistrationRepository clients,
      JsonMapper json) {
    SignInHandlers signIn = new SignInHandlers(directory, audit, properties.postLoginRedirect());
    SignOutHandlers signOut =
        new SignOutHandlers(directory, audit, clients, json, properties.postLogoutRedirect());
    DefaultOAuth2AuthorizationRequestResolver authorizationRequests =
        new DefaultOAuth2AuthorizationRequestResolver(
            clients,
            OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
    authorizationRequests.setAuthorizationRequestCustomizer(
        OAuth2AuthorizationRequestCustomizers.withPkce());

    return http ->
        http.oauth2Login(login ->
                login
                    // Setting the login page stops Spring from generating its own HTML login page.
                    .loginPage(
                        OAuth2AuthorizationRequestRedirectFilter
                                .DEFAULT_AUTHORIZATION_REQUEST_BASE_URI
                            + "/"
                            + properties.registrationId())
                    .authorizationEndpoint(
                        a -> a.authorizationRequestResolver(authorizationRequests))
                    .successHandler(signIn)
                    .failureHandler(signIn))
            .logout(logout ->
                logout
                    .logoutUrl("/api/v1/auth/logout")
                    .addLogoutHandler(signOut)
                    .logoutSuccessHandler(signOut))
            .addFilterBefore(
                new CurrentUserFilter(directory, audit, properties.sessionMaxLifetime()),
                AuthorizationFilter.class);
  }
}
