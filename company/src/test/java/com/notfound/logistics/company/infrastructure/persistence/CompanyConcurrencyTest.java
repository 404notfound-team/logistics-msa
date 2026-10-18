package com.notfound.logistics.company.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.notfound.logistics.company.application.service.CompanyService;
import com.notfound.logistics.company.application.service.HubVerifier;
import com.notfound.logistics.company.application.service.Requester;
import com.notfound.logistics.company.domain.model.Company;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// 실제 PostgreSQL에서 같은 업체를 바꾸는 두 트랜잭션을 겹쳐 실행한다.
// 먼저 시작한 트랜잭션(A)은 업체를 잠근 뒤 커밋 직전 flush의 감사 작업자 조회에서 멈춘다. 그사이 실행한 B는 A가 끝날 때까지 기다려야 하고,
// A가 끝난 뒤 최신 행을 읽어야 한다. 잠금이 없으면 B가 먼저 커밋하고 A가 오래된 값으로 모든 칸을 덮어써 아래 검증이 실패한다.
@DataJpaTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true"
    })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
  CompanyRepositoryAdapter.class,
  CompanyService.class,
  CompanyConcurrencyTest.ConcurrencyTestConfig.class
})
@Testcontainers
// 각 서비스 호출이 스스로 커밋해야 다른 트랜잭션에서 보이므로 테스트 트랜잭션을 쓰지 않는다.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CompanyConcurrencyTest {
  private static final UUID HUB_ID = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");
  private static final Requester MASTER = new Requester(UUID.randomUUID(), true);
  private static final String SLOW_THREAD = "slow-tx";

  @Container
  static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:18-alpine");

  @Autowired private CompanyService companyService;
  @Autowired private PausingAuditor auditor;
  @Autowired private DataSource dataSource;

  private JdbcTemplate jdbc;

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
    registry.add("spring.datasource.username", DATABASE::getUsername);
    registry.add("spring.datasource.password", DATABASE::getPassword);
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    auditor.reset();
  }

  @Test
  void deletionDuringUpdateIsNotUndone() throws Exception {
    UUID companyId = createCompany();

    runOverlapping(
        () -> companyService.updateCompany(MASTER, companyId, "변경 업체", null, null, null),
        () -> companyService.deleteCompany(MASTER, companyId));

    Map<String, Object> row = companyRow(companyId);
    assertThat(row.get("name")).isEqualTo("변경 업체");
    assertThat(row.get("deleted_at")).isNotNull();
    assertThat(row.get("deleted_by")).isEqualTo("fast-tx");
  }

  @Test
  void concurrentUpdatesOfDifferentFieldsAreBothKept() throws Exception {
    UUID companyId = createCompany();

    runOverlapping(
        () -> companyService.updateCompany(MASTER, companyId, "변경 업체", null, null, null),
        () -> companyService.updateCompany(MASTER, companyId, null, null, "변경 주소", null));

    Map<String, Object> row = companyRow(companyId);
    assertThat(row.get("name")).isEqualTo("변경 업체");
    assertThat(row.get("address")).isEqualTo("변경 주소");
  }

  @Test
  void productAddedWhileDeletingCompanyIsDeletedToo() throws Exception {
    UUID companyId = createCompany();

    runOverlapping(
        () -> companyService.createProduct(MASTER, companyId, "추가 상품", null, 1000L),
        () -> companyService.deleteCompany(MASTER, companyId));

    assertThat(companyRow(companyId).get("deleted_at")).isNotNull();
    // 삭제된 업체 밑에 살아 있는 상품이 남지 않는다.
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM company.p_products WHERE company_id = ? AND deleted_at IS NULL",
                Long.class,
                companyId))
        .isZero();
  }

  @Test
  void productDeletionDuringUpdateIsNotUndone() throws Exception {
    UUID companyId = createCompany();
    UUID productId = createProduct(companyId);

    runOverlapping(
        () -> companyService.updateProduct(MASTER, companyId, productId, null, null, 2000L),
        () -> companyService.deleteProduct(MASTER, companyId, productId));

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT price, deleted_at FROM company.p_products WHERE product_id = ?", productId);
    assertThat(row.get("price")).isEqualTo(2000L);
    assertThat(row.get("deleted_at")).isNotNull();
  }

  // A를 느린 스레드에서 시작해 업체를 잠근 채 멈추게 하고, B가 A를 기다리는지 확인한 뒤 A를 마저 진행한다.
  private void runOverlapping(Runnable first, Runnable second) throws Exception {
    auditor.pauseFirstCallOn(SLOW_THREAD);
    CompletableFuture<Void> a = CompletableFuture.runAsync(first, namedThread(SLOW_THREAD));
    assertThat(auditor.awaitPaused()).as("A가 업체를 잠근 채 커밋 직전에 멈춤").isTrue();

    CompletableFuture<Void> b = CompletableFuture.runAsync(second, namedThread("fast-tx"));
    Thread.sleep(500);
    assertThat(b).as("B는 A의 잠금이 풀릴 때까지 기다린다").isNotDone();

    auditor.resume();
    a.get(10, TimeUnit.SECONDS);
    b.get(10, TimeUnit.SECONDS);
  }

  private UUID createCompany() {
    Company company =
        companyService.createCompany(MASTER, "동시성 업체", HUB_ID, "기존 주소", UUID.randomUUID());
    return company.getId();
  }

  private UUID createProduct(UUID companyId) {
    companyService.createProduct(MASTER, companyId, "기존 상품", null, 1000L);
    return jdbc.queryForObject(
        "SELECT product_id FROM company.p_products WHERE company_id = ?", UUID.class, companyId);
  }

  private Map<String, Object> companyRow(UUID companyId) {
    return jdbc.queryForMap(
        "SELECT name, address, deleted_at, deleted_by FROM company.p_companies WHERE company_id = ?",
        companyId);
  }

  private static Executor namedThread(String name) {
    return task -> new Thread(task, name).start();
  }

  /** 작업자로 스레드 이름을 쓰고, 지정한 스레드의 첫 호출에서 멈춘다. */
  static final class PausingAuditor implements AuditorAware<String> {
    private volatile String pausedThread;
    private volatile CountDownLatch paused = new CountDownLatch(1);
    private volatile CountDownLatch released = new CountDownLatch(1);

    void reset() {
      pausedThread = null;
      paused = new CountDownLatch(1);
      released = new CountDownLatch(1);
    }

    void pauseFirstCallOn(String threadName) {
      pausedThread = threadName;
    }

    boolean awaitPaused() throws InterruptedException {
      return paused.await(10, TimeUnit.SECONDS);
    }

    void resume() {
      released.countDown();
    }

    @Override
    public Optional<String> getCurrentAuditor() {
      String name = Thread.currentThread().getName();
      if (name.equals(pausedThread)) {
        pausedThread = null;
        paused.countDown();
        try {
          released.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      return Optional.of(name);
    }
  }

  @TestConfiguration
  @EnableJpaAuditing(auditorAwareRef = "pausingAuditor")
  static class ConcurrencyTestConfig {
    @Bean
    PausingAuditor pausingAuditor() {
      return new PausingAuditor();
    }

    // 허브 확인은 이 테스트의 관심사가 아니므로 항상 통과시킨다.
    @Bean
    HubVerifier hubVerifier() {
      return hubId -> {};
    }

    @Bean
    Clock clock() {
      return Clock.fixed(Instant.parse("2026-10-18T00:00:00Z"), java.time.ZoneOffset.UTC);
    }
  }
}
