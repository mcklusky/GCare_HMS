package com.gcare.hms.radiology;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection to the external imaging archive ({@code gcare.imaging.archive.*}; docs/24). Nothing is
 * configured by default: without {@code base-url} the HMS reports the archive as not configured.
 *
 * @param baseUrl DICOMweb root, e.g. {@code https://archive.example/dicom-web}
 * @param username basic-auth user (optional)
 * @param password basic-auth password (optional; from a secret store, never committed)
 * @param bearerToken alternative to basic auth (optional)
 * @param viewerUrlTemplate viewer link with {@code {StudyInstanceUID}}, e.g. {@code
 *     https://archive.example/viewer?study={StudyInstanceUID}} (optional)
 * @param timeout per request
 * @param maxResults upper bound on studies returned per query
 */
@ConfigurationProperties("gcare.imaging.archive")
public record ImagingArchiveProperties(
    URI baseUrl,
    String username,
    String password,
    String bearerToken,
    String viewerUrlTemplate,
    Duration timeout,
    Integer maxResults) {

  public ImagingArchiveProperties {
    timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
    maxResults = maxResults == null ? 200 : maxResults;
    if (baseUrl != null
        && !("https".equals(baseUrl.getScheme()) || "http".equals(baseUrl.getScheme()))) {
      throw new IllegalArgumentException("gcare.imaging.archive.base-url must be http(s)");
    }
    if (viewerUrlTemplate != null
        && !viewerUrlTemplate.isBlank()
        && !viewerUrlTemplate.contains("{StudyInstanceUID}")) {
      throw new IllegalArgumentException(
          "gcare.imaging.archive.viewer-url-template must contain {StudyInstanceUID}");
    }
    if (viewerUrlTemplate != null
        && !viewerUrlTemplate.isBlank()
        && !(viewerUrlTemplate.startsWith("https://") || viewerUrlTemplate.startsWith("http://"))) {
      // The link is shown to clinicians; never let a template become a script or another scheme.
      throw new IllegalArgumentException(
          "gcare.imaging.archive.viewer-url-template must be an http(s) URL");
    }
  }
}
