package com.gcare.hms.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a correlation ID (docs/05-API.md): reused from {@code X-Correlation-Id} when
 * the caller sends a safe value, otherwise generated. It is echoed in the response header, placed
 * in the logging MDC and included in every error body. Runs before Spring Security so rejected
 * requests are traceable too.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Correlation-Id";
  public static final String MDC_KEY = "correlationId";

  private static final Pattern SAFE_VALUE = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String supplied = request.getHeader(HEADER);
    String correlationId =
        supplied != null && SAFE_VALUE.matcher(supplied).matches()
            ? supplied
            : UUID.randomUUID().toString();
    MDC.put(MDC_KEY, correlationId);
    response.setHeader(HEADER, correlationId);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }

  /** The current request's correlation ID, or {@code null} outside a request. */
  public static String current() {
    return MDC.get(MDC_KEY);
  }
}
