package com.notfound.logistics.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.notfound.logistics.users.UsersApplication;
import io.swagger.v3.oas.annotations.media.Schema;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@ActiveProfiles("local")
@SpringBootTest(
    classes = UsersApplication.class,
    properties = {
      "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
      "eureka.client.enabled=false",
      "management.tracing.enabled=false"
    })
@AutoConfigureMockMvc
@Import(OpenApiDocumentationTest.ContractController.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OpenApiDocumentationTest {
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
  }

  record PublicResponse(@Schema(description = "사용자 이름") String userName) {}
}
