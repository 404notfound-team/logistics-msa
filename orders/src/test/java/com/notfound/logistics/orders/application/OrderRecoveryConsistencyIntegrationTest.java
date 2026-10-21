package com.notfound.logistics.orders.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verifyNoInteractions;

import com.notfound.logistics.orders.OrdersApplication;
import com.notfound.logistics.orders.application.service.ActiveOrderProcessing;
import com.notfound.logistics.orders.application.service.ActiveOrderProcessing.Operation;
import com.notfound.logistics.orders.application.service.OrderService;
import com.notfound.logistics.orders.application.service.OrderTransactions;
import com.notfound.logistics.orders.domain.model.Order;
import com.notfound.logistics.orders.domain.model.OrderStatus;
import com.notfound.logistics.orders.domain.model.RecoveryPhase;
import com.notfound.logistics.orders.domain.repository.OrderRepository;
import com.notfound.logistics.orders.infrastructure.audit.OrdersAuditorAware;
import com.notfound.logistics.orders.support.OrderClientFixture;
import com.notfound.logistics.orders.support.OrderClientTestConfiguration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
    classes = OrdersApplication.class,
    properties = {
      "eureka.client.enabled=false",
      "management.tracing.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.properties.hibernate.default_schema=public",
      "orders.recovery.enabled=false"
    })
