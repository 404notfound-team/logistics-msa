package com.notfound.logistics.delivery.infrastructure.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
}
