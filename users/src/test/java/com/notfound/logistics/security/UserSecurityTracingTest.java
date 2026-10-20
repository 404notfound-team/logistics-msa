package com.notfound.logistics.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.notfound.logistics.users.UsersApplication;
import com.notfound.logistics.users.application.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** tracing을 켠 상태에서 보안 필터의 401·403 응답에 요청의 trace_id가 담기는지 확인합니다. */
@ActiveProfiles("local")
@SpringBootTest(
    classes = UsersApplication.class,
    properties = {
      "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
      "spring.data.jpa.repositories.enabled=false",
      "users.audit.enabled=false",
      "users.jwt.secret=test-only-signing-key-for-users-module-tests-32bytes",
      "USERS_SERVICE_TOKEN=test-only-users-service-token",
      "GATEWAY_SERVICE_TOKEN=test-only-gateway-service-token",
      "users.internal-auth.orders-token=test-only-orders-service-token",
      "eureka.client.enabled=false",
      "management.zipkin.tracing.export.enabled=false"
    })
@AutoConfigureMockMvc
@AutoConfigureObservability(metrics = false)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UserSecurityTracingTest {
  private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
  private static final String TRACEPARENT = "00-" + TRACE_ID + "-00f067aa0ba902b7-01";
  private static final String AUTHORIZATION_PATH =
      "/internal/users/11111111-1111-1111-1111-111111111111/authorization";

  @MockitoBean private UserService userService;

  @Autowired private MockMvc mvc;

  @Test
  void unauthorizedResponsesCarryTraceId() throws Exception {
    for (String path : new String[] {AUTHORIZATION_PATH, "/api/users/me"}) {
      mvc.perform(get(path).header("traceparent", TRACEPARENT))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.trace_id").value(TRACE_ID));
    }
  }

  @Test
  void forbiddenResponseCarriesTraceId() throws Exception {
    mvc.perform(
            get(AUTHORIZATION_PATH)
                .header("traceparent", TRACEPARENT)
                .header("X-Service-Token", "test-only-orders-service-token"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.trace_id").value(TRACE_ID));
  }
}
