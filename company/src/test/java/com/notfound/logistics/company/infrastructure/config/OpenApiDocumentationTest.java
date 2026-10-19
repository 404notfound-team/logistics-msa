package com.notfound.logistics.company.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notfound.logistics.company.application.service.CompanyService;
import com.notfound.logistics.company.presentation.controller.CompanyController;
import com.notfound.logistics.company.presentation.controller.ProductController;
import com.notfound.logistics.company.support.CompanySecurityTest;
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
    controllers = {CompanyController.class, ProductController.class},
    properties = "springdoc.api-docs.enabled=true")
@Import(OpenApiConfiguration.class)
@CompanySecurityTest
@ImportAutoConfiguration({
  SpringDocConfiguration.class,
  SpringDocConfigProperties.class,
  SpringDocSecurityConfiguration.class,
  SpringDocWebMvcConfiguration.class
})
class OpenApiDocumentationTest {
  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper mapper;
  @MockitoBean private CompanyService service;

  @Test
  void includesGatewayErrorsAndPreservesBusinessFailureExamples() throws Exception {
    var doc = document();
    assertErrorSchemas(doc, 10);
    var create = responses(doc, "/api/companies", "post");
    assertThat(create.has("201")).isTrue();
    assertThat(create.path("503").toString()).contains("SERVICE_UNAVAILABLE");
    assertThat(
            responses(doc, "/api/companies/{company_id}/products", "get")
                .path("200")
                .path("content")
                .elements()
                .next()
                .path("schema")
                .path("$ref")
                .asText())
        .contains("ApiResponse")
        .doesNotContain("ApiErrorResponse");
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
