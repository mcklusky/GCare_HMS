package com.gcare.hms.shared.security;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;

/**
 * Lets a module add to the security filter chain (sign-in, sign-out, request filters) without the
 * shared configuration depending on that module. The deny-by-default rules stay in {@link
 * SecurityConfig}; contributors must not loosen them.
 */
@FunctionalInterface
public interface HttpSecurityContributor {

  void contribute(HttpSecurity http) throws Exception;
}
