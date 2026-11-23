package com.gcare.hms.shared.web;

import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps exceptions to the standard {@link ApiError} body. Unexpected errors are logged with the
 * correlation ID and answered with a generic message; details never reach the caller.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ApiError> handleApi(ApiException e) {
    return respond(e.status(), ApiError.of(e.code(), e.getMessage(), e.fieldErrors()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ApiError> handleInvalidBody(MethodArgumentNotValidException e) {
    Map<String, List<String>> fields = new TreeMap<>();
    e.getBindingResult()
        .getFieldErrors()
        .forEach(fe ->
            fields
                .computeIfAbsent(fe.getField(), k -> new java.util.ArrayList<>())
                .add(fe.getDefaultMessage()));
    e.getBindingResult()
        .getGlobalErrors()
        .forEach(ge ->
            fields
                .computeIfAbsent("_global", k -> new java.util.ArrayList<>())
                .add(ge.getDefaultMessage()));
    return respond(
        HttpStatus.BAD_REQUEST, ApiError.of("VALIDATION_FAILED", "Request is invalid", fields));
  }

  @ExceptionHandler(ConstraintViolationException.class)
  ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException e) {
    Map<String, List<String>> fields = new LinkedHashMap<>();
    e.getConstraintViolations()
        .forEach(v ->
            fields
                .computeIfAbsent(v.getPropertyPath().toString(), k -> new java.util.ArrayList<>())
                .add(v.getMessage()));
    return respond(
        HttpStatus.BAD_REQUEST, ApiError.of("VALIDATION_FAILED", "Request is invalid", fields));
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class
  })
  ResponseEntity<ApiError> handleMalformed(Exception e) {
    return respond(
        HttpStatus.BAD_REQUEST, ApiError.of("MALFORMED_REQUEST", "Request could not be read"));
  }

  @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
  ResponseEntity<ApiError> handleStale(ObjectOptimisticLockingFailureException e) {
    return respond(
        HttpStatus.CONFLICT,
        ApiError.of("CONFLICT", "The record was changed by someone else; reload and retry"));
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<ApiError> handleNoResource(NoResourceFoundException e) {
    return respond(HttpStatus.NOT_FOUND, ApiError.of("NOT_FOUND", "Resource not found"));
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<ApiError> handleMethod(HttpRequestMethodNotSupportedException e) {
    return respond(
        HttpStatus.METHOD_NOT_ALLOWED, ApiError.of("METHOD_NOT_ALLOWED", "Method not allowed"));
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ResponseEntity<ApiError> handleMediaType(HttpMediaTypeNotSupportedException e) {
    return respond(
        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
        ApiError.of("UNSUPPORTED_MEDIA_TYPE", "Content type not supported"));
  }

  /** Method-security denials raised inside controllers/services. */
  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException e) {
    return respond(HttpStatus.FORBIDDEN, ApiError.of("FORBIDDEN", "Access denied"));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiError> handleUnexpected(Exception e) {
    log.error("Unhandled exception", e);
    return respond(
        HttpStatus.INTERNAL_SERVER_ERROR,
        ApiError.of("INTERNAL_ERROR", "An unexpected error occurred"));
  }

  private static ResponseEntity<ApiError> respond(HttpStatus status, ApiError body) {
    return ResponseEntity.status(status).body(body);
  }
}
