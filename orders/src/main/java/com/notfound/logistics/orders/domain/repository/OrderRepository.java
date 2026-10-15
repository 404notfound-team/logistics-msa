package com.notfound.logistics.orders.domain.repository;

import com.notfound.logistics.orders.domain.model.Order;
import com.notfound.logistics.orders.domain.model.OrderStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, UUID> {
  Optional<Order> findByCreatedByAndIdempotencyKey(String createdBy, UUID idempotencyKey);

  // Native lookup deliberately retains reserved keys, including soft-deleted orders.
  @Query(
      value = "select * from p_orders where created_by = :actor and idempotency_key = :key",
      nativeQuery = true)
  Optional<Order> findReservedKey(@Param("actor") String actor, @Param("key") UUID key);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select o from Order o where o.id = :id")
  Optional<Order> lockById(@Param("id") UUID id);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "update Order o set o.status = :next, o.updatedBy = :actor, o.updatedAt = :now "
          + "where o.id = :id and o.status = :expected and o.deletedAt is null")
  int resume(
      @Param("id") UUID id,
      @Param("expected") OrderStatus expected,
      @Param("next") OrderStatus next,
      @Param("actor") String actor,
      @Param("now") Instant now);

  List<Order> findTop50ByStatusInOrderByUpdatedAtAsc(Collection<OrderStatus> statuses);
}
