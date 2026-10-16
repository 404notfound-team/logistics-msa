package com.notfound.logistics.hub.presentation.controller.hubroute;

import static com.notfound.logistics.hub.support.HubSecurityTestRequests.authenticatedGet;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.notfound.logistics.hub.application.dto.hubroute.HubRouteResult;
import com.notfound.logistics.hub.application.service.hubroute.HubRouteQueryService;
import com.notfound.logistics.hub.support.HubSecurityTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HubRouteController.class)
@HubSecurityTest
class HubRouteControllerTest {
  @Autowired private MockMvc mvc;
  @MockitoBean private HubRouteQueryService service;

  @Test
  void defaultRequestUsesSharedPageContract() throws Exception {
    UUID from = UUID.randomUUID();
    UUID to = UUID.randomUUID();
    UUID id = UUID.randomUUID();
    when(service.getRoutes(null, null, 0, 20))
        .thenReturn(
            new PageImpl<>(
                List.of(new HubRouteResult(id, from, to, 10, new BigDecimal("9.25"))),
                PageRequest.of(0, 20),
                21));
    mvc.perform(authenticatedGet("/api/hub-routes"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].id").value(id.toString()))
        .andExpect(jsonPath("$.data.content[0].departure_hub_id").value(from.toString()))
        .andExpect(jsonPath("$.data.content[0].arrival_hub_id").value(to.toString()))
        .andExpect(jsonPath("$.data.content[0].duration_minutes").value(10))
        .andExpect(jsonPath("$.data.content[0].distance_km").value(9.25))
        .andExpect(jsonPath("$.data.page").value(0))
        .andExpect(jsonPath("$.data.size").value(20))
        .andExpect(jsonPath("$.data.total_elements").value(21))
        .andExpect(jsonPath("$.data.total_pages").value(2));
  }

  @ParameterizedTest
  @CsvSource({"true,false", "false,true", "true,true"})
  void bindsSnakeCaseFilters(boolean departure, boolean arrival) throws Exception {
    UUID from = departure ? UUID.randomUUID() : null;
    UUID to = arrival ? UUID.randomUUID() : null;
    when(service.getRoutes(from, to, 0, 20))
        .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
    var request = authenticatedGet("/api/hub-routes");
    if (departure) {
      request.param("departure_hub_id", from.toString());
    }
    if (arrival) {
      request.param("arrival_hub_id", to.toString());
    }
    mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.data.content").isEmpty());
    verify(service).getRoutes(from, to, 0, 20);
  }

  @ParameterizedTest
  @CsvSource({
    "page,-1",
    "page,-2147483648",
    "page,2147483648",
    "page,9223372036854775808",
    "page,1.5",
    "page,abc",
    "size,0",
    "size,-1",
    "size,101",
    "size,2147483647",
    "size,2147483648",
    "size,abc",
    "departure_hub_id,invalid",
    "arrival_hub_id,invalid"
  })
  void rejectsInvalidParametersBeforeCallingService(String parameter, String value)
      throws Exception {
    mvc.perform(authenticatedGet("/api/hub-routes").param(parameter, value))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.field_errors").isArray());
    verifyNoInteractions(service);
  }

  @ParameterizedTest
  @CsvSource({
    "0,1,0",
    "0,100,0",
    "15,20,289",
    "2147483647,1,289",
    "2147483647,100,289",
    "21474837,100,289"
  })
  void acceptsValidBoundaryAndOutOfRangePages(int page, int size, long total) throws Exception {
    when(service.getRoutes(null, null, page, size))
        .thenReturn(new PageImpl<>(List.of(), PageRequest.of(page, size), total));
    mvc.perform(
            authenticatedGet("/api/hub-routes")
                .param("page", String.valueOf(page))
                .param("size", String.valueOf(size)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content").isEmpty())
        .andExpect(jsonPath("$.data.page").value(page))
        .andExpect(jsonPath("$.data.total_elements").value(total));
  }
}
