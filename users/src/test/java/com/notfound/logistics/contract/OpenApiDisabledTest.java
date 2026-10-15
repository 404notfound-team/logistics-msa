package com.notfound.logistics.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.notfound.logistics.users.UsersApplication;
import com.notfound.logistics.users.service.UserService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("test")
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
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OpenApiDisabledTest {
  @MockitoBean private UserService userService;

  @Autowired private MockMvc mvc;

  @ParameterizedTest
  @ValueSource(strings = {"/v3/api-docs", "/v3/api-docs.yaml", "/swagger-ui.html"})
  void documentationIsUnavailableOutsideLocalProfile(String path) throws Exception {
    mvc.perform(get(path)).andExpect(status().isNotFound());
  }
}
