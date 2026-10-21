package com.notfound.logistics.hub.application.service.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.notfound.logistics.hub.domain.repository.stock.StockRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class StockQueryServiceTest {
  private final StockRepository stocks = mock(StockRepository.class);
  private final StockQueryService service = new StockQueryService(stocks);

  @ParameterizedTest
  @CsvSource({
    "2147483646,1,false",
    "2147483647,1,true",
    "1073741823,2,false",
    "1073741824,2,true",
    "21474836,100,false",
    "21474837,100,true",
    "2174222,20,false",
    "2174222,100,false",
    "2147483647,100,true"
  })
  void handlesOffsetBoundaryWithoutOverflowOrUnnecessaryJpaQuery(
      int page, int size, boolean countOnly) {
    if (countOnly) {
      when(stocks.count()).thenReturn(7L);
    } else {
      when(stocks.findAll(any(Pageable.class)))
          .thenAnswer(call -> new PageImpl<>(List.of(), call.getArgument(0), 7));
    }
    var result = service.getStocks(null, null, page, size);
    assertThat(result.getContent()).isEmpty();
    assertThat(result.getTotalElements()).isEqualTo(7);
    assertThat(result.getNumber()).isEqualTo(page);
    assertThat(result.getPageable().getOffset()).isEqualTo((long) page * size);
    assertThat(result.getSort())
        .containsExactly(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    if (countOnly) {
      verify(stocks).count();
    } else {
      verify(stocks).findAll(any(Pageable.class));
    }
    verifyNoMoreInteractions(stocks);
  }

  @ParameterizedTest
  @CsvSource({"false,false", "true,false", "false,true", "true,true"})
  void oversizedOffsetUsesOnlyCountForSameFilter(boolean byHub, boolean byProduct) {
    UUID hub = byHub ? UUID.randomUUID() : null;
    UUID product = byProduct ? UUID.randomUUID() : null;
    if (byHub && byProduct) {
      when(stocks.countByHubIdAndProductId(hub, product)).thenReturn(1L);
    } else if (byHub) {
      when(stocks.countByHubId(hub)).thenReturn(2L);
    } else if (byProduct) {
      when(stocks.countByProductId(product)).thenReturn(3L);
    } else {
      when(stocks.count()).thenReturn(4L);
    }
    var result = service.getStocks(hub, product, Integer.MAX_VALUE, 100);
    assertThat(result.getContent()).isEmpty();
    assertThat(result.getTotalElements())
        .isEqualTo(byHub && byProduct ? 1 : byHub ? 2 : byProduct ? 3 : 4);
    if (byHub && byProduct) {
      verify(stocks).countByHubIdAndProductId(hub, product);
    } else if (byHub) {
      verify(stocks).countByHubId(hub);
    } else if (byProduct) {
      verify(stocks).countByProductId(product);
    } else {
      verify(stocks).count();
    }
    verifyNoMoreInteractions(stocks);
  }

  @ParameterizedTest
  @CsvSource({"false,false", "true,false", "false,true", "true,true"})
  void normalOffsetsPreserveEmptyResultAndExactCount(boolean byHub, boolean byProduct) {
    UUID hub = byHub ? UUID.randomUUID() : null;
    UUID product = byProduct ? UUID.randomUUID() : null;
    org.mockito.stubbing.Answer<Object> emptyPage =
        call -> new PageImpl<>(List.of(), call.getArgument(call.getArguments().length - 1), 0);
    if (byHub && byProduct) {
      when(stocks.findByHubIdAndProductId(eq(hub), eq(product), any())).thenAnswer(emptyPage);
    } else if (byHub) {
      when(stocks.findByHubId(eq(hub), any())).thenAnswer(emptyPage);
    } else if (byProduct) {
      when(stocks.findByProductId(eq(product), any())).thenAnswer(emptyPage);
    } else {
      when(stocks.findAll(any(Pageable.class))).thenAnswer(emptyPage);
    }
    var result = service.getStocks(hub, product, 0, 20);
    assertThat(result.getContent()).isEmpty();
    assertThat(result.getTotalElements()).isZero();
    if (byHub && byProduct) {
      verify(stocks).findByHubIdAndProductId(eq(hub), eq(product), any());
    } else if (byHub) {
      verify(stocks).findByHubId(eq(hub), any());
    } else if (byProduct) {
      verify(stocks).findByProductId(eq(product), any());
    } else {
      verify(stocks).findAll(any(Pageable.class));
    }
    verifyNoMoreInteractions(stocks);
  }
}
