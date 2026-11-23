package com.gcare.hms.shared.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Standard error body for every API error (docs/05-API.md): {@code {"code", "message",
 * "field_errors", "correlation_id"}}.
 *
 * <p>{@code message} is safe for display. It never contains stack traces, SQL, patient data or
 * other internal details.
 */
public record ApiError(
    String code,
    String message,
    @JsonProperty("field_errors") Map<String, List<String>> fieldErrors,
    @JsonProperty("correlation_id") String correlationId) {

  public static ApiError of(String code, String message) {
    return new ApiError(code, message, Map.of(), CorrelationIdFilter.current());
  }

  public static ApiError of(String code, String message, Map<String, List<String>> fieldErrors) {
    return new ApiError(code, message, fieldErrors, CorrelationIdFilter.current());
  }
}
