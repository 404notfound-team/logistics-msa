package com.notfound.logistics.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notfound.logistics.orders.application.service.OrderService;
import com.notfound.logistics.orders.domain.exception.OrderBusinessException;
import com.notfound.logistics.orders.domain.exception.OrderErrorCode;
import com.notfound.logistics.orders.infrastructure.security.OrderSecurityConfiguration;
import com.notfound.logistics.orders.presentation.controller.OrderController;
import com.notfound.logistics.orders.support.OrderClientFixture;
import java.util.UUID;
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
    controllers = OrderController.class,
    properties = {
      "springdoc.api-docs.enabled=true",
      "orders.jwt.secret=" + OrderClientFixture.SECRET
    })
@Import({OpenApiConfiguration.class, OrderSecurityConfiguration.class})
@ImportAutoConfiguration({
  SpringDocConfiguration.class,
  SpringDocConfigProperties.class,
  SpringDocSecurityConfiguration.class,
  SpringDocWebMvcConfiguration.class
})
class OpenApiDocumentationTest {
  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper mapper;
  @MockitoBean private OrderService service;

  @Test
  void documentsOrderOutcomesAndErrorsWithoutReplacingSuccessSchemas() throws Exception {
    var doc = document();
    assertErrorSchemas(doc, 5);
    var cancel = responses(doc, "/api/orders/{id}/cancel", "post");
    assertThat(cancel.has("200")).isTrue();
    assertThat(cancel.has("202")).isTrue();
    assertThat(cancel.has("409")).isTrue();
    assertThat(
            cancel
                .path("409")
                .path("content")
                .path("application/json")
                .path("examples")
                .path("ORDER_STATE_CONFLICT")
                .path("value")
                .path("code")
                .asText())
        .isEqualTo("ORDER_STATE_CONFLICT");
    assertThat(cancel.path("202").path("description").asText()).contains("RECOVERY_REQUIRED");
    for (String code : java.util.List.of("200", "202")) {
      assertThat(
              cancel
                  .path(code)
                  .path("content")
                  .elements()
                  .next()
                  .path("schema")
                  .path("$ref")
                  .asText())
          .isEqualTo("#/components/schemas/ApiResponseOrderResponse");
    }
    var create = responses(doc, "/api/orders", "post");
    assertThat(create.has("201")).isTrue();
    assertThat(create.has("502")).isTrue();
    assertThat(
            create
                .path("503")
                .path("content")
                .path("application/json")
                .path("examples")
                .has("SERVICE_UNAVAILABLE"))
        .isTrue();
    assertThat(
            create
                .path("503")
                .path("content")
                .path("application/json")
                .path("examples")
                .has("UPSTREAM_UNAVAILABLE"))
        .isTrue();
    var delete = responses(doc, "/api/orders/{id}", "delete");
    assertThat(delete.has("204")).isTrue();
    assertThat(delete.has("200")).isFalse();
    assertThat(delete.path("204").has("content")).isFalse();
    assertThat(doc.path("paths").toString()).doesNotContain("/internal/");
  }

  @Test
  void cancellationConflictBodyMatchesTheDocumentedErrorExample() throws Exception {
    when(service.cancel(any(UUID.class), eq("alice"), eq(false)))
        .thenThrow(new OrderBusinessException(OrderErrorCode.ORDER_STATE_CONFLICT));
    var result =
        mvc.perform(
                post("/api/orders/" + UUID.randomUUID() + "/cancel")
                    .header("Authorization", "Bearer " + OrderClientFixture.token("alice", "USER")))
            .andExpect(status().isConflict())
            .andReturn();
    var actual = mapper.readTree(result.getResponse().getContentAsString());
    var expected =
        responses(document(), "/api/orders/{id}/cancel", "post")
            .path("409")
            .path("content")
            .path("application/json")
            .path("examples")
            .path("ORDER_STATE_CONFLICT")
            .path("value");
    assertThat(actual).isEqualTo(expected);
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
