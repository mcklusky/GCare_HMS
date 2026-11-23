package com.gcare.hms.shared.web;

import org.springframework.http.HttpStatus;

/**
 * Base for expected API failures. Services throw a subclass; {@link GlobalExceptionHandler} maps it
 * to the standard error body. The message must be safe to show to the caller.
 */
public abstract class ApiException extends RuntimeException {

  private final HttpStatus status;
  private final String code;

  protected ApiException(HttpStatus status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public HttpStatus status() {
    return status;
  }

  public String code() {
    return code;
  }

  /** 404: the resource does not exist or is outside the caller's scope (no disclosure). */
  public static class NotFound extends ApiException {
    public NotFound(String resourceType) {
      super(HttpStatus.NOT_FOUND, "NOT_FOUND", resourceType + " not found");
    }
  }

  /** 409: conflicting edit or duplicate (docs/05-API.md "Conflict returns 409"). */
  public static class Conflict extends ApiException {
    public Conflict(String message) {
      super(HttpStatus.CONFLICT, "CONFLICT", message);
    }
  }

  /** 422: the requested state transition is not allowed from the current state. */
  public static class InvalidTransition extends ApiException {
    public InvalidTransition(String message) {
      super(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_TRANSITION", message);
    }
  }
}
