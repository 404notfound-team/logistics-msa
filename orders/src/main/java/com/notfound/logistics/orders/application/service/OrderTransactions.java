package com.notfound.logistics.orders.application.service;

import com.notfound.logistics.orders.domain.exception.OrderBusinessException;
import com.notfound.logistics.orders.domain.exception.OrderErrorCode;
import com.notfound.logistics.orders.domain.model.Order;
import com.notfound.logistics.orders.domain.model.OrderStatus;
import com.notfound.logistics.orders.domain.model.RecoveryPhase;
import com.notfound.logistics.orders.domain.repository.OrderRepository;
import com.notfound.logistics.orders.infrastructure.audit.OrdersAuditorAware;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderTransactions {
  private final OrderRepository repository;
  private final OrdersAuditorAware auditor;

  public record InconsistentRecovery(OrderStatus status, RecoveryPhase phase) {}

  @Transactional
  public Optional<InconsistentRecovery> blockInconsistentRecovery(UUID id) {
    // 후보 조회 이후 완료·삭제·수정됐을 수 있으므로 잠근 최신 행으로 다시 판단한다.
    var found = repository.lockById(id);
    if (found.isEmpty()) return Optional.empty();
    Order order = found.get();
    if (!order.isRecoveryPending()
        || order.isRecoveryBlocked()
        || order.hasConsistentRecoveryPhase()) return Optional.empty();
    var problem = new InconsistentRecovery(order.getStatus(), order.getRecoveryPhase());
    // 업무를 재개하지 않고 마지막 저장 주체로 차단 메타데이터만 기록한다.
    auditor.runAsPersistedUser(
        order.getUpdatedBy(),
        () -> {
          order.blockRecovery("INCONSISTENT_RECOVERY_PHASE");
          repository.flush();
        });
    return Optional.of(problem);
  }

  @Transactional
  public Order insert(Order order) {
    return repository.saveAndFlush(order);
  }

  @Transactional
  public Order change(UUID id, Consumer<Order> mutation) {
    Order order =
        repository
            .lockById(id)
            .orElseThrow(() -> new OrderBusinessException(OrderErrorCode.ORDER_NOT_FOUND));
    mutation.accept(order);
    repository.flush();
    return order;
  }

  @Transactional
  public boolean beginCancellation(UUID id, String actor) {
    return repository.beginCancellation(
            id,
            OrderStatus.CONFIRMED,
            OrderStatus.CANCELLING,
            RecoveryPhase.DELIVERY_CANCELLATION,
            actor,
            Instant.now())
        == 1;
  }

  @Transactional
  public boolean resume(UUID id, boolean cancelling, String actor) {
    return repository.resume(
            id,
            OrderStatus.RECOVERY_REQUIRED,
            cancelling ? OrderStatus.CANCELLING : OrderStatus.PROCESSING,
            actor,
            Instant.now())
        == 1;
  }
}
