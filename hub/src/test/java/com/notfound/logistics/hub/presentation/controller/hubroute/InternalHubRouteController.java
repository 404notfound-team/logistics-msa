package com.notfound.logistics.hub.presentation.controller.hubroute;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.notfound.logistics.hub.application.dto.hubroute.HubRouteResult;
import com.notfound.logistics.hub.application.service.hubroute.HubRouteQueryService;
import com.notfound.logistics.hub.domain.exception.hubroute.HubRouteBusinessException;
import com.notfound.logistics.hub.domain.exception.hubroute.HubRouteErrorCode;
import com.notfound.logistics.hub.presentation.exception.GlobalExceptionHandler;
import com.notfound.logistics.hub.presentation.exception.hubroute.HubRouteExceptionHandler;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(InternalHubRouteController.class)
@Import({GlobalExceptionHandler.class, HubRouteExceptionHandler.class})
class InternalHubRouteControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private HubRouteQueryService hubRouteQueryService;

  @Test
  void getInternalHubRouteSuccess() throws Exception {
    // given
    UUID routeId = UUID.randomUUID();
    UUID departureHubId = UUID.randomUUID();
    UUID arrivalHubId = UUID.randomUUID();

    HubRouteResult result =
        new HubRouteResult(routeId, departureHubId, arrivalHubId, 120, new BigDecimal("150.50"));

    when(hubRouteQueryService.getRoute(departureHubId, arrivalHubId)).thenReturn(result);

    // when & then
    mockMvc
        .perform(
            get("/internal/hub-routes")
                .param("departure_hub_id", departureHubId.toString())
                .param("arrival_hub_id", arrivalHubId.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(routeId.toString()))
        .andExpect(jsonPath("$.data.departure_hub_id").value(departureHubId.toString()))
        .andExpect(jsonPath("$.data.arrival_hub_id").value(arrivalHubId.toString()))
        .andExpect(jsonPath("$.data.duration_minutes").value(120))
        .andExpect(jsonPath("$.data.distance_km").value(150.50));

    verify(hubRouteQueryService).getRoute(departureHubId, arrivalHubId);
  }

  @Test
  void getInternalSameHubRouteSuccess() throws Exception {
    // given
    UUID hubId = UUID.randomUUID();

    when(hubRouteQueryService.getRoute(hubId, hubId))
        .thenReturn(new HubRouteResult(UUID.randomUUID(), hubId, hubId, 0, BigDecimal.ZERO));

    // when & then
    mockMvc
        .perform(
            get("/internal/hub-routes")
                .param("departure_hub_id", hubId.toString())
                .param("arrival_hub_id", hubId.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.departure_hub_id").value(hubId.toString()))
        .andExpect(jsonPath("$.data.arrival_hub_id").value(hubId.toString()))
        .andExpect(jsonPath("$.data.duration_minutes").value(0))
        .andExpect(jsonPath("$.data.distance_km").value(0));
  }

  @Test
  void getInternalHubRouteNotFound() throws Exception {
    // given
    UUID departureHubId = UUID.randomUUID();
    UUID arrivalHubId = UUID.randomUUID();

    when(hubRouteQueryService.getRoute(departureHubId, arrivalHubId))
        .thenThrow(new HubRouteBusinessException(HubRouteErrorCode.HUB_ROUTE_NOT_FOUND));

    // when & then
    mockMvc
        .perform(
            get("/internal/hub-routes")
                .param("departure_hub_id", departureHubId.toString())
                .param("arrival_hub_id", arrivalHubId.toString()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("HUB_ROUTE_NOT_FOUND"));
  }

  @Test
  void getInternalHubRouteMissingDepartureHubId() throws Exception {

    mockMvc
        .perform(get("/internal/hub-routes").param("arrival_hub_id", UUID.randomUUID().toString()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  void getInternalHubRouteMissingArrivalHubId() throws Exception {

    mockMvc
        .perform(
            get("/internal/hub-routes").param("departure_hub_id", UUID.randomUUID().toString()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  void getInternalHubRouteInvalidDepartureHubId() throws Exception {

    mockMvc
        .perform(
            get("/internal/hub-routes")
                .param("departure_hub_id", "invalid-uuid")
                .param("arrival_hub_id", UUID.randomUUID().toString()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  void getInternalHubRouteInvalidArrivalHubId() throws Exception {

    mockMvc
        .perform(
            get("/internal/hub-routes")
                .param("departure_hub_id", UUID.randomUUID().toString())
                .param("arrival_hub_id", "invalid-uuid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  void getInternalHubRouteMissingBothParameters() throws Exception {

    mockMvc
        .perform(get("/internal/hub-routes"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }
}
