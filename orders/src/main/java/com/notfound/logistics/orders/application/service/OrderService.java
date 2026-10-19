package com.notfound.logistics.orders.application.service;

import com.notfound.logistics.common.api.PageResponse;
import com.notfound.logistics.orders.application.dto.CreateOrderCommand;
import com.notfound.logistics.orders.application.dto.OrderOperationResult;
import com.notfound.logistics.orders.application.dto.OrderOperationResult.Outcome;
import com.notfound.logistics.orders.application.dto.OrderResult;
import com.notfound.logistics.orders.application.service.ActiveOrderProcessing.Operation;
import com.notfound.logistics.orders.domain.exception.OrderBusinessException;
import com.notfound.logistics.orders.domain.exception.OrderErrorCode;
import com.notfound.logistics.orders.domain.model.Order;
import com.notfound.logistics.orders.domain.model.OrderStatus;
import com.notfound.logistics.orders.domain.model.RecoveryAction;
import com.notfound.logistics.orders.domain.repository.OrderRepository;
import com.notfound.logistics.orders.infrastructure.audit.OrdersAuditorAware;
import com.notfound.logistics.orders.infrastructure.client.CompanyClient;
import com.notfound.logistics.orders.infrastructure.client.DeliveryClient;
import com.notfound.logistics.orders.infrastructure.client.OrderClientTypes.Deduction;
import com.notfound.logistics.orders.infrastructure.client.OrderClientTypes.Delivery;
import com.notfound.logistics.orders.infrastructure.client.OrderClientTypes.DeliveryRequest;
import com.notfound.logistics.orders.infrastructure.client.OrderClientTypes.Restoration;
import com.notfound.logistics.orders.infrastructure.client.OrderClientTypes.Stock;
import com.notfound.logistics.orders.infrastructure.client.RemoteFailure;
import com.notfound.logistics.orders.infrastructure.client.StockClient;
import com.notfound.logistics.orders.infrastructure.notification.SlackNotifier;
import feign.FeignException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrderService {
  private static final String STOCK_UNKNOWN = "STOCK_RESULT_UNKNOWN";
  private static final String DELIVERY_UNKNOWN = "DELIVERY_RESULT_UNKNOWN";
  private static final Set<String> DELIVERY_STATES =
      Set.of(
          "HUB_WAITING",
          "HUB_MOVING",
          "DESTINATION_HUB_ARRIVED",
          "COMPANY_MOVING",
          "DELIVERED",
          "CANCELLED");
  private final OrderRepository repository;
  private final OrderTransactions transactions;
  private final ActiveOrderProcessing active;
  private final OrdersAuditorAware auditor;
  private final CompanyClient companies;
  private final StockClient stocks;
  private final DeliveryClient deliveries;
  private final SlackNotifier notifications;

  public OrderOperationResult create(CreateOrderCommand command, String username) {
    var reserved = repository.findReservedKey(username, command.idempotencyKey());
    if (reserved.isPresent()) return replay(reserved.get(), command);
    Order order = newOrder(command);
    active.begin(order.getId(), Operation.CREATE);
    try {
      try {
        transactions.insert(order);
      } catch (DataIntegrityViolationException exception) {
        var winner = repository.findReservedKey(username, command.idempotencyKey());
        if (winner.isPresent()) return replay(winner.get(), command);
        throw exception;
      }
      return processCreate(order.getId());
    } finally {
      active.end(order.getId());
    }
  }

  private Order newOrder(CreateOrderCommand command) {
    var supplier =
        reference(
            () -> companies.company(command.supplierCompanyId()), OrderErrorCode.COMPANY_NOT_FOUND);
    var receiver =
        reference(
            () -> companies.company(command.receiverCompanyId()), OrderErrorCode.COMPANY_NOT_FOUND);
    var product =
        reference(() -> companies.product(command.productId()), OrderErrorCode.PRODUCT_NOT_FOUND);
    if (!Objects.equals(supplier.id(), command.supplierCompanyId())
        || !Objects.equals(receiver.id(), command.receiverCompanyId())
        || !Objects.equals(product.id(), command.productId())
        || supplier.hubId() == null
        || receiver.hubId() == null
        || product.companyId() == null
        || product.name() == null
        || product.name().isBlank()
        || product.name().length() > 100
        || product.price() == null
        || product.price().signum() < 0) throw unavailable();
    long unitPrice;
    try {
      unitPrice = product.price().longValueExact();
    } catch (ArithmeticException exception) {
      throw unavailable();
    }
    if (!product.companyId().equals(command.supplierCompanyId()))
      throw business(OrderErrorCode.PRODUCT_SUPPLIER_MISMATCH);
    return Order.create(
        command.receiverCompanyId(),
        command.supplierCompanyId(),
        command.productId(),
        command.quantity(),
        product.name(),
        unitPrice,
        supplier.hubId(),
        receiver.hubId(),
        command.deliveryAddress(),
        command.recipientName(),
        command.requestNotes(),
        command.idempotencyKey());
  }

  private <T> T reference(
      java.util.function.Supplier<com.notfound.logistics.common.api.ApiResponse<T>> lookup,
      OrderErrorCode notFound) {
    try {
      return lookup.get().data();
    } catch (RemoteFailure failure) {
      if (failure.status() == 404) throw business(notFound);
      throw unavailable();
    } catch (FeignException exception) {
      throw unavailable();
    }
  }

  private OrderOperationResult replay(Order order, CreateOrderCommand command) {
    if (order.getDeletedAt() != null
        || !order.matches(
            command.receiverCompanyId(),
            command.supplierCompanyId(),
            command.productId(),
            command.quantity(),
            command.deliveryAddress(),
            command.recipientName(),
            command.requestNotes())) throw business(OrderErrorCode.IDEMPOTENCY_KEY_REUSED);
    return operation(order, inProgress(order) ? Outcome.IN_PROGRESS : Outcome.RETURNED, null);
  }

  private OrderOperationResult processCreate(UUID id) {
    Order order = load(id);
    transactions.change(id, value -> value.recordAttempt(STOCK_UNKNOWN));
    try {
      var response =
          stocks.deduct(
              new Deduction(
                  id, order.getDepartureHubId(), order.getProductId(), order.getQuantity()));
      Stock stock = response == null ? null : response.data();
      if (stock == null
          || stock.id() == null
          || !Objects.equals(stock.hubId(), order.getDepartureHubId())
          || !Objects.equals(stock.productId(), order.getProductId())
          || stock.quantity() == null
          || stock.quantity() < 0) return fail(id, OrderErrorCode.UPSTREAM_UNAVAILABLE);
    } catch (RemoteFailure failure) {
      if (failure.status() == 409 && failure.code().equals("INSUFFICIENT_STOCK"))
        return fail(id, OrderErrorCode.INSUFFICIENT_STOCK);
      if (failure.status() == 404 && failure.code().equals("STOCK_NOT_FOUND"))
        return fail(id, OrderErrorCode.STOCK_NOT_FOUND);
      return fail(id, OrderErrorCode.UPSTREAM_UNAVAILABLE);
    } catch (FeignException exception) {
      // Without a stock operation record, an uncertain deduction must never be retried.
      return fail(id, OrderErrorCode.UPSTREAM_UNAVAILABLE);
    }
    transactions.change(id, value -> value.recordAttempt(DELIVERY_UNKNOWN));
    return processDeliveryCreation(id, false);
  }

  private OrderOperationResult processDeliveryCreation(UUID id, boolean uncertainDelivery) {
    Order order = load(id);
    try {
      Delivery delivery = delivery(id);
      if (delivery == null) {
        transactions.change(id, value -> value.recordAttempt(DELIVERY_UNKNOWN));
        try {
          delivery =
              deliveries
                  .create(
                      new DeliveryRequest(
                          id,
                          order.getDepartureHubId(),
                          order.getDestinationHubId(),
                          order.getDeliveryAddress(),
                          order.getRecipientName()))
                  .data();
        } catch (RemoteFailure failure) {
          // A later rejection/404 cannot prove that an earlier timed-out call did not commit.
          if (!uncertainDelivery && definiteDeliveryRejection(failure)) {
            return fail(id, OrderErrorCode.DELIVERY_CREATION_FAILED);
          }
          return recovery(id, DELIVERY_UNKNOWN);
        }
      }
      if (!validDelivery(order, delivery) || "CANCELLED".equals(delivery.status()))
        return recovery(id, "INCONSISTENT_RESULT");
      UUID deliveryId = delivery.deliveryId();
      transactions.change(id, value -> value.confirm(deliveryId));
      Order confirmed = load(id);
      notifications.confirmed(confirmed, delivery);
      return operation(confirmed, Outcome.CREATED, null);
    } catch (RemoteFailure | FeignException exception) {
      return recovery(id, "UPSTREAM_UNAVAILABLE");
    }
  }

  public OrderOperationResult cancel(UUID id, String username, boolean master) {
    Order order = accessible(id, username, master);
    if (order.getStatus() == OrderStatus.CANCELLED) return operation(order, Outcome.RETURNED, null);
    if (order.getStatus() == OrderStatus.FAILED)
      throw business(OrderErrorCode.ORDER_STATE_CONFLICT);
    if (inProgress(order) && order.getRecoveryAction() != RecoveryAction.CANCEL)
      throw business(OrderErrorCode.ORDER_STATE_CONFLICT);
    Operation running = active.begin(id, Operation.CANCEL);
    while (running == Operation.RECOVER) {
      if (active.whileRunning(
          id,
          Operation.RECOVER,
          () -> {
            if (load(id).getStatus() == OrderStatus.CONFIRMED)
              transactions.beginCancellation(id, username);
          })) break;
      running = active.begin(id, Operation.CANCEL);
    }
    if (running != null) {
      Order current = load(id);
      if (current.getStatus() == OrderStatus.CANCELLED)
        return operation(current, Outcome.RETURNED, null);
      if (running == Operation.CANCEL || current.getRecoveryAction() == RecoveryAction.CANCEL)
        return operation(current, Outcome.IN_PROGRESS, null);
      throw business(OrderErrorCode.ORDER_STATE_CONFLICT);
    }
    try {
      if (inProgress(order)) {
        Order current = load(id);
        if (current.getStatus() == OrderStatus.CANCELLED)
          return operation(current, Outcome.RETURNED, null);
        if (current.getRecoveryAction() == RecoveryAction.CANCEL)
          return operation(current, Outcome.IN_PROGRESS, null);
        throw business(OrderErrorCode.ORDER_STATE_CONFLICT);
      }
      if (!transactions.beginCancellation(id, username)) {
        Order current = load(id);
        if (current.getStatus() == OrderStatus.CANCELLED)
          return operation(current, Outcome.RETURNED, null);
        if (current.getRecoveryAction() == RecoveryAction.CANCEL)
          return operation(current, Outcome.IN_PROGRESS, null);
        throw business(OrderErrorCode.ORDER_STATE_CONFLICT);
      }
      return processCancel(id);
    } finally {
      active.end(id);
    }
  }

  private OrderOperationResult processCancel(UUID id) {
    Order order = load(id);
    if (order.awaitingStockRestoration()) return restoreStock(order);
    try {
      Delivery delivery = delivery(id);
      if (!validDelivery(order, delivery)) return recovery(id, "DELIVERY_RESULT_UNKNOWN");
      if ("HUB_WAITING".equals(delivery.status())) {
        try {
          delivery = deliveries.cancel(delivery.deliveryId()).data();
        } catch (RemoteFailure failure) {
          if (failure.status() != 409 || !failure.code().equals("DELIVERY_ALREADY_STARTED"))
            return recovery(id, "DELIVERY_CANCELLATION_UNKNOWN");
          delivery = delivery(id);
        }
      }
      if (!validDelivery(order, delivery)) return recovery(id, "DELIVERY_RESULT_UNKNOWN");
      if (!"CANCELLED".equals(delivery.status())) {
        if ("HUB_WAITING".equals(delivery.status()))
          return recovery(id, "DELIVERY_CANCELLATION_UNKNOWN");
        transactions.change(id, Order::rejectCancellation);
        return operation(load(id), Outcome.REJECTED, OrderErrorCode.ORDER_STATE_CONFLICT);
      }
      transactions.change(id, Order::awaitStockRestoration);
      return restoreStock(load(id));
    } catch (RemoteFailure | FeignException exception) {
      return recovery(id, "UPSTREAM_UNAVAILABLE");
    }
  }

  private OrderOperationResult restoreStock(Order order) {
    UUID id = order.getId();
    try {
      var response =
          stocks.restore(
              new Restoration(
                  id, order.getDepartureHubId(), order.getProductId(), order.getQuantity()));
      Stock stock = response == null ? null : response.data();
      if (stock == null
          || stock.id() == null
          || !Objects.equals(stock.hubId(), order.getDepartureHubId())
          || !Objects.equals(stock.productId(), order.getProductId())
          || stock.quantity() == null
          || stock.quantity() < 0) return recovery(id, "STOCK_RESTORATION_UNKNOWN");
    } catch (RemoteFailure | FeignException exception) {
      return recovery(id, "STOCK_RESTORATION_UNKNOWN");
    }
    // The receipt makes this safe even if this commit fails after Hub has committed.
    transactions.change(id, Order::cancel);
    return operation(load(id), Outcome.RETURNED, null);
  }

  public OrderResult get(UUID id, String username, boolean master) {
    return OrderResult.from(accessible(id, username, master));
  }

  public PageResponse<OrderResult> search(
      UUID productId,
      OrderStatus status,
      Instant from,
      Instant to,
      int page,
      int size,
      String sort,
      String username,
      boolean master) {
    if (page < 0 || size < 1 || size > 100 || (from != null && to != null && from.isAfter(to)))
      throw business(OrderErrorCode.INVALID_ORDER_INPUT);
    Specification<Order> specification =
        (root, query, builder) -> {
          var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
          if (!master) predicates.add(builder.equal(root.get("createdBy"), username));
          if (productId != null) predicates.add(builder.equal(root.get("productId"), productId));
          if (status != null) predicates.add(builder.equal(root.get("status"), status));
          if (from != null)
            predicates.add(builder.greaterThanOrEqualTo(root.get("createdAt"), from));
          if (to != null) predicates.add(builder.lessThanOrEqualTo(root.get("createdAt"), to));
          return builder.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    var requestedSort = sort(sort);
    long offset = (long) page * size;
    if (offset > Integer.MAX_VALUE) {
      long total = repository.count(specification);
      if (offset >= total) return new PageResponse<>(List.of(), page, size, total);
      throw business(OrderErrorCode.INVALID_ORDER_INPUT);
    }
    var results = repository.findAll(specification, PageRequest.of(page, size, requestedSort));
    return new PageResponse<>(
        results.getContent().stream().map(OrderResult::from).toList(),
        page,
        size,
        results.getTotalElements());
  }

  private Sort sort(String value) {
    String[] parts = value.split(",", -1);
    var fields =
        Map.of(
            "created_at",
            "createdAt",
            "order_id",
            "id",
            "status",
            "status",
            "quantity",
            "quantity");
    if (parts.length != 2
        || !fields.containsKey(parts[0])
        || !Set.of("asc", "desc").contains(parts[1]))
      throw business(OrderErrorCode.INVALID_ORDER_INPUT);
    var sort = Sort.by(Sort.Direction.fromString(parts[1]), fields.get(parts[0]));
    return parts[0].equals("order_id") ? sort : sort.and(Sort.by("id"));
  }

  public void delete(UUID id, String username, boolean master) {
    if (!master) throw business(OrderErrorCode.ORDER_FORBIDDEN);
    if (active.begin(id, Operation.DELETE) != null) {
      Order current =
          repository
              .findIncludingDeleted(id)
              .orElseThrow(() -> business(OrderErrorCode.ORDER_NOT_FOUND));
      if (current.getDeletedAt() != null) return;
      throw business(OrderErrorCode.ORDER_STATE_CONFLICT);
    }
    try {
      Order order =
          repository
              .findIncludingDeleted(id)
              .orElseThrow(() -> business(OrderErrorCode.ORDER_NOT_FOUND));
      if (order.getDeletedAt() != null) return;
      if (!Set.of(OrderStatus.CANCELLED, OrderStatus.FAILED, OrderStatus.CONFIRMED)
          .contains(order.getStatus())) throw business(OrderErrorCode.ORDER_STATE_CONFLICT);
      if (order.getDeliveryId() != null) {
        try {
          Delivery remote = delivery(id);
          if (order.getStatus() == OrderStatus.CONFIRMED
              && remote != null
              && (!validDelivery(order, remote) || !"DELIVERED".equals(remote.status())))
            throw business(OrderErrorCode.ORDER_STATE_CONFLICT);
          deliveries.delete(order.getDeliveryId());
        } catch (RemoteFailure failure) {
          if (failure.status() == 409) throw business(OrderErrorCode.ORDER_STATE_CONFLICT);
          throw unavailable();
        } catch (FeignException exception) {
          throw unavailable();
        }
      }
      transactions.change(id, entity -> entity.markDeleted(username, Instant.now()));
    } finally {
      active.end(id);
    }
  }

  public void recover() {
    for (Order candidate :
        repository.findTop50ByStatusInOrderByUpdatedAtAsc(
            List.of(
                OrderStatus.PROCESSING, OrderStatus.CANCELLING, OrderStatus.RECOVERY_REQUIRED))) {
      UUID id = candidate.getId();
      if (active.begin(id, Operation.RECOVER) != null) continue;
      try {
        Order current = load(id);
        RecoveryAction action = current.getRecoveryAction();
        if (action == null) continue;
        String username =
            action == RecoveryAction.CREATE ? current.getCreatedBy() : current.getUpdatedBy();
        auditor.runAsPersistedUser(
            username,
            () -> {
              if (current.getStatus() != OrderStatus.RECOVERY_REQUIRED)
                transactions.change(id, value -> value.requireRecovery("INTERRUPTED_PROCESSING"));
              if (transactions.resume(id, action, username)) {
                if (action == RecoveryAction.CREATE) {
                  if (DELIVERY_UNKNOWN.equals(current.getLastError()))
                    processDeliveryCreation(id, true);
                  else fail(id, OrderErrorCode.UPSTREAM_UNAVAILABLE);
                } else processCancel(id);
              }
            });
      } catch (RuntimeException exception) {
        // A database interruption leaves persisted intent for the next bounded pass.
        org.slf4j.LoggerFactory.getLogger(OrderService.class)
            .warn(
                "Order recovery interrupted; orderId={}, type={}",
                id,
                exception.getClass().getSimpleName());
      } finally {
        active.end(id);
      }
    }
  }

  private Order accessible(UUID id, String username, boolean master) {
    Order order = load(id);
    if (!master && !order.getCreatedBy().equals(username))
      throw business(OrderErrorCode.ORDER_FORBIDDEN);
    return order;
  }

  private Order load(UUID id) {
    return repository.findById(id).orElseThrow(() -> business(OrderErrorCode.ORDER_NOT_FOUND));
  }

  private Delivery delivery(UUID id) {
    try {
      return deliveries.byOrder(id).data();
    } catch (RemoteFailure failure) {
      if (failure.status() == 404) return null;
      throw failure;
    }
  }

  private boolean validDelivery(Order order, Delivery delivery) {
    return delivery != null
        && delivery.deliveryId() != null
        && Objects.equals(delivery.orderId(), order.getId())
        && DELIVERY_STATES.contains(delivery.status() == null ? "" : delivery.status())
        && (order.getDeliveryId() == null || order.getDeliveryId().equals(delivery.deliveryId()));
  }

  private boolean definiteDeliveryRejection(RemoteFailure failure) {
    return (failure.status() == 404
            && Set.of("HUB_ROUTE_NOT_FOUND", "HUB_NOT_FOUND", "ENTITY_NOT_FOUND")
                .contains(failure.code()))
        || (failure.status() == 400
            && Set.of("INVALID_REQUEST", "VALIDATION_FAILED").contains(failure.code()));
  }

  private OrderOperationResult recovery(UUID id, String code) {
    transactions.change(id, value -> value.requireRecovery(code));
    return operation(load(id), Outcome.IN_PROGRESS, null);
  }

  private OrderOperationResult fail(UUID id, OrderErrorCode code) {
    transactions.change(id, value -> value.fail(code.name()));
    return operation(load(id), Outcome.REJECTED, code);
  }

  private boolean inProgress(Order order) {
    return Set.of(OrderStatus.PROCESSING, OrderStatus.CANCELLING, OrderStatus.RECOVERY_REQUIRED)
        .contains(order.getStatus());
  }

  private OrderOperationResult operation(Order order, Outcome outcome, OrderErrorCode error) {
    return new OrderOperationResult(OrderResult.from(order), outcome, error);
  }

  private OrderBusinessException business(OrderErrorCode code) {
    return new OrderBusinessException(code);
  }

  private OrderBusinessException unavailable() {
    return business(OrderErrorCode.UPSTREAM_UNAVAILABLE);
  }
}
