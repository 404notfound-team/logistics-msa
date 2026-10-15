package com.notfound.logistics.orders.application.service;

import com.notfound.logistics.orders.domain.exception.OrderBusinessException;
import com.notfound.logistics.orders.domain.exception.OrderErrorCode;
import com.notfound.logistics.orders.domain.model.Order;
import com.notfound.logistics.orders.domain.model.OrderStatus;
import com.notfound.logistics.orders.domain.model.RecoveryAction;
import com.notfound.logistics.orders.domain.repository.OrderRepository;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderTransactions {
  private final OrderRepository repository;

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
  public boolean resume(UUID id, RecoveryAction action, String actor) {
    return repository.resume(
            id, OrderStatus.RECOVERY_REQUIRED, OrderStatus.PROCESSING, actor, Instant.now())
        == 1;
  }
}
