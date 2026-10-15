package com.notfound.logistics.hub.application.service.hubroute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.notfound.logistics.hub.domain.model.hubroute.HubRoute;
import com.notfound.logistics.hub.domain.repository.hubroute.HubRouteRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class HubRouteQueryServiceTest {
  private final HubRouteRepository repository = mock(HubRouteRepository.class);
  private final HubRouteQueryService service = new HubRouteQueryService(repository);

  @ParameterizedTest
  @CsvSource({"false,false", "true,false", "false,true", "true,true"})
  void searchesAllFilterCombinations(boolean departure, boolean arrival) {
    UUID from = departure ? UUID.randomUUID() : null;
    UUID to = arrival ? UUID.randomUUID() : null;
    HubRoute route =
        HubRoute.create(UUID.randomUUID(), UUID.randomUUID(), 12, new BigDecimal("10.25"));
    var result = new PageImpl<>(List.of(route), PageRequest.of(0, 20), 1);
    if (departure && arrival) {
      when(repository.findByDepartureHubIdAndArrivalHubId(eq(from), eq(to), any()))
          .thenReturn(result);
    } else if (departure) {
      when(repository.findByDepartureHubId(eq(from), any())).thenReturn(result);
    } else if (arrival) {
      when(repository.findByArrivalHubId(eq(to), any())).thenReturn(result);
    } else {
      when(repository.findAll(any(Pageable.class))).thenReturn(result);
    }
    var page = service.getRoutes(from, to, 0, 20);
    assertThat(page.getTotalElements()).isEqualTo(1);
    assertThat(page.getContent().getFirst().id()).isEqualTo(route.getId());
    assertThat(page.getContent().getFirst().distanceKm()).isEqualByComparingTo("10.25");
    verifyNoMoreInteractionsAfterSearch(from, to);
  }

  private void verifyNoMoreInteractionsAfterSearch(UUID from, UUID to) {
    var pageable = ArgumentCaptor.forClass(Pageable.class);
    if (from != null && to != null) {
      verify(repository).findByDepartureHubIdAndArrivalHubId(eq(from), eq(to), pageable.capture());
    } else if (from != null) {
      verify(repository).findByDepartureHubId(eq(from), pageable.capture());
    } else if (to != null) {
      verify(repository).findByArrivalHubId(eq(to), pageable.capture());
    } else {
      verify(repository).findAll(pageable.capture());
    }
    assertThat(pageable.getValue().getSort())
        .containsExactly(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    verifyNoMoreInteractions(repository);
  }

  @ParameterizedTest
  @CsvSource({"false,false", "true,false", "false,true", "true,true"})
  void oversizedOffsetUsesOnlyMatchingCount(boolean departure, boolean arrival) {
    UUID from = departure ? UUID.randomUUID() : null;
    UUID to = arrival ? UUID.randomUUID() : null;
    if (departure && arrival) {
      when(repository.countByDepartureHubIdAndArrivalHubId(from, to)).thenReturn(1L);
    } else if (departure) {
      when(repository.countByDepartureHubId(from)).thenReturn(17L);
    } else if (arrival) {
      when(repository.countByArrivalHubId(to)).thenReturn(17L);
    } else {
      when(repository.count()).thenReturn(289L);
    }
    var result = service.getRoutes(from, to, Integer.MAX_VALUE, 100);
    assertThat(result.getContent()).isEmpty();
    assertThat(result.getNumber()).isEqualTo(Integer.MAX_VALUE);
    assertThat(result.getTotalElements())
        .isEqualTo(departure && arrival ? 1 : departure || arrival ? 17 : 289);
    if (departure && arrival) {
      verify(repository).countByDepartureHubIdAndArrivalHubId(from, to);
    } else if (departure) {
      verify(repository).countByDepartureHubId(from);
    } else if (arrival) {
      verify(repository).countByArrivalHubId(to);
    } else {
      verify(repository).count();
    }
    verifyNoMoreInteractions(repository);
  }

  @ParameterizedTest
  @CsvSource({"2147483646,1,false", "2147483647,1,true", "21474836,100,false", "21474837,100,true"})
  void handlesOffsetBoundaryWithoutIntegerOverflow(int page, int size, boolean countOnly) {
    if (countOnly) {
      when(repository.count()).thenReturn(289L);
    } else {
      when(repository.findAll(any(Pageable.class)))
          .thenAnswer(invocation -> new PageImpl<>(List.of(), invocation.getArgument(0), 289));
    }
    var result = service.getRoutes(null, null, page, size);
    assertThat(result.getContent()).isEmpty();
    assertThat(result.getTotalElements()).isEqualTo(289);
    assertThat(result.getPageable().getOffset()).isEqualTo((long) page * size);
    assertThat(result.getSort())
        .containsExactly(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    if (countOnly) {
      verify(repository).count();
    } else {
      verify(repository).findAll(any(Pageable.class));
    }
    verifyNoMoreInteractions(repository);
  }

  @Test
  void emptySearchPreservesZeroTotal() {
    when(repository.findAll(any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
    assertThat(service.getRoutes(null, null, 0, 20).getTotalElements()).isZero();
  }
}
