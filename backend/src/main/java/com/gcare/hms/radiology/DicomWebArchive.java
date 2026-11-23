package com.gcare.hms.radiology;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link ImagingArchive} over standard DICOMweb QIDO-RS (PS3.18), so any conformant archive can be
 * evaluated (docs/24). Reads only the tags needed for matching. Never follows redirects, caps the
 * response size and validates study UIDs before using them in a link.
 */
class DicomWebArchive implements ImagingArchive {

  static final String STUDY_DATE = "00080020";
  static final String ACCESSION = "00080050";
  static final String MODALITIES = "00080061";
  static final String DESCRIPTION = "00081030";
  static final String PATIENT_ID = "00100020";
  static final String STUDY_UID = "0020000D";
  static final String SERIES_COUNT = "00201206";
  static final String INSTANCE_COUNT = "00201208";

  private static final Pattern UID = Pattern.compile("^[0-9]+(\\.[0-9]+)*$");
  private static final int MAX_BODY = 5 * 1024 * 1024;
  private static final int MAX_PAGES = 50;
  private static final DateTimeFormatter DICOM_DATE = DateTimeFormatter.BASIC_ISO_DATE;

  private final ImagingArchiveProperties props;
  private final JsonMapper json;
  private final HttpClient http;
  private final String base;

  DicomWebArchive(ImagingArchiveProperties props, JsonMapper json) {
    this.props = props;
    this.json = json;
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(props.timeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    String b = props.baseUrl().toString();
    this.base = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
  }

  @Override
  public boolean configured() {
    return true;
  }

  @Override
  public Status status() {
    long start = System.nanoTime();
    try {
      query("limit=1");
      return new Status(
          true, true, Duration.ofNanos(System.nanoTime() - start), "The archive answered");
    } catch (UnavailableException e) {
      return new Status(true, false, Duration.ofNanos(System.nanoTime() - start), e.getMessage());
    }
  }

  @Override
  public List<Study> studiesByAccession(String accessionNumber) {
    return query("AccessionNumber=" + encode(accessionNumber));
  }

  @Override
  public List<Study> studiesBetween(LocalDate from, LocalDate to) {
    // Page through the window: a busy department can have more studies than one answer holds, and a
    // study the sweep never sees would never link. MAX_PAGES bounds a misbehaving archive.
    String criteria = "StudyDate=" + DICOM_DATE.format(from) + "-" + DICOM_DATE.format(to);
    List<Study> all = new ArrayList<>();
    for (int page = 0; page < MAX_PAGES; page++) {
      int offset = page * props.maxResults();
      List<Study> batch = query(criteria + (offset == 0 ? "" : "&offset=" + offset));
      all.addAll(batch);
      if (batch.size() < props.maxResults()) {
        return all;
      }
    }
    throw new UnavailableException(
        "The archive returned more than " + MAX_PAGES + " pages of studies; shorten the look-back",
        null);
  }

  @Override
  public Optional<URI> viewerUrl(String studyInstanceUid) {
    String template = props.viewerUrlTemplate();
    if (template == null || template.isBlank() || !validUid(studyInstanceUid)) {
      return Optional.empty();
    }
    return Optional.of(URI.create(template.replace("{StudyInstanceUID}", studyInstanceUid)));
  }

  static boolean validUid(String uid) {
    return uid != null && uid.length() <= 64 && UID.matcher(uid).matches();
  }

  private List<Study> query(String criteria) {
    String url =
        base
            + "/studies?"
            + criteria
            + "&includefield="
            + DESCRIPTION
            + "&includefield="
            + SERIES_COUNT
            + "&includefield="
            + INSTANCE_COUNT
            + (criteria.startsWith("limit=") ? "" : "&limit=" + props.maxResults());
    HttpRequest.Builder req =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(props.timeout())
            .header("Accept", "application/dicom+json")
            .GET();
    if (props.bearerToken() != null && !props.bearerToken().isBlank()) {
      req.header("Authorization", "Bearer " + props.bearerToken());
    } else if (props.username() != null && !props.username().isBlank()) {
      String pair = props.username() + ":" + (props.password() == null ? "" : props.password());
      req.header(
          "Authorization",
          "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8)));
    }
    HttpResponse<java.io.InputStream> res;
    byte[] body;
    try {
      res = http.send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
      // Read at most one byte over the cap, so an oversized answer never fills the memory.
      try (java.io.InputStream in = res.body()) {
        body = in.readNBytes(MAX_BODY + 1);
      }
    } catch (java.io.IOException e) {
      throw new UnavailableException("The archive did not answer", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new UnavailableException("Interrupted while asking the archive", e);
    }
    int code = res.statusCode();
    if (code == 204) {
      return List.of();
    }
    if (code == 401 || code == 403) {
      throw new UnavailableException(
          "The archive refused the HMS credentials (" + code + ")", null);
    }
    if (code < 200 || code >= 300) {
      throw new UnavailableException("The archive answered with status " + code, null);
    }
    if (body.length > MAX_BODY) {
      throw new UnavailableException("The archive answer was too large", null);
    }
    JsonNode root;
    try {
      root = json.readTree(body);
    } catch (RuntimeException e) {
      throw new UnavailableException("The archive answer was not DICOM JSON", e);
    }
    if (!root.isArray()) {
      throw new UnavailableException("The archive answer was not a list of studies", null);
    }
    List<Study> studies = new ArrayList<>();
    for (JsonNode s : root) {
      String uid = text(s, STUDY_UID);
      if (!validUid(uid)) {
        continue; // never use an unusable identifier
      }
      studies.add(
          new Study(
              uid,
              text(s, ACCESSION),
              text(s, PATIENT_ID),
              texts(s, MODALITIES),
              date(text(s, STUDY_DATE)),
              text(s, DESCRIPTION),
              integer(s, SERIES_COUNT),
              integer(s, INSTANCE_COUNT)));
    }
    return studies;
  }

  private static JsonNode values(JsonNode s, String tag) {
    return s.path(tag).path("Value");
  }

  private static String text(JsonNode s, String tag) {
    JsonNode v = values(s, tag).path(0);
    if (v.isMissingNode() || v.isNull()) {
      return null;
    }
    String t = v.isString() ? v.asString() : v.toString();
    return t.isBlank() ? null : t.trim();
  }

  private static List<String> texts(JsonNode s, String tag) {
    List<String> out = new ArrayList<>();
    values(s, tag)
        .forEach(v -> {
          if (v.isString() && !v.asString().isBlank()) {
            out.add(v.asString().trim());
          }
        });
    return out;
  }

  private static Integer integer(JsonNode s, String tag) {
    String t = text(s, tag);
    try {
      return t == null ? null : Integer.valueOf(t);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static LocalDate date(String t) {
    try {
      return t == null || t.length() != 8 ? null : LocalDate.parse(t, DICOM_DATE);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private static String encode(String v) {
    return URLEncoder.encode(v, StandardCharsets.UTF_8);
  }

  /** Used when no archive is configured. */
  static final class NotConfigured implements ImagingArchive {

    @Override
    public boolean configured() {
      return false;
    }

    @Override
    public Status status() {
      return new Status(false, false, Duration.ZERO, "No imaging archive is configured");
    }

    @Override
    public List<Study> studiesByAccession(String accessionNumber) {
      throw new UnavailableException("No imaging archive is configured", null);
    }

    @Override
    public List<Study> studiesBetween(LocalDate from, LocalDate to) {
      throw new UnavailableException("No imaging archive is configured", null);
    }

    @Override
    public Optional<URI> viewerUrl(String studyInstanceUid) {
      return Optional.empty();
    }
  }
}
