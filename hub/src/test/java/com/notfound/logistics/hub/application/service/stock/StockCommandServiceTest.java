package com.notfound.logistics.hub.application.service.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.notfound.logistics.hub.domain.exception.hub.HubBusinessException;
import com.notfound.logistics.hub.domain.exception.stock.StockBusinessException;
import com.notfound.logistics.hub.domain.model.hub.Hub;
import com.notfound.logistics.hub.domain.model.stock.Stock;
import com.notfound.logistics.hub.domain.repository.hub.HubRepository;
import com.notfound.logistics.hub.domain.repository.stock.StockRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StockCommandServiceTest {
  private final StockRepository stocks = mock(StockRepository.class);
  private final HubRepository hubs = mock(HubRepository.class);
  private final StockCommandService service = new StockCommandService(stocks, hubs);
  private final UUID hubId = UUID.randomUUID();
  private final UUID productId = UUID.randomUUID();

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
    when(hubs.findById(hubId)).thenReturn(Optional.of(mock(Hub.class)));
    when(stocks.saveAndFlush(any(Stock.class))).thenAnswer(call -> call.getArgument(0));
    var result = service.create(hubId, productId, 0);
    assertThat(result.hubId()).isEqualTo(hubId);
    assertThat(result.productId()).isEqualTo(productId);
    assertThat(result.quantity()).isZero();
  }
}
