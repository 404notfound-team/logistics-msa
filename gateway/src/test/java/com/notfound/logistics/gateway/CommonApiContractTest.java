package com.notfound.logistics.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;

import com.notfound.logistics.common.api.ApiErrorResponse;
import com.notfound.logistics.common.api.ApiResponse;
import com.notfound.logistics.common.api.CommonErrorCode;
import com.notfound.logistics.common.api.FieldViolation;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.ClassUtils;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

@ActiveProfiles("test")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "JWT_SECRET=test-only-signing-key-for-gateway-module-tests-32bytes",
      "eureka.client.enabled=false",
      "management.tracing.enabled=false"
    })
@Import(CommonApiContractTest.ContractRoutes.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CommonApiContractTest {
  @LocalServerPort private int port;

  @Test
  void gatewayUsesTheSharedJsonContractAndRealHttpStatus() {
    var client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
    client
        .get()
        .uri("/__test/common/success")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data")
        .isEqualTo("fixture");
    client
        .get()
        .uri("/__test/common/error")
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("INVALID_REQUEST")
        .jsonPath("$.trace_id")
        .isEqualTo("test-trace")
        .jsonPath("$.field_errors[0].field")
        .isEqualTo("user_name")
        .jsonPath("$.data")
        .doesNotExist();
  }

  @Test
  void apiLibraryDoesNotBringMvcOrJpaIntoTheGateway() {
    var loader = getClass().getClassLoader();
    assertThat(ClassUtils.isPresent("org.springframework.web.servlet.DispatcherServlet", loader))
        .isFalse();
    assertThat(
            ClassUtils.isPresent("org.springframework.data.jpa.repository.JpaRepository", loader))
        .isFalse();
    assertThat(ClassUtils.isPresent("org.hibernate.Session", loader)).isFalse();
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class ContractRoutes {
    @Bean
    RouterFunction<ServerResponse> commonContractRoutes() {
      return RouterFunctions.route(
              GET("/__test/common/success"),
              request -> ServerResponse.ok().bodyValue(new ApiResponse<>("fixture")))
          .andRoute(
              GET("/__test/common/error"),
              request ->
                  ServerResponse.status(CommonErrorCode.INVALID_REQUEST.httpStatus())
                      .bodyValue(
                          ApiErrorResponse.validation(
                              "test-trace", List.of(new FieldViolation("user_name", "필수 값입니다.")))));
    }
  }
}
