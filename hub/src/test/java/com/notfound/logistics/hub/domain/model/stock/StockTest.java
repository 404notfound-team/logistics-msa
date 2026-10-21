package com.notfound.logistics.hub.domain.model.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.notfound.logistics.hub.domain.exception.stock.StockBusinessException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StockTest {
  @ParameterizedTest
  @ValueSource(longs = {-1, Long.MIN_VALUE})
  void rejectsNegativeInitialQuantity(long quantity) {
    assertThatThrownBy(() -> Stock.create(UUID.randomUUID(), UUID.randomUUID(), quantity))
        .isInstanceOf(StockBusinessException.class);
  }

  @Test
  void deductsToZeroAndRestoresOriginalQuantity() {
    var stock = Stock.create(UUID.randomUUID(), UUID.randomUUID(), 10);
    stock.deduct(10);
    assertThat(stock.getQuantity()).isZero();
    stock.restore(10);
    assertThat(stock.getQuantity()).isEqualTo(10);
  }

  @Test
  void overflowAndInsufficientStockLeaveQuantityUnchanged() {
    var stock = Stock.create(UUID.randomUUID(), UUID.randomUUID(), Long.MAX_VALUE);
    assertThatThrownBy(() -> stock.add(1)).isInstanceOf(StockBusinessException.class);
    assertThat(stock.getQuantity()).isEqualTo(Long.MAX_VALUE);
    stock.deduct(Long.MAX_VALUE);
    assertThatThrownBy(() -> stock.deduct(1)).isInstanceOf(StockBusinessException.class);
    assertThat(stock.getQuantity()).isZero();
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1, Long.MIN_VALUE})
  void allQuantityChangesRequirePositiveAmount(long quantity) {
    var stock = Stock.create(UUID.randomUUID(), UUID.randomUUID(), 10);
    assertThatThrownBy(() -> stock.add(quantity)).isInstanceOf(StockBusinessException.class);
    assertThatThrownBy(() -> stock.deduct(quantity)).isInstanceOf(StockBusinessException.class);
    assertThatThrownBy(() -> stock.restore(quantity)).isInstanceOf(StockBusinessException.class);
    assertThat(stock.getQuantity()).isEqualTo(10);
  }
}
