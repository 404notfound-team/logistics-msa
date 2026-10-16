package com.notfound.logistics.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notfound.logistics.common.api.ApiErrorResponse;
import com.notfound.logistics.common.api.ApiResponse;
import com.notfound.logistics.common.api.CommonErrorCode;
import com.notfound.logistics.common.api.PageResponse;
import com.notfound.logistics.users.UsersApplication;
import com.notfound.logistics.users.application.service.UserService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@ActiveProfiles("local")
@SpringBootTest(
    classes = UsersApplication.class,
    properties = {
      "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
      "spring.data.jpa.repositories.enabled=false",
      "users.audit.enabled=false",
      "users.jwt.secret=test-only-signing-key-for-users-module-tests-32bytes",
      "eureka.client.enabled=false",
      "management.tracing.enabled=false"
    })
@AutoConfigureMockMvc
@Import(OpenApiDocumentationTest.ContractController.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@WithMockUser
class OpenApiDocumentationTest {
  @MockitoBean private UserService userService;

  @Autowired private MockMvc mvc;

  @Test
  void onlyPublicOperationsAreDocumentedWithGatewayRelativeServer() throws Exception {
    mvc.perform(get("/v3/api-docs").header("X-Forwarded-Host", "untrusted.invalid"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.info.title").value("사용자 API"))
        .andExpect(jsonPath("$.servers[0].url").value("/"))
        .andExpect(jsonPath("$.paths['/api/users/openapi-test'].get").exists())
        .andExpect(jsonPath("$.components.schemas.PublicResponse.properties.user_name").exists())
        .andExpect(
            jsonPath("$.components.schemas.PublicResponse.properties.userName").doesNotExist())
        .andExpect(
            jsonPath("$.components.schemas.PublicResponse.properties.user_name.description")
                .value("사용자 이름"))
        .andExpect(jsonPath("$.paths['/internal/openapi-test']").doesNotExist())
        .andExpect(jsonPath("$.paths['/internal/infra/ping']").doesNotExist())
        .andExpect(jsonPath("$.paths['/actuator/health']").doesNotExist())
        .andExpect(jsonPath("$.paths['/error']").doesNotExist());
    mvc.perform(get("/api/users/openapi-test"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user_name").value("fixture"))
        .andExpect(jsonPath("$.userName").doesNotExist());
  }

  @RestController
  static class ContractController {
    @GetMapping("/api/users/openapi-test")
    PublicResponse publicOperation() {
      return new PublicResponse("fixture");
    }

    @GetMapping("/internal/openapi-test")
    PublicResponse internalOperation() {
      return new PublicResponse("internal");
    }

    @GetMapping("/api/users/common-contract-test")
    ApiResponse<PageResponse<PublicResponse>> pagedOperation() {
      return new ApiResponse<>(
          new PageResponse<>(List.of(new PublicResponse("fixture")), 0, 20, 1));
    }

    @GetMapping("/api/users/common-error-test")
    ResponseEntity<ApiErrorResponse> errorOperation() {
      return ResponseEntity.status(CommonErrorCode.NOT_FOUND.httpStatus())
          .body(ApiErrorResponse.of(CommonErrorCode.NOT_FOUND, null));
    }
  }

  @Test
  void sharedSuccessAndErrorTypesMatchTheirOpenApiSchemas() throws Exception {
    mvc.perform(get("/api/users/common-contract-test"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].user_name").value("fixture"))
        .andExpect(jsonPath("$.data.total_elements").value(1))
        .andExpect(jsonPath("$.data.total_pages").value(1));
    mvc.perform(get("/api/users/common-error-test"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"))
        .andExpect(jsonPath("$.trace_id").value(nullValue()))
        .andExpect(jsonPath("$.field_errors").isEmpty())
        .andExpect(jsonPath("$.data").doesNotExist());
    var result =
        mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andExpect(
                jsonPath(
                        "$.components.schemas.PageResponsePublicResponse.properties.total_elements")
                    .exists())
            .andExpect(
                jsonPath("$.components.schemas.PageResponsePublicResponse.properties.total_pages")
                    .exists())
            .andExpect(
                jsonPath("$.components.schemas.ApiErrorResponse.properties.trace_id").exists())
            .andExpect(
                jsonPath("$.components.schemas.ApiErrorResponse.properties.field_errors").exists())
            .andExpect(
                jsonPath("$.components.schemas.ApiErrorResponse.properties.traceId").doesNotExist())
            .andReturn();
    var openApi = new ObjectMapper().readTree(result.getResponse().getContentAsString());
    var traceSchema =
        openApi
            .path("components")
            .path("schemas")
            .path("ApiErrorResponse")
            .path("properties")
            .path("trace_id");
    if (openApi.path("openapi").asText().startsWith("3.1")) {
      assertThat(traceSchema.path("type").isArray()).isTrue();
      assertThat(traceSchema.path("type").toString()).contains("\"string\"", "\"null\"");
    } else {
      assertThat(traceSchema.path("nullable").asBoolean()).isTrue();
    }
  }

  record PublicResponse(@Schema(description = "사용자 이름") String userName) {}
}
