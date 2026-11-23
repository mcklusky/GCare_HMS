package com.gcare.hms.shared.security;

import com.gcare.hms.shared.web.ApiError;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

/** Writes Spring Security rejections in the standard {@link ApiError} shape. */
final class ApiErrorSecurityHandlers {

  private ApiErrorSecurityHandlers() {}

  /** 401 for requests without (valid) authentication. */
  static AuthenticationEntryPoint unauthenticated(JsonMapper json) {
    return (request, response, ex) ->
        write(
            response,
            json,
            HttpStatus.UNAUTHORIZED,
            ApiError.of("UNAUTHENTICATED", "Authentication required"));
  }

  /** 403 for authenticated callers without permission. */
  static AccessDeniedHandler forbidden(JsonMapper json) {
    return (request, response, ex) ->
        write(response, json, HttpStatus.FORBIDDEN, ApiError.of("FORBIDDEN", "Access denied"));
  }

  private static void write(
      HttpServletResponse response, JsonMapper json, HttpStatus status, ApiError body)
      throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    json.writeValue(response.getOutputStream(), body);
  }
}
