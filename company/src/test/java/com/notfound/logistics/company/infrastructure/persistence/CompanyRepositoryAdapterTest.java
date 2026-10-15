package com.notfound.logistics.company.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.notfound.logistics.company.domain.model.Company;
import java.time.Instant;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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
  private static final UUID OTHER_HUB_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
  private static final Pageable FIRST_PAGE = PageRequest.of(0, 20);

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

  @Test
  void searchByNameMatchesPartiallyIgnoringCase() {
    persist("Alpha Logistics", HUB_ID);
    persist("beta logistics", HUB_ID);
    persist("Gamma Trading", HUB_ID);

    Page<Company> result = repository.search("LOGIS", null, FIRST_PAGE);

    assertThat(result.getContent())
        .extracting(Company::getName)
        .containsExactlyInAnyOrder("Alpha Logistics", "beta logistics");
    assertThat(result.getTotalElements()).isEqualTo(2);
  }

  @Test
  void searchByHubIdFiltersOtherHubs() {
    persist("허브1 업체", HUB_ID);
    persist("허브2 업체", OTHER_HUB_ID);

    Page<Company> result = repository.search(null, OTHER_HUB_ID, FIRST_PAGE);

    assertThat(result.getContent()).extracting(Company::getName).containsExactly("허브2 업체");
  }

  @Test
  void searchTreatsLikeWildcardsAsPlainText() {
    persist("100% 물류", HUB_ID);
    persist("1000 물류", HUB_ID);

    // %를 와일드카드로 해석하면 두 업체가 모두 나온다.
    Page<Company> result = repository.search("100%", null, FIRST_PAGE);

    assertThat(result.getContent()).extracting(Company::getName).containsExactly("100% 물류");
  }

  @Test
  void deletedCompanyIsExcludedFromLookupAndSearch() {
    Company kept = persist("유지 업체", HUB_ID);
    Company deleted = persist("삭제 업체", HUB_ID);
    deleted.markDeleted("test-user", Instant.parse("2026-10-15T00:00:00Z"));
    entityManager.flush();
    entityManager.clear();

    assertThat(repository.findById(deleted.getId())).isEmpty();
    Page<Company> result = repository.search(null, null, FIRST_PAGE);
    assertThat(result.getContent()).extracting(Company::getId).containsExactly(kept.getId());
    // 목록과 전체 개수에 같은 삭제 제외 조건이 적용된다.
    assertThat(result.getTotalElements()).isEqualTo(1);
  }

  private Company persist(String name, UUID hubId) {
    Company company = repository.save(Company.of(name, hubId, "서울특별시 강남구 테헤란로 123", MANAGER_ID));
    entityManager.flush();
    return company;
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