@Testcontainers
@Import(OrderClientTestConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderRecoveryConsistencyIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:18-alpine");

  @Autowired OrderService service;
  @MockitoSpyBean OrderTransactions transactions;
  @Autowired OrdersAuditorAware auditor;
  @Autowired ActiveOrderProcessing active;
  @Autowired OrderClientFixture fixture;
  @Autowired JdbcTemplate jdbc;
  @MockitoSpyBean OrderRepository repository;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
    registry.add("spring.datasource.username", DATABASE::getUsername);
    registry.add("spring.datasource.password", DATABASE::getPassword);
    registry.add("orders.jwt.secret", () -> OrderClientFixture.SECRET);
    registry.add("orders.clients.service-token", () -> OrderClientFixture.SERVICE_TOKEN);
  }

  @BeforeEach
  void reset() {
    fixture.awaitIdle();
    fixture.control(Map.of("action", "reset"));
    jdbc.execute("TRUNCATE TABLE p_orders");
    SecurityContextHolder.clearContext();
    clearInvocations(fixture.companies, fixture.stocks, fixture.deliveries, fixture.notifications);
  }

  @ParameterizedTest
  @CsvSource(
      nullValues = "NULL",
      textBlock =
          """
          PROCESSING, NULL
          CANCELLING, NULL
          RECOVERY_REQUIRED, NULL
          PROCESSING, DELIVERY_CANCELLATION
          PROCESSING, STOCK_RESTORATION
          CANCELLING, STOCK_DEDUCTION
          CANCELLING, DELIVERY_CREATION
          CANCELLING, STOCK_COMPENSATION
          """)
  void quarantinesEveryInvalidPendingCombinationOnceWithoutRemoteCalls(
      OrderStatus status, RecoveryPhase phase, CapturedOutput output) {
    Order before = persisted(status, phase);
    UUID id = before.getId();
    service.recover();
    Order blocked = repository.findById(id).orElseThrow();
    assertThat(blocked.getStatus()).isEqualTo(OrderStatus.RECOVERY_REQUIRED);
    assertThat(blocked.isRecoveryBlocked()).isTrue();
    assertThat(blocked.getLastError()).isEqualTo("INCONSISTENT_RECOVERY_PHASE");
    assertThat(blocked)
        .usingRecursiveComparison()
        .ignoringFields("status", "lastError", "recoveryBlocked", "updatedAt")
        .isEqualTo(before);
    assertThat(blocked.getUpdatedBy()).isEqualTo("admin");
    assertThat(
            repository.findRecoveryCandidates(
                List.of(OrderStatus.RECOVERY_REQUIRED),
                org.springframework.data.domain.PageRequest.of(0, 50)))
        .isEmpty();
    service.recover();
    assertThat(repository.findById(id).orElseThrow()).usingRecursiveComparison().isEqualTo(blocked);
    verifyNoInteractions(
        fixture.companies, fixture.stocks, fixture.deliveries, fixture.notifications);
    assertThat(output.getOut())
        .contains(
            "orderId="
                + id
                + ", status="
                + status
                + ", phase="
                + phase
                + ", code=INCONSISTENT_RECOVERY_PHASE");
    assertThat(output.getOut().split("code=INCONSISTENT_RECOVERY_PHASE", -1)).hasSize(2);
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    assertThat(active.begin(id, Operation.RECOVER)).isNull();
    active.end(id);
  }

  @Test
  void anInvalidOrderDoesNotPreventTheNextValidOrderFromRecovering() {
    Order invalid = persisted(OrderStatus.PROCESSING, null);
    Order valid = persisted(OrderStatus.PROCESSING, RecoveryPhase.STOCK_DEDUCTION);
    doReturn(List.of(invalid, valid))
        .when(repository)
        .findRecoveryCandidates(anyCollection(), any());
    service.recover();
    assertThat(repository.findById(invalid.getId()).orElseThrow().isRecoveryBlocked()).isTrue();
    Order completed = repository.findById(valid.getId()).orElseThrow();
    assertThat(completed.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    assertThat(completed.getRecoveryPhase()).isNull();
    assertThat(completed.getUpdatedBy()).isEqualTo("alice");
    assertThat(fixture.state().at("/counts/stock.deduct").asInt()).isEqualTo(1);
    assertThat(fixture.state().at("/actors/stock.deduct").asText()).isEqualTo("alice");
    assertThat(fixture.state().at("/stocks/0/quantity").asLong()).isEqualTo(997);
  }

  @ParameterizedTest
  @ValueSource(strings = {"CONFIRMED", "CANCELLED", "FAILED", "deleted", "blocked"})
  void staleCandidatesDoNotChangeOrdersThatAlreadyLeftAutomaticRecovery(String state) {
    Order before = persisted(OrderStatus.PROCESSING, null);
    if (state.equals("deleted"))
      jdbc.update(
          "update p_orders set deleted_at = CURRENT_TIMESTAMP, deleted_by = 'admin' where id = ?",
          before.getId());
    else if (state.equals("blocked"))
      jdbc.update("update p_orders set recovery_blocked = true where id = ?", before.getId());
    else jdbc.update("update p_orders set status = ? where id = ?", state, before.getId());
    Order snapshot = repository.findIncludingDeleted(before.getId()).orElseThrow();
    doReturn(List.of(before)).when(repository).findRecoveryCandidates(anyCollection(), any());
    service.recover();
    assertThat(repository.findIncludingDeleted(before.getId()).orElseThrow())
        .usingRecursiveComparison()
        .isEqualTo(snapshot);
    verifyNoInteractions(
        fixture.companies, fixture.stocks, fixture.deliveries, fixture.notifications);
  }

  @ParameterizedTest
  @ValueSource(strings = {"completed", "deleted", "blocked", "repaired"})
  void checksTheLockedRowAgainBeforeQuarantining(String change, CapturedOutput output) {
    Order before = persisted(OrderStatus.PROCESSING, null);
    var changed = new AtomicBoolean();
    doAnswer(
            invocation -> {
              if (changed.compareAndSet(false, true)) {
                switch (change) {
                  case "completed" ->
                      jdbc.update(
                          "update p_orders set status = 'CONFIRMED' where id = ?", before.getId());
                  case "deleted" ->
                      jdbc.update(
                          "update p_orders set deleted_at = CURRENT_TIMESTAMP, deleted_by = 'admin' where id = ?",
                          before.getId());
                  case "blocked" ->
                      jdbc.update(
                          "update p_orders set recovery_blocked = true where id = ?",
                          before.getId());
                  case "repaired" ->
                      jdbc.update(
                          "update p_orders set recovery_phase = 'STOCK_DEDUCTION' where id = ?",
                          before.getId());
                  default -> throw new AssertionError(change);
                }
              }
              return invocation.callRealMethod();
            })
        .when(transactions)
        .blockInconsistentRecovery(before.getId());
    service.recover();
    Order after = repository.findIncludingDeleted(before.getId()).orElseThrow();
    assertThat(changed).isTrue();
    switch (change) {
      case "completed" -> assertThat(after.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
      case "deleted" -> assertThat(after.getDeletedAt()).isNotNull();
      case "blocked" -> assertThat(after.isRecoveryBlocked()).isTrue();
      case "repaired" ->
          assertThat(after.getRecoveryPhase()).isEqualTo(RecoveryPhase.STOCK_DEDUCTION);
      default -> throw new AssertionError(change);
    }
    assertThat(after.getLastError()).isEqualTo("PREVIOUS_FAILURE");
    assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
    assertThat(output.getOut()).doesNotContain("code=INCONSISTENT_RECOVERY_PHASE");
    assertThat(output.getOut()).doesNotContain("Order recovery interrupted");
    verifyNoInteractions(
        fixture.companies, fixture.stocks, fixture.deliveries, fixture.notifications);
    if (change.equals("repaired")) {
      service.recover();
      assertThat(repository.findById(before.getId()).orElseThrow().getStatus())
          .isEqualTo(OrderStatus.CONFIRMED);
    }
  }

  @Test
  void failedQuarantineRollsBackAndCanBeRetriedWithoutLeakingTheAuditContext(
      CapturedOutput output) {
    Order before = persisted(OrderStatus.RECOVERY_REQUIRED, null);
    jdbc.execute(
        """
        CREATE FUNCTION reject_recovery_quarantine() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN RAISE EXCEPTION 'injected write failure' USING ERRCODE = '23514'; END;
        $$
        """);
    jdbc.execute(
        """
        CREATE TRIGGER reject_recovery_quarantine BEFORE UPDATE ON p_orders
        FOR EACH ROW WHEN (NEW.recovery_blocked)
        EXECUTE FUNCTION reject_recovery_quarantine()
        """);
    try {
      service.recover();
    } finally {
      jdbc.execute("DROP TRIGGER reject_recovery_quarantine ON p_orders");
      jdbc.execute("DROP FUNCTION reject_recovery_quarantine()");
    }
    assertThat(repository.findById(before.getId()).orElseThrow())
        .usingRecursiveComparison()
        .isEqualTo(before);
    assertThat(output.getOut()).contains("Order recovery interrupted");
    assertThat(output.getOut()).doesNotContain("code=INCONSISTENT_RECOVERY_PHASE");
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    service.recover();
    assertThat(repository.findById(before.getId()).orElseThrow().isRecoveryBlocked()).isTrue();
    verifyNoInteractions(
        fixture.companies, fixture.stocks, fixture.deliveries, fixture.notifications);
  }

  private Order persisted(OrderStatus status, RecoveryPhase phase) {
    Order order =
        Order.create(
            UUID.fromString(OrderClientFixture.RECEIVER),
            UUID.fromString(OrderClientFixture.SUPPLIER),
            UUID.fromString(OrderClientFixture.PRODUCT),
            3,
            "상품",
            100L,
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "서울",
            "수령인",
            null,
            UUID.randomUUID());
    auditor.runAsPersistedUser("alice", () -> transactions.insert(order));
    // 일반 업무 메서드로 만들 수 없는 저장 상태를 재현한다.
    jdbc.update(
        "update p_orders set status = ?, recovery_phase = ?, recovery_attempts = 3, last_error = 'PREVIOUS_FAILURE', updated_by = 'admin' where id = ?",
        status.name(),
        phase == null ? null : phase.name(),
        order.getId());
    return repository.findById(order.getId()).orElseThrow();
  }
}
