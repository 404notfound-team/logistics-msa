package com.notfound.logistics.delivery.presentation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.notfound.logistics.delivery.application.dto.DeliveryResult;
import com.notfound.logistics.delivery.application.dto.DeliveryRouteResult;
import com.notfound.logistics.delivery.application.service.DeliveryQueryService;
import com.notfound.logistics.delivery.domain.exception.DeliveryBusinessException;
import com.notfound.logistics.delivery.domain.exception.DeliveryErrorCode;
import com.notfound.logistics.delivery.domain.model.DeliveryRouteStatus;
import com.notfound.logistics.delivery.domain.model.DeliveryStatus;
import com.notfound.logistics.delivery.domain.repository.DeliverySearchCondition;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DeliveryController.class)
class DeliveryControllerTest {
  private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID DEPARTURE_HUB_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID DESTINATION_HUB_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000003");
  private static final UUID ASSIGNEE_ID = UUID.fromString("00000000-0000-0000-0000-000000000007");

  @Autowired private MockMvc mockMvc;
  @MockitoBean private DeliveryQueryService deliveryQueryService;

  @Test
  void defaultSearchUsesFirstPageAndNewestFirstWithPrimaryKeyTieBreaker() throws Exception {
    given(deliveryQueryService.search(any(), any())).willReturn(Page.empty());

    mockMvc
        .perform(get("/api/deliveries"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content").isEmpty())
        .andExpect(jsonPath("$.data.page").value(0))
        .andExpect(jsonPath("$.data.size").value(20))
        .andExpect(jsonPath("$.data.total_elements").value(0))
        .andExpect(jsonPath("$.data.total_pages").value(0));

    ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
    then(deliveryQueryService).should().search(any(), pageable.capture());
    assertThat(pageable.getValue().getPageNumber()).isZero();
    assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
    assertThat(pageable.getValue().getSort())
        .isEqualTo(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id")));
  }

  @Test
  void largestPageNumberIsAcceptedAndReturnsAnEmptyPage() throws Exception {
    given(deliveryQueryService.search(any(), any()))
        .willAnswer(
            invocation -> new PageImpl<DeliveryResult>(List.of(), invocation.getArgument(1), 3));

    mockMvc
        .perform(get("/api/deliveries").param("page", "2147483647").param("size", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content").isEmpty())
        .andExpect(jsonPath("$.data.page").value(Integer.MAX_VALUE))
        .andExpect(jsonPath("$.data.size").value(20))
        .andExpect(jsonPath("$.data.total_elements").value(3))
        .andExpect(jsonPath("$.data.total_pages").value(1));
  }

  @Test
  void snakeCaseFiltersAreMappedToTheSearchConditionAndResultsAreSnakeCase() throws Exception {
    DeliveryResult result =
        new DeliveryResult(
            UUID.fromString("00000000-0000-0000-0000-0000000000aa"),
            ORDER_ID,
            DeliveryStatus.HUB_MOVING,
            DEPARTURE_HUB_ID,
            DESTINATION_HUB_ID,
            "서울특별시 송파구 송파대로 55",
            "홍길동",
            ASSIGNEE_ID,
            List.of(
                new DeliveryRouteResult(
                    UUID.fromString("00000000-0000-0000-0000-0000000000c1"),
                    1,
                    DEPARTURE_HUB_ID,
                    DESTINATION_HUB_ID,
                    new BigDecimal("150.5"),
                    null,
                    7200,
                    null,
                    DeliveryRouteStatus.IN_TRANSIT,
                    ASSIGNEE_ID)),
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-01T01:00:00Z"));
    given(deliveryQueryService.search(any(), any()))
        .willReturn(new PageImpl<>(List.of(result), Pageable.ofSize(10).withPage(1), 11));

    mockMvc
        .perform(
            get("/api/deliveries")
                .param("order_id", ORDER_ID.toString())
                .param("status", "HUB_MOVING")
                .param("departure_hub_id", DEPARTURE_HUB_ID.toString())
                .param("destination_hub_id", DESTINATION_HUB_ID.toString())
                .param("address", " 송파 ")
                .param("recipient_name", "홍")
                .param("assignee_id", ASSIGNEE_ID.toString())
                .param("page", "1")
                .param("size", "10")
                .param("sort", "updated_at,asc"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].delivery_id").value(result.deliveryId().toString()))
        .andExpect(jsonPath("$.data.content[0].order_id").value(ORDER_ID.toString()))
        .andExpect(jsonPath("$.data.content[0].status").value("HUB_MOVING"))
        .andExpect(
            jsonPath("$.data.content[0].departure_hub_id").value(DEPARTURE_HUB_ID.toString()))
        .andExpect(
            jsonPath("$.data.content[0].destination_hub_id").value(DESTINATION_HUB_ID.toString()))
        .andExpect(jsonPath("$.data.content[0].address").value("서울특별시 송파구 송파대로 55"))
        .andExpect(jsonPath("$.data.content[0].recipient_name").value("홍길동"))
        .andExpect(jsonPath("$.data.content[0].assignee_id").value(ASSIGNEE_ID.toString()))
        .andExpect(jsonPath("$.data.content[0].created_at").value("2026-01-01T00:00:00Z"))
        .andExpect(jsonPath("$.data.content[0].routes.length()").value(1))
        .andExpect(
            jsonPath("$.data.content[0].routes[0].delivery_route_id")
                .value("00000000-0000-0000-0000-0000000000c1"))
        .andExpect(jsonPath("$.data.content[0].routes[0].sequence").value(1))
        .andExpect(jsonPath("$.data.content[0].routes[0].status").value("IN_TRANSIT"))
        .andExpect(jsonPath("$.data.page").value(1))
        .andExpect(jsonPath("$.data.total_elements").value(11))
        .andExpect(jsonPath("$.data.total_pages").value(2));

    ArgumentCaptor<DeliverySearchCondition> condition =
        ArgumentCaptor.forClass(DeliverySearchCondition.class);
    ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
    then(deliveryQueryService).should().search(condition.capture(), pageable.capture());
    assertThat(condition.getValue())
        .isEqualTo(
            new DeliverySearchCondition(
                ORDER_ID,
                DeliveryStatus.HUB_MOVING,
                DEPARTURE_HUB_ID,
                DESTINATION_HUB_ID,
                "송파",
                "홍",
                ASSIGNEE_ID));
    assertThat(pageable.getValue().getSort())
        .isEqualTo(Sort.by(Sort.Order.asc("updatedAt"), Sort.Order.asc("id")));
  }

  @Test
  void unsupportedMethodKeepsTheAllowHeaderInTheCommonErrorFormat() throws Exception {
    mockMvc
        .perform(post("/api/deliveries"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(header().string(HttpHeaders.ALLOW, "GET"))
        .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
        .andExpect(jsonPath("$.field_errors").isEmpty());
    then(deliveryQueryService).should(never()).search(any(), any());
  }

  @Test
  void searchDetailReturnsTheDeliveryWithItsRoutesInSnakeCase() throws Exception {
    UUID deliveryId = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    UUID relayHubId = UUID.fromString("00000000-0000-0000-0000-000000000004");
    UUID firstRouteId = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    UUID secondRouteId = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    given(deliveryQueryService.searchDetail(deliveryId))
        .willReturn(
            new DeliveryResult(
                deliveryId,
                ORDER_ID,
                DeliveryStatus.HUB_MOVING,
                DEPARTURE_HUB_ID,
                DESTINATION_HUB_ID,
                "서울특별시 송파구 송파대로 55",
                "홍길동",
                null,
                List.of(
                    new DeliveryRouteResult(
                        firstRouteId,
                        1,
                        DEPARTURE_HUB_ID,
                        relayHubId,
                        new BigDecimal("150.5"),
                        new BigDecimal("148.125"),
                        7200,
                        7000L,
                        DeliveryRouteStatus.ARRIVED,
                        ASSIGNEE_ID),
                    new DeliveryRouteResult(
                        secondRouteId,
                        2,
                        relayHubId,
                        DESTINATION_HUB_ID,
                        new BigDecimal("200"),
                        null,
                        9000,
                        null,
                        DeliveryRouteStatus.IN_TRANSIT,
                        null)),
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-02T00:00:00Z")));

    mockMvc
        .perform(get("/api/deliveries/{delivery_id}", deliveryId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.delivery_id").value(deliveryId.toString()))
        .andExpect(jsonPath("$.data.order_id").value(ORDER_ID.toString()))
        .andExpect(jsonPath("$.data.status").value("HUB_MOVING"))
        .andExpect(jsonPath("$.data.departure_hub_id").value(DEPARTURE_HUB_ID.toString()))
        .andExpect(jsonPath("$.data.destination_hub_id").value(DESTINATION_HUB_ID.toString()))
        .andExpect(jsonPath("$.data.address").value("서울특별시 송파구 송파대로 55"))
        .andExpect(jsonPath("$.data.recipient_name").value("홍길동"))
        .andExpect(jsonPath("$.data.assignee_id").isEmpty())
        .andExpect(jsonPath("$.data.created_at").value("2026-01-01T00:00:00Z"))
        .andExpect(jsonPath("$.data.updated_at").value("2026-01-02T00:00:00Z"))
        .andExpect(jsonPath("$.data.routes.length()").value(2))
        .andExpect(jsonPath("$.data.routes[0].delivery_route_id").value(firstRouteId.toString()))
        .andExpect(jsonPath("$.data.routes[0].sequence").value(1))
        .andExpect(jsonPath("$.data.routes[0].departure_hub_id").value(DEPARTURE_HUB_ID.toString()))
        .andExpect(jsonPath("$.data.routes[0].arrival_hub_id").value(relayHubId.toString()))
        .andExpect(jsonPath("$.data.routes[0].expected_distance_km").value(150.5))
        .andExpect(jsonPath("$.data.routes[0].actual_distance_km").value(148.125))
        .andExpect(jsonPath("$.data.routes[0].expected_duration_seconds").value(7200))
        .andExpect(jsonPath("$.data.routes[0].actual_duration_seconds").value(7000))
        .andExpect(jsonPath("$.data.routes[0].status").value("ARRIVED"))
        .andExpect(jsonPath("$.data.routes[0].assignee_id").value(ASSIGNEE_ID.toString()))
        .andExpect(jsonPath("$.data.routes[1].delivery_route_id").value(secondRouteId.toString()))
        .andExpect(jsonPath("$.data.routes[1].status").value("IN_TRANSIT"))
        .andExpect(jsonPath("$.data.routes[1].actual_distance_km").isEmpty())
        .andExpect(jsonPath("$.data.routes[1].assignee_id").isEmpty());
  }

  @Test
  void unknownDeliveryIsNotFoundInTheCommonErrorFormat() throws Exception {
    UUID deliveryId = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    given(deliveryQueryService.searchDetail(deliveryId))
        .willThrow(new DeliveryBusinessException(DeliveryErrorCode.DELIVERY_NOT_FOUND));

    mockMvc
        .perform(get("/api/deliveries/{delivery_id}", deliveryId))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("DELIVERY_NOT_FOUND"))
        .andExpect(jsonPath("$.message").value("배송을 찾을 수 없습니다."))
        .andExpect(jsonPath("$.field_errors").isEmpty());
  }

  @Test
  void malformedDeliveryIdIsRejectedWithTheCommonErrorFormat() throws Exception {
    mockMvc
        .perform(get("/api/deliveries/{delivery_id}", "not-a-uuid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.field_errors[0].field").value("delivery_id"))
        .andExpect(jsonPath("$.field_errors.length()").value(1));
    then(deliveryQueryService).should(never()).searchDetail(any());
  }

  @ParameterizedTest
  @CsvSource({
    "size, 0",
    "size, 101",
    "page, -1",
    "order_id, not-a-uuid",
    "status, UNKNOWN",
    "assignee_id, abc",
    "assignee_id, 7",
    "sort, address",
    "sort, 'created_at,sideways'",
    "sort, 'created_at,desc,id'"
  })
  void invalidParametersAreRejectedWithTheCommonErrorFormat(String field, String value)
      throws Exception {
    mockMvc
        .perform(get("/api/deliveries").param(field, value))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.field_errors[0].field").value(field))
        .andExpect(jsonPath("$.field_errors.length()").value(1));
    then(deliveryQueryService).should(never()).search(any(), any());
  }
}
