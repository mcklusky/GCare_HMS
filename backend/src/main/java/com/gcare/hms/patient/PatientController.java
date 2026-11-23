package com.gcare.hms.patient;

import com.gcare.hms.patient.PatientApi.DuplicateCandidate;
import com.gcare.hms.patient.PatientApi.DuplicateCandidatesError;
import com.gcare.hms.patient.PatientApi.Page;
import com.gcare.hms.patient.PatientApi.PatientDetail;
import com.gcare.hms.patient.PatientApi.PatientSummary;
import com.gcare.hms.patient.PatientApi.RegisterPatientRequest;
import com.gcare.hms.patient.PatientService.SearchCriteria;
import com.gcare.hms.shared.web.CorrelationIdFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Patient index (FR-003–FR-005). Authorization and audit are in {@link PatientService}. */
@RestController
@RequestMapping("/api/v1/patients")
@Tag(name = "Patients")
class PatientController {

  private final PatientService service;

  PatientController(PatientService service) {
    this.service = service;
  }

  @PostMapping
  @Operation(
      summary = "Register a patient",
      description =
          "Needs PATIENT_REGISTER at the registration facility and PATIENT_READ. If likely or"
              + " possible duplicates exist, answers 409 DUPLICATE_CANDIDATES with the candidates;"
              + " resend with duplicate_review naming every candidate (and a reason for likely"
              + " duplicates).")
  ResponseEntity<PatientDetail> register(@Valid @RequestBody RegisterPatientRequest request) {
    PatientDetail created = service.register(request);
    return ResponseEntity.created(URI.create("/api/v1/patients/" + created.id())).body(created);
  }

  @GetMapping
  @Operation(
      summary = "Search patients",
      description =
          "Needs PATIENT_READ. Requires a number, identifier, phone, or a name with birth date,"
              + " birth year or sex.")
  Page<PatientSummary> search(
      @RequestParam(required = false) String number,
      @RequestParam(name = "identifier_type", required = false) IdentifierType identifierType,
      @RequestParam(name = "identifier_value", required = false) String identifierValue,
      @RequestParam(required = false) String phone,
      @RequestParam(required = false) String name,
      @RequestParam(name = "birth_date", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate birthDate,
      @RequestParam(name = "birth_year", required = false) Integer birthYear,
      @RequestParam(required = false) Sex sex,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return service.search(
        new SearchCriteria(
            number, identifierType, identifierValue, phone, name, birthDate, birthYear, sex),
        page,
        size);
  }

  @GetMapping("/{id}")
  @Operation(summary = "Get a patient", description = "Needs PATIENT_READ. Audited.")
  PatientDetail get(@PathVariable UUID id) {
    return service.get(id);
  }

  @GetMapping("/{id}/possible-duplicates")
  @Operation(
      summary = "Likely and possible duplicates of a patient",
      description = "Needs PATIENT_READ.")
  List<DuplicateCandidate> possibleDuplicates(@PathVariable UUID id) {
    return service.possibleDuplicates(id);
  }

  /** Turns {@link DuplicateCandidatesException} into the 409 body with candidates. */
  @RestControllerAdvice(assignableTypes = PatientController.class)
  @Order(Ordered.HIGHEST_PRECEDENCE)
  static class Errors {

    @ExceptionHandler(DuplicateCandidatesException.class)
    ResponseEntity<DuplicateCandidatesError> duplicates(DuplicateCandidatesException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body(
              new DuplicateCandidatesError(
                  "DUPLICATE_CANDIDATES",
                  "Possible duplicate patients were found. Review them before registering.",
                  Map.of(),
                  CorrelationIdFilter.current(),
                  e.candidates()));
    }
  }
}
