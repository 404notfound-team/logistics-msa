package com.notfound.logistics.orders.domain.repository;

import com.notfound.logistics.orders.domain.model.Order;
import com.notfound.logistics.orders.domain.model.OrderStatus;
import com.notfound.logistics.orders.domain.model.RecoveryPhase;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository
    extends JpaRepository<Order, UUID>, JpaSpecificationExecutor<Order> {
  // Native queries deliberately retain reserved keys and permit idempotent deletion.
  @Query(
      value = "select * from p_orders where created_by = :actor and idempotency_key = :key",
      nativeQuery = true)
  Optional<Order> findReservedKey(@Param("actor") String actor, @Param("key") UUID key);

  @Query(value = "select * from p_orders where id = :id", nativeQuery = true)
  Optional<Order> findIncludingDeleted(@Param("id") UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select o from Order o where o.id = :id")
  Optional<Order> lockById(@Param("id") UUID id);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "update Order o set o.status = :next, o.lastError = null, o.recoveryPhase = :phase, o.recoveryAttempts = 0, o.recoveryBlocked = false, "
          + "o.updatedBy = :actor, o.updatedAt = :now where o.id = :id and o.status = :expected and o.deletedAt is null")
  int beginCancellation(
      @Param("id") UUID id,
      @Param("expected") OrderStatus expected,
      @Param("next") OrderStatus next,
      @Param("phase") RecoveryPhase phase,
      @Param("actor") String actor,
      @Param("now") Instant now);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "update Order o set o.status = :next, o.updatedBy = :actor, o.updatedAt = :now "
          + "where o.id = :id and o.status = :expected and o.deletedAt is null and o.recoveryBlocked = false")
  int resume(
      @Param("id") UUID id,
      @Param("expected") OrderStatus expected,
      @Param("next") OrderStatus next,
      @Param("actor") String actor,
      @Param("now") Instant now);

  // 고정 간격으로 오래된 작업부터 재개하며 자동 차단 건은 제외한다.
  @Query(
      "select o from Order o where o.status in :statuses and o.recoveryBlocked = false order by o.updatedAt, o.id")
  List<Order> findRecoveryCandidates(
      @Param("statuses") Collection<OrderStatus> statuses, Pageable pageable);
}
