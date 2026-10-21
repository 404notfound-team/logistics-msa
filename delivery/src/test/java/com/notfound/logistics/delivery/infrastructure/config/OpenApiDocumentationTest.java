package com.notfound.logistics.delivery.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notfound.logistics.delivery.application.service.DeliveryCommandService;
import com.notfound.logistics.delivery.application.service.DeliveryCreationService;
import com.notfound.logistics.delivery.application.service.DeliveryQueryService;
import com.notfound.logistics.delivery.infrastructure.security.DeliverySecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.configuration.SpringDocSecurityConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
    properties = {
      "springdoc.api-docs.enabled=true",
      "delivery.jwt.secret=delivery-openapi-test-secret-0123456789"
    })
@Import({OpenApiConfiguration.class, DeliverySecurityConfiguration.class})
@ImportAutoConfiguration({
  SpringDocConfiguration.class,
  SpringDocConfigProperties.class,
  SpringDocSecurityConfiguration.class,
  SpringDocWebMvcConfiguration.class
})
class OpenApiDocumentationTest {
  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper mapper;
  @MockitoBean private DeliveryQueryService queryService;
  @MockitoBean private DeliveryCommandService commandService;
  @MockitoBean private DeliveryCreationService creationService;

  @Test
  void publicDeliveryDocumentRequiresBearerAuthenticationAndKeepsItsInfo() throws Exception {
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.info.title").value("배송 API"))
        .andExpect(jsonPath("$.servers[0].url").value("/"))
        .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
        .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
        .andExpect(jsonPath("$.security[0].bearerAuth").isArray())
        .andExpect(jsonPath("$.paths['/api/deliveries'].get").exists())
        .andExpect(jsonPath("$.paths['/internal/deliveries']").doesNotExist());
  }

  @Test
  void documentsReadAuthorizationAndModificationConflictsWithErrorSchemas() throws Exception {
    var doc = document();
    assertErrorSchemas(doc, 4);
    var modify = responses(doc, "/api/deliveries/{delivery_id}", "patch");
    var examples = modify.path("409").path("content").path("application/json").path("examples");
    for (String code :
        java.util.List.of(
            "INVALID_STATUS_TRANSITION", "ASSIGNMENT_NOT_ALLOWED", "CONCURRENT_MODIFICATION"))
      assertThat(examples.has(code)).isTrue();
    assertThat(
            modify
                .path("200")
                .path("content")
                .elements()
                .next()
                .path("schema")
                .path("$ref")
                .asText())
        .isEqualTo("#/components/schemas/ApiResponseDeliveryResponse");
    assertThat(
            responses(doc, "/api/deliveries/{id}/routes", "get")
                .path("403")
                .path("content")
                .path("application/json")
                .path("examples")
                .has("DELIVERY_READ_FORBIDDEN"))
        .isTrue();
    assertThat(responses(doc, "/api/deliveries", "get").has("403")).isFalse();
    assertThat(modify.path("409").toString()).doesNotContain("DELIVERY_ALREADY_STARTED");
  }

  private JsonNode document() throws Exception {
    return mapper.readTree(
        mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private JsonNode responses(JsonNode doc, String path, String method) {
    return doc.path("paths").path(path).path(method).path("responses");
  }

  private void assertErrorSchemas(JsonNode doc, int operations) {
    int count = 0;
    for (JsonNode path : doc.path("paths")) {
      for (JsonNode operation : path) {
        count++;
        var responses = operation.path("responses");
        for (String code : java.util.List.of("400", "401", "500", "503")) {
          assertThat(responses.has(code)).as("%s %s", operation.path("operationId"), code).isTrue();
        }
        responses
            .properties()
            .forEach(
                response -> {
                  if (Integer.parseInt(response.getKey()) < 400) return;
                  assertThat(response.getValue().path("content").isEmpty()).isFalse();
                  for (JsonNode media : response.getValue().path("content")) {
                    assertThat(media.path("schema").path("$ref").asText())
                        .isEqualTo("#/components/schemas/ApiErrorResponse");
                    var examples = new java.util.ArrayList<JsonNode>();
                    media.path("examples").forEach(example -> examples.add(example.path("value")));
                    if (media.has("example")) examples.add(media.path("example"));
                    assertThat(examples).isNotEmpty();
                    for (JsonNode example : examples) {
                      assertThat(example.has("code")).isTrue();
                      assertThat(example.has("trace_id")).isTrue();
                      assertThat(example.path("field_errors").isArray()).isTrue();
                    }
                  }
                });
      }
    }
    assertThat(count).isEqualTo(operations);
    assertThat(
            doc.path("components")
                .path("schemas")
                .path("ApiErrorResponse")
                .path("properties")
                .has("trace_id"))
        .isTrue();
    assertThat(
            doc.path("components")
                .path("schemas")
                .path("ApiErrorResponse")
                .path("properties")
                .has("field_errors"))
        .isTrue();
  }
}
