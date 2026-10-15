package com.notfound.logistics.orders.application.service;

import com.notfound.logistics.orders.application.dto.CreateOrderCommand;
import com.notfound.logistics.orders.application.dto.OrderOperationResult;
import com.notfound.logistics.orders.application.dto.OrderOperationResult.Outcome;
import com.notfound.logistics.orders.application.dto.OrderResult;
import com.notfound.logistics.orders.domain.exception.OrderBusinessException;
import com.notfound.logistics.orders.domain.exception.OrderErrorCode;
import com.notfound.logistics.orders.domain.model.Order;
import com.notfound.logistics.orders.domain.model.OrderStatus;
import com.notfound.logistics.orders.domain.repository.OrderRepository;
import com.notfound.logistics.orders.infrastructure.client.CompanyClient;
import com.notfound.logistics.orders.infrastructure.client.DeliveryClient;
import com.notfound.logistics.orders.infrastructure.client.OrderClientTypes.Deduction;
import com.notfound.logistics.orders.infrastructure.client.OrderClientTypes.Delivery;
import com.notfound.logistics.orders.infrastructure.client.OrderClientTypes.DeliveryRequest;
import com.notfound.logistics.orders.infrastructure.client.OrderClientTypes.Restoration;
import com.notfound.logistics.orders.infrastructure.client.OrderClientTypes.StockOperation;
import com.notfound.logistics.orders.infrastructure.client.RemoteFailure;
import com.notfound.logistics.orders.infrastructure.client.StockClient;
import com.notfound.logistics.orders.infrastructure.notification.SlackNotifier;
import com.notfound.logistics.orders.infrastructure.security.OrderActors;
import feign.FeignException;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrderService {
  private static final String STOCK_UNKNOWN = "STOCK_RESULT_UNKNOWN";
  private static final String DELIVERY_UNKNOWN = "DELIVERY_RESULT_UNKNOWN";
  private static final String DELIVERY_FAILED = "DELIVERY_CREATION_FAILED";
  private static final Set<String> DELIVERY_STATES =
      Set.of(
          "WAITING",
          "HUB_TRANSIT",
          "DESTINATION_HUB_ARRIVED",
          "COMPANY_TRANSIT",
          "COMPLETED",
          "CANCELLED");
  private final OrderRepository repository;
  private final OrderTransactions transactions;
  private final ActiveOrderProcessing active;
  private final OrderActors actors;
  private final CompanyClient companies;
  private final StockClient stocks;
  private final DeliveryClient deliveries;
  private final SlackNotifier notifications;

  public OrderOperationResult create(CreateOrderCommand command) {
    var actor = actors.require();
    var reserved = repository.findReservedKey(actor.username(), command.idempotencyKey());
    if (reserved.isPresent()) return replay(reserved.get(), command);
    Order order = newOrder(command);
    active.begin(order.getId());
    try {
      try {
        transactions.insert(order);
      } catch (DataIntegrityViolationException exception) {
        var winner = repository.findReservedKey(actor.username(), command.idempotencyKey());
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
    try {
      if (product.price().setScale(2, RoundingMode.UNNECESSARY).precision() > 15)
        throw unavailable();
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
        product.price(),
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
    try {
      StockOperation stock = stockOperation(id);
      if (DELIVERY_FAILED.equals(order.getLastError())) return compensate(order, stock);
      if (stock != null && "RESTORED".equals(stock.state()))
        return recovery(id, "INCONSISTENT_RESULT");
      if (stock == null && DELIVERY_UNKNOWN.equals(order.getLastError()))
        return recovery(id, "INCONSISTENT_RESULT");
      if (stock == null) {
        boolean uncertain =
            STOCK_UNKNOWN.equals(order.getLastError())
                || DELIVERY_UNKNOWN.equals(order.getLastError());
        transactions.change(id, value -> value.recordAttempt(STOCK_UNKNOWN));
        try {
          stock =
              stocks
                  .deduct(
                      new Deduction(
                          id, order.getDepartureHubId(), order.getProductId(), order.getQuantity()))
                  .data();
        } catch (RemoteFailure failure) {
          if (!uncertain && failure.status() == 409 && failure.code().equals("INSUFFICIENT_STOCK"))
            return fail(id, OrderErrorCode.INSUFFICIENT_STOCK);
          if (!uncertain && failure.status() == 404 && failure.code().equals("STOCK_NOT_FOUND"))
            return fail(id, OrderErrorCode.STOCK_NOT_FOUND);
          return recovery(id, STOCK_UNKNOWN);
        }
      }
      if (!validStock(order, stock, "DEDUCTED")) return recovery(id, "INCONSISTENT_RESULT");
      boolean uncertainDelivery = DELIVERY_UNKNOWN.equals(order.getLastError());
      if (!uncertainDelivery) transactions.change(id, value -> value.recordAttempt(null));
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
            transactions.change(id, value -> value.recordAttempt(DELIVERY_FAILED));
            return compensate(load(id), stock);
          }
          return recovery(id, DELIVERY_UNKNOWN);
        }
      }
      if (!validDelivery(order, delivery) || "CANCELLED".equals(delivery.status()))
        return recovery(id, "INCONSISTENT_RESULT");
      UUID deliveryId = delivery.deliveryId();
      transactions.change(id, value -> value.confirm(deliveryId));
      Order confirmed = load(id);
      notifications.confirmed(confirmed.getId(), confirmed.getDeliveryId());
      return operation(confirmed, Outcome.CREATED, null);
    } catch (RemoteFailure | FeignException exception) {
      return recovery(id, "UPSTREAM_UNAVAILABLE");
    }
  }

  private OrderOperationResult compensate(Order order, StockOperation stock) {
    Delivery existing = delivery(order.getId());
    if (existing != null
        && (!validDelivery(order, existing) || !"CANCELLED".equals(existing.status())))
      return recovery(order.getId(), "INCONSISTENT_RESULT");
    if (stock == null) return recovery(order.getId(), "STOCK_RESULT_UNKNOWN");
    if (!validStock(order, stock, "RESTORED")) {
      if (!validStock(order, stock, "DEDUCTED"))
        return recovery(order.getId(), "INCONSISTENT_RESULT");
      stock = stocks.restore(new Restoration(order.getId())).data();
    }
    if (!validStock(order, stock, "RESTORED"))
      return recovery(order.getId(), "INCONSISTENT_RESULT");
    return fail(order.getId(), OrderErrorCode.DELIVERY_CREATION_FAILED);
  }

  public void recover() {
    for (Order candidate :
        repository.findTop50ByStatusInOrderByUpdatedAtAsc(
            List.of(OrderStatus.PROCESSING, OrderStatus.RECOVERY_REQUIRED))) {
      UUID id = candidate.getId();
      if (!active.begin(id)) continue;
      try {
        String username = candidate.getCreatedBy();
        actors.asPersistedActor(
            username,
            () -> {
              Order current = load(id);
              if (current.getRecoveryAction() == null) return null;
              if (current.getStatus() != OrderStatus.RECOVERY_REQUIRED)
                transactions.change(id, value -> value.requireRecovery("INTERRUPTED_PROCESSING"));
              if (transactions.resume(id, current.getRecoveryAction(), username)) {
                processCreate(id);
              }
              return null;
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

  private Order load(UUID id) {
    return repository.findById(id).orElseThrow(() -> business(OrderErrorCode.ORDER_NOT_FOUND));
  }

  private StockOperation stockOperation(UUID id) {
    try {
      return stocks.operation(id).data();
    } catch (RemoteFailure failure) {
      if (failure.status() == 404) return null;
      throw failure;
    }
  }

  private Delivery delivery(UUID id) {
    try {
      return deliveries.byOrder(id).data();
    } catch (RemoteFailure failure) {
      if (failure.status() == 404) return null;
      throw failure;
    }
  }

  private boolean validStock(Order order, StockOperation stock, String state) {
    return stock != null
        && Objects.equals(stock.orderId(), order.getId())
        && stock.stockId() != null
        && stock.quantity() == order.getQuantity()
        && state.equals(stock.state());
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
    return Set.of(OrderStatus.PROCESSING, OrderStatus.RECOVERY_REQUIRED)
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
