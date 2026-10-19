package com.notfound.logistics.hub.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notfound.logistics.hub.application.service.hub.HubQueryService;
import com.notfound.logistics.hub.application.service.hubroute.HubRouteQueryService;
import com.notfound.logistics.hub.application.service.stock.StockCommandService;
import com.notfound.logistics.hub.application.service.stock.StockQueryService;
import com.notfound.logistics.hub.presentation.controller.hub.HubController;
import com.notfound.logistics.hub.presentation.controller.hubroute.HubRouteController;
import com.notfound.logistics.hub.presentation.controller.stock.StockController;
import com.notfound.logistics.hub.support.HubSecurityTest;
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
    controllers = {HubController.class, StockController.class, HubRouteController.class},
    properties = "springdoc.api-docs.enabled=true")
@Import(OpenApiConfiguration.class)
@HubSecurityTest
@ImportAutoConfiguration({
  SpringDocConfiguration.class,
  SpringDocConfigProperties.class,
  SpringDocSecurityConfiguration.class,
  SpringDocWebMvcConfiguration.class
})
class OpenApiDocumentationTest {
  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper mapper;
  @MockitoBean private HubQueryService hubs;
  @MockitoBean private HubRouteQueryService routes;
  @MockitoBean private StockQueryService stocks;
  @MockitoBean private StockCommandService commands;

  @Test
  void documentsPublicHubAndStockErrorsWithTheActualCreationStatus() throws Exception {
    var doc = document();
    assertErrorSchemas(doc, 8);
    var create = responses(doc, "/api/stocks", "post");
    assertThat(create.has("201")).isTrue();
    assertThat(create.has("200")).isFalse();
    assertThat(create.has("403")).isTrue();
    assertThat(create.has("404")).isTrue();
    assertThat(
            create
                .path("409")
                .path("content")
                .path("application/json")
                .path("examples")
                .has("STOCK_ALREADY_EXISTS"))
        .isTrue();
    assertThat(
            responses(doc, "/api/hubs/{hubId}/products/{productId}/stocks/add", "post")
                .path("409")
                .path("content")
                .path("application/json")
                .path("examples")
                .has("STOCK_QUANTITY_OVERFLOW"))
        .isTrue();
    assertThat(responses(doc, "/api/hubs/{hubId}/stocks", "get").has("404")).isFalse();
    assertThat(doc.path("paths").toString()).doesNotContain("/internal/");
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
