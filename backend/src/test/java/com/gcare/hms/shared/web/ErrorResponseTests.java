package com.gcare.hms.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Error body shape and correlation IDs (docs/05-API.md), using a test-only controller. */
class ErrorResponseTests {

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc =
        MockMvcBuilders.standaloneSetup(new TestController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new CorrelationIdFilter())
            .build();
  }

  @Test
  void validationErrorsListEachField() throws Exception {
    MvcResult result =
        mvc.perform(
                post("/test/things")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"\",\"code\":\"waytoolongcode\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.message").value("Request is invalid"))
            .andExpect(jsonPath("$.field_errors.name").isArray())
            .andExpect(jsonPath("$.field_errors.code").isArray())
            .andReturn();
    String headerId = result.getResponse().getHeader(CorrelationIdFilter.HEADER);
    assertThat(headerId).isNotBlank();
    assertThat(result.getResponse().getContentAsString()).contains(headerId);
  }

  @Test
  void malformedJsonIsRejectedWithoutParserDetails() throws Exception {
    mvc.perform(post("/test/things").contentType(MediaType.APPLICATION_JSON).content("{not json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
        .andExpect(jsonPath("$.message").value("Request could not be read"));
  }

  @Test
  void notFoundUsesSafeMessage() throws Exception {
    mvc.perform(get("/test/missing"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"))
        .andExpect(jsonPath("$.message").value("Patient not found"));
  }

  @Test
  void invalidTransitionIs422() throws Exception {
    mvc.perform(get("/test/transition"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
  }

  @Test
  void conflictIs409() throws Exception {
    mvc.perform(get("/test/conflict"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONFLICT"));
  }

  @Test
  void unexpectedErrorsHideInternalDetails() throws Exception {
    MvcResult result =
        mvc.perform(get("/test/boom"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
            .andReturn();
    assertThat(result.getResponse().getContentAsString())
        .doesNotContain("SELECT")
        .doesNotContain("secret");
  }

  @Test
  void safeCallerCorrelationIdIsEchoed() throws Exception {
    mvc.perform(get("/test/missing").header(CorrelationIdFilter.HEADER, "abc-12345-XYZ"))
        .andExpect(header().string(CorrelationIdFilter.HEADER, "abc-12345-XYZ"))
        .andExpect(jsonPath("$.correlation_id").value("abc-12345-XYZ"));
  }

  @Test
  void unsafeCallerCorrelationIdIsReplaced() throws Exception {
    MvcResult result =
        mvc.perform(get("/test/missing").header(CorrelationIdFilter.HEADER, "<script>x</script>"))
            .andReturn();
    String id = result.getResponse().getHeader(CorrelationIdFilter.HEADER);
    assertThat(id).doesNotContain("<").matches("[0-9a-f-]{36}");
  }

  record ThingRequest(@NotBlank String name, @Size(max = 5) String code) {}

  @RestController
  static class TestController {

    @PostMapping("/test/things")
    String create(@Valid @RequestBody ThingRequest request) {
      return "ok";
    }

    @GetMapping("/test/missing")
    String missing() {
      throw new ApiException.NotFound("Patient");
    }

    @GetMapping("/test/transition")
    String transition() {
      throw new ApiException.InvalidTransition("Signed notes cannot be edited");
    }

    @GetMapping("/test/conflict")
    String conflict() {
      throw new ApiException.Conflict("Duplicate submission");
    }

    @GetMapping("/test/boom")
    String boom() {
      throw new IllegalStateException("SELECT * FROM secret_table failed");
    }
  }
}
