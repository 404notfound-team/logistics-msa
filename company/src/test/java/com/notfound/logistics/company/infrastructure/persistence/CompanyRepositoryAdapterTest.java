package com.notfound.logistics.company.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.notfound.logistics.company.domain.model.Company;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// 실제 PostgreSQL에서 저장 → 영속성 컨텍스트 초기화 → 재조회 경로를 확인한다.
// Mockito 단위 테스트로는 JPA가 조회 결과로 엔티티를 만드는 과정(기본 생성자 필요)이 검증되지 않는다.
@DataJpaTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=create-drop",
      // application.yml의 default_schema(company)를 테스트 DB에 함께 만든다.
      "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true"
    })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({CompanyRepositoryAdapter.class, CompanyRepositoryAdapterTest.AuditingTestConfig.class})
@Testcontainers
// 컨테이너가 내려가기 전에 Spring 컨텍스트를 먼저 닫아 종료 시 DB 연결 대기(30초)를 막는다.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CompanyRepositoryAdapterTest {
  private static final UUID HUB_ID = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");
  private static final UUID MANAGER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Container
  static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:18-alpine");

  @Autowired private CompanyRepositoryAdapter repository;
  @Autowired private TestEntityManager entityManager;

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
    registry.add("spring.datasource.username", DATABASE::getUsername);
    registry.add("spring.datasource.password", DATABASE::getPassword);
  }

  @Test
  void savedCompanyCanBeReloadedFromDatabase() {
    Company saved =
        repository.save(Company.of("테스트 물류업체", HUB_ID, "서울특별시 강남구 테헤란로 123", MANAGER_ID));

    // INSERT를 DB에 반영하고 1차 캐시를 비워, 다음 조회가 반드시 DB에서 엔티티를 새로 만들게 한다.
    entityManager.flush();
    entityManager.clear();

    Optional<Company> reloaded = repository.findById(saved.getId());

    assertThat(reloaded).isPresent();
    Company company = reloaded.get();
    assertThat(company).isNotSameAs(saved);
    assertThat(company.getName()).isEqualTo("테스트 물류업체");
    assertThat(company.getHubId()).isEqualTo(HUB_ID);
    assertThat(company.getAddress()).isEqualTo("서울특별시 강남구 테헤란로 123");
    assertThat(company.getManagerUserId()).isEqualTo(MANAGER_ID);
    assertThat(company.getCreatedBy()).isEqualTo("test-user");
    assertThat(company.getCreatedAt()).isNotNull();
  }

  // @DataJpaTest는 운영 감사 설정을 불러오지 않으므로 테스트용 작업자를 둔다.
  @TestConfiguration
  @EnableJpaAuditing
  static class AuditingTestConfig {
    @Bean
    AuditorAware<String> testAuditorAware() {
      return () -> Optional.of("test-user");
    }
  }
}
