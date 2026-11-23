package com.gcare.hms.testsupport;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

/**
 * Replaces issuer discovery with a fixed Keycloak client registration, so tests start without a
 * running identity provider. MockMvc tests then sign in with {@code IdentityFixtures}. Turned off
 * with {@code gcare.test.offline-idp=false} (KeycloakSignInTests).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = "gcare.test.offline-idp", matchIfMissing = true)
public class OfflineIdentityProviderConfiguration {

  public static ClientRegistration registration() {
    return ClientRegistration.withRegistrationId("keycloak")
        .clientId("gcare-hms-backend")
        .clientSecret("test-only-not-a-secret")
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
        .scope("openid", "profile", "email")
        .authorizationUri("http://keycloak.test.invalid/realms/gcare/protocol/openid-connect/auth")
        .tokenUri("http://keycloak.test.invalid/realms/gcare/protocol/openid-connect/token")
        .jwkSetUri("http://keycloak.test.invalid/realms/gcare/protocol/openid-connect/certs")
        .userNameAttributeName("sub")
        .build();
  }

  @Bean
  ClientRegistrationRepository clientRegistrationRepository() {
    return new InMemoryClientRegistrationRepository(registration());
  }
}
