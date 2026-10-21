package com.notfound.logistics.hub.application.service.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.notfound.logistics.common.api.ApiResponse;
import com.notfound.logistics.hub.application.dto.stock.StockResult;
import com.notfound.logistics.hub.domain.exception.hub.HubBusinessException;
import com.notfound.logistics.hub.domain.exception.stock.StockBusinessException;
import com.notfound.logistics.hub.domain.exception.stock.StockErrorCode;
import com.notfound.logistics.hub.domain.model.hub.Hub;
import com.notfound.logistics.hub.domain.model.stock.Stock;
import com.notfound.logistics.hub.domain.model.stock.StockDeduction;
import com.notfound.logistics.hub.domain.repository.hub.HubRepository;
import com.notfound.logistics.hub.domain.repository.stock.StockDeductionRepository;
import com.notfound.logistics.hub.domain.repository.stock.StockRepository;
import com.notfound.logistics.hub.domain.repository.stock.StockRestorationRepository;
import com.notfound.logistics.hub.infrastructure.client.CompanyProductClient;
import com.notfound.logistics.hub.infrastructure.client.CompanyProductClient.ProductResponse;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StockCommandServiceTest {
  private final StockRepository stocks = mock(StockRepository.class);
  private final HubRepository hubs = mock(HubRepository.class);
  private final StockRestorationRepository restorations = mock(StockRestorationRepository.class);
  private final StockDeductionRepository deductions = mock(StockDeductionRepository.class);
  private final CompanyProductClient products = mock(CompanyProductClient.class);
  private final StockCommandService service =
      new StockCommandService(
          stocks, hubs, products, new StockMovementTransactions(stocks, deductions, restorations));
  private final UUID hubId = UUID.randomUUID();
  private final UUID productId = UUID.randomUUID();

  @BeforeEach
  void saveOutcomes() {
    when(deductions.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
  }

  private UUID recordedOrder(Stock stock, long quantity) {
    UUID order = UUID.randomUUID();
    when(deductions.findById(order))
        .thenReturn(Optional.of(StockDeduction.completed(order, stock, quantity)));
    return order;
  }

  @Test
  void missingHubDoesNotTouchStock() {
    when(hubs.findById(hubId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.create(hubId, productId, 1))
        .isInstanceOf(HubBusinessException.class);
    verifyNoInteractions(stocks);
  }

  @Test
  void duplicateStockIsRejected() {
    when(hubs.findById(hubId)).thenReturn(Optional.of(mock(Hub.class)));
    when(stocks.existsByHubIdAndProductId(hubId, productId)).thenReturn(true);
    assertThatThrownBy(() -> service.create(hubId, productId, 1))
        .isInstanceOf(StockBusinessException.class)
        .hasMessageContaining("동일한 상품");
  }

  @Test
  void createsStockWithZeroInitialQuantity() {
    when(products.getProduct(productId))
        .thenReturn(
            new ApiResponse<>(new ProductResponse(productId, UUID.randomUUID(), "상품", 100L)));
    when(hubs.findById(hubId)).thenReturn(Optional.of(mock(Hub.class)));
    when(stocks.saveAndFlush(any(Stock.class))).thenAnswer(call -> call.getArgument(0));
    var result = service.create(hubId, productId, 0);
    assertThat(result.hubId()).isEqualTo(hubId);
    assertThat(result.productId()).isEqualTo(productId);
    assertThat(result.quantity()).isZero();
  }

  @Test
  void addsStockQuantity() {
    Stock stock = Stock.create(hubId, productId, 100);

    when(stocks.findLockedByHubIdAndProductId(hubId, productId)).thenReturn(Optional.of(stock));

    var result = service.add(hubId, productId, 10);

    assertThat(result.quantity()).isEqualTo(110);
    assertThat(stock.getQuantity()).isEqualTo(110);
  }

  /** 재고 추가 */
  @Test
  void missingStockCannotBeIncreased() {
    when(stocks.findLockedByHubIdAndProductId(hubId, productId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.add(hubId, productId, 10))
        .isInstanceOf(StockBusinessException.class)
        .extracting(e -> ((StockBusinessException) e).getErrorCode())
        .isEqualTo(StockErrorCode.STOCK_NOT_FOUND);
  }

  @Test
  void stockQuantityOverflowIsRejected() {
    Stock stock = Stock.create(hubId, productId, Long.MAX_VALUE);

    when(stocks.findLockedByHubIdAndProductId(hubId, productId)).thenReturn(Optional.of(stock));

    assertThatThrownBy(() -> service.add(hubId, productId, 1))
        .isInstanceOf(StockBusinessException.class)
        .extracting(e -> ((StockBusinessException) e).getErrorCode())
        .isEqualTo(StockErrorCode.STOCK_QUANTITY_OVERFLOW);
  }

  @Test
  void deductsStockQuantity() {
    Stock stock = Stock.create(hubId, productId, 100);

    when(stocks.findLockedByHubIdAndProductId(hubId, productId)).thenReturn(Optional.of(stock));

    var result = service.deduct(UUID.randomUUID(), hubId, productId, 30);

    assertThat(result.quantity()).isEqualTo(70);
    assertThat(stock.getQuantity()).isEqualTo(70);
  }

  /** 재고 차감 (internal) */
  @Test
  void insufficientStockCannotBeDeducted() {
    Stock stock = Stock.create(hubId, productId, 10);

    when(stocks.findLockedByHubIdAndProductId(hubId, productId)).thenReturn(Optional.of(stock));

    assertThatThrownBy(() -> service.deduct(UUID.randomUUID(), hubId, productId, 20))
        .isInstanceOf(StockBusinessException.class)
        .extracting(e -> ((StockBusinessException) e).getErrorCode())
        .isEqualTo(StockErrorCode.INSUFFICIENT_STOCK);

    assertThat(stock.getQuantity()).isEqualTo(10);
  }

  @Test
  void missingStockCannotBeDeducted() {
    when(stocks.findLockedByHubIdAndProductId(hubId, productId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.deduct(UUID.randomUUID(), hubId, productId, 10))
        .isInstanceOf(StockBusinessException.class)
        .extracting(e -> ((StockBusinessException) e).getErrorCode())
        .isEqualTo(StockErrorCode.STOCK_NOT_FOUND);
  }

  @Test
  void deductingExactAvailableStockLeavesZero() {
    Stock stock = Stock.create(hubId, productId, 10);

    when(stocks.findLockedByHubIdAndProductId(hubId, productId)).thenReturn(Optional.of(stock));

    var result = service.deduct(UUID.randomUUID(), hubId, productId, 10);

    assertThat(result.quantity()).isZero();
    assertThat(stock.getQuantity()).isZero();
  }

  @Test
  void zeroStockCannotBeDeducted() {
    Stock stock = Stock.create(hubId, productId, 0);

    when(stocks.findLockedByHubIdAndProductId(hubId, productId)).thenReturn(Optional.of(stock));

    assertThatThrownBy(() -> service.deduct(UUID.randomUUID(), hubId, productId, 1))
        .isInstanceOf(StockBusinessException.class)
        .extracting(e -> ((StockBusinessException) e).getErrorCode())
        .isEqualTo(StockErrorCode.INSUFFICIENT_STOCK);

    assertThat(stock.getQuantity()).isZero();
  }

  @Test
  void invalidDeductionQuantityDoesNotChangeStock() {
    Stock stock = Stock.create(hubId, productId, 10);

    when(stocks.findLockedByHubIdAndProductId(hubId, productId)).thenReturn(Optional.of(stock));

    for (long quantity : new long[] {0, -1, Long.MIN_VALUE}) {
      assertThatThrownBy(() -> service.deduct(UUID.randomUUID(), hubId, productId, quantity))
          .isInstanceOf(StockBusinessException.class)
          .extracting(e -> ((StockBusinessException) e).getErrorCode())
          .isEqualTo(StockErrorCode.INVALID_STOCK_QUANTITY);
    }

    assertThat(stock.getQuantity()).isEqualTo(10);
  }

  @Test
  void maximumLongQuantityCanBeDeductedExactly() {
    Stock stock = Stock.create(hubId, productId, Long.MAX_VALUE);

    when(stocks.findLockedByHubIdAndProductId(hubId, productId)).thenReturn(Optional.of(stock));

    var result = service.deduct(UUID.randomUUID(), hubId, productId, Long.MAX_VALUE);

    assertThat(result.quantity()).isZero();
    assertThat(stock.getQuantity()).isZero();
  }

  @Test
  void restoresStockQuantity() {
    Stock stock = Stock.create(hubId, productId, 70);

    when(stocks.findLockedByHubIdAndProductId(hubId, productId)).thenReturn(Optional.of(stock));

    StockResult result = service.restore(recordedOrder(stock, 30), hubId, productId, 30);

    assertThat(result.quantity()).isEqualTo(100);
    assertThat(stock.getQuantity()).isEqualTo(100);
  }

  @Test
  void missingStockCannotBeRestored() {
    when(stocks.findLockedByHubIdAndProductId(hubId, productId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.restore(
                    recordedOrder(Stock.create(hubId, productId, 0), 10), hubId, productId, 10))
        .isInstanceOf(StockBusinessException.class)
        .extracting(e -> ((StockBusinessException) e).getErrorCode())
        .isEqualTo(StockErrorCode.STOCK_NOT_FOUND);
  }

  @Test
  void invalidRestoreQuantityDoesNotChangeStock() {
    Stock stock = Stock.create(hubId, productId, 10);

    when(stocks.findLockedByHubIdAndProductId(hubId, productId)).thenReturn(Optional.of(stock));

    for (long quantity : new long[] {0, -1, Long.MIN_VALUE}) {
      assertThatThrownBy(() -> service.restore(UUID.randomUUID(), hubId, productId, quantity))
          .isInstanceOf(StockBusinessException.class)
          .extracting(e -> ((StockBusinessException) e).getErrorCode())
          .isEqualTo(StockErrorCode.INVALID_STOCK_QUANTITY);
    }

    assertThat(stock.getQuantity()).isEqualTo(10);
  }

  @Test
  void restoreOverflowDoesNotChangeStock() {
    Stock stock = Stock.create(hubId, productId, Long.MAX_VALUE);

    when(stocks.findLockedByHubIdAndProductId(hubId, productId)).thenReturn(Optional.of(stock));

    assertThatThrownBy(() -> service.restore(recordedOrder(stock, 1), hubId, productId, 1))
        .isInstanceOf(StockBusinessException.class)
        .extracting(e -> ((StockBusinessException) e).getErrorCode())
        .isEqualTo(StockErrorCode.STOCK_QUANTITY_OVERFLOW);

    assertThat(stock.getQuantity()).isEqualTo(Long.MAX_VALUE);
  }
}
