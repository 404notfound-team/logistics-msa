package com.notfound.logistics.company.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.notfound.logistics.company.infrastructure.security.CompanySecurityConfiguration;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;

/**
 * 컨트롤러 테스트에 실제 보안 설정을 적용한다. 모든 요청에 USER 역할의 서명된 JWT(공개 API용)와 orders의 서비스 인증 헤더(내부 API용)를 기본으로 붙인다.
 * 인증 자체의 거부 경로는 CompanySecurityIntegrationTest에서 확인한다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import({CompanySecurityConfiguration.class, CompanySecurityTest.DefaultUserToken.class})
@TestPropertySource(
    properties = {
      "company.jwt.secret=" + CompanySecurityTestTokens.SECRET,
      "company.security.internal.orders-token=" + CompanySecurityTestTokens.ORDERS_TOKEN,
      "company.security.internal.users-token=" + CompanySecurityTestTokens.USERS_TOKEN
    })
public @interface CompanySecurityTest {

  @TestConfiguration(proxyBeanMethods = false)
  class DefaultUserToken {
    @Bean
    MockMvcBuilderCustomizer defaultUserToken() {
      return builder ->
          builder.defaultRequest(
              get("/")
                  .header(HttpHeaders.AUTHORIZATION, CompanySecurityTestTokens.userBearer())
                  .header("X-Service-Token", CompanySecurityTestTokens.ORDERS_TOKEN)
                  .header("X-Actor-Username", CompanySecurityTestTokens.USERNAME));
    }
  }
}
