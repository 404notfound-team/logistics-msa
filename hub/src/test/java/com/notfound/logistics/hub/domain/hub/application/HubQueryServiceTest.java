package com.notfound.logistics.hub.domain.hub.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.notfound.logistics.hub.domain.hub.application.dto.HubResult;
import com.notfound.logistics.hub.domain.hub.domain.Hub;
import com.notfound.logistics.hub.domain.hub.domain.HubRepository;
import com.notfound.logistics.hub.domain.hub.domain.exception.HubBusinessException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class HubQueryServiceTest {

  private HubRepository hubRepository;
  private HubQueryService hubQueryService;

  @BeforeEach
  void setUp() {
    hubRepository = mock(HubRepository.class);
    hubQueryService = new HubQueryService(hubRepository);
  }

  @Test
  void getHubsWithPagination() {
    Page<Hub> hubPage = new PageImpl<>(List.of(), PageRequest.of(0, 10), 17);

    when(hubRepository.findAll(any(Pageable.class))).thenReturn(hubPage);

    Page<HubResult> result = hubQueryService.getHubs(0, 10, "desc");

    assertEquals(17, result.getTotalElements());
    assertEquals(2, result.getTotalPages());
    assertEquals(0, result.getNumber());
    assertEquals(10, result.getSize());

    verify(hubRepository).findAll(any(Pageable.class));
  }

  @Test
  void getHubsWithOffsetExceedingIntegerMaxValue() {
    int page = 21474837;
    int size = 100;

    when(hubRepository.count()).thenReturn(17L);

    Page<HubResult> result = hubQueryService.getHubs(page, size, "desc");

    assertThat(result.getContent()).isEmpty();
    assertThat(result.getNumber()).isEqualTo(page);
    assertThat(result.getSize()).isEqualTo(size);
    assertThat(result.getTotalElements()).isEqualTo(17L);

    verify(hubRepository, never()).findAll(any(Pageable.class));
    verify(hubRepository).count();
  }

  @Test
  void getHubsWithOffsetAtIntegerMaxValue() {
    int page = Integer.MAX_VALUE;

    when(hubRepository.count()).thenReturn(17L);

    Page<HubResult> result = hubQueryService.getHubs(page, 1, "desc");

    assertThat(result.getContent()).isEmpty();
    assertThat(result.getTotalElements()).isEqualTo(17L);

    verify(hubRepository, never()).findAll(any(Pageable.class));
  }

  @Test
  void getHubsWithOffsetBelowIntegerMaxValue() {
    int page = Integer.MAX_VALUE - 1;

    when(hubRepository.findAll(any(Pageable.class))).thenReturn(Page.empty());

    Page<HubResult> result = hubQueryService.getHubs(page, 1, "desc");

    assertThat(result.getContent()).isEmpty();

    verify(hubRepository).findAll(any(Pageable.class));
    verify(hubRepository, never()).count();
  }

  @Test
  void getHubsWithSorting() {
    when(hubRepository.findAll(any(Pageable.class))).thenReturn(Page.empty());

    hubQueryService.getHubs(1, 5, "desc");

    ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);

    verify(hubRepository).findAll(captor.capture());

    Pageable pageable = captor.getValue();

    assertEquals(1, pageable.getPageNumber());
    assertEquals(5, pageable.getPageSize());

    Sort.Order createdAt = pageable.getSort().getOrderFor("createdAt");
    Sort.Order id = pageable.getSort().getOrderFor("id");

    assertEquals(Sort.Direction.DESC, createdAt.getDirection());
    assertEquals(Sort.Direction.DESC, id.getDirection());
  }

  @Test
  void getHubSuccess() {
    UUID hubId = UUID.randomUUID();
    Hub hub = mock(Hub.class);

    when(hub.getId()).thenReturn(hubId);
    when(hub.getName()).thenReturn("서울특별시 센터");
    when(hub.getAddress()).thenReturn("서울특별시 송파구 송파대로 55");
    when(hubRepository.findById(hubId)).thenReturn(Optional.of(hub));

    HubResult result = hubQueryService.getHub(hubId);

    assertEquals(hubId, result.id());
    assertEquals("서울특별시 센터", result.name());
    assertEquals("서울특별시 송파구 송파대로 55", result.address());

    verify(hubRepository).findById(hubId);
  }

  @Test
  void getHubNotFound() {
    UUID hubId = UUID.randomUUID();

    when(hubRepository.findById(hubId)).thenReturn(Optional.empty());

    assertThrows(HubBusinessException.class, () -> hubQueryService.getHub(hubId));

    verify(hubRepository).findById(hubId);
  }
}
