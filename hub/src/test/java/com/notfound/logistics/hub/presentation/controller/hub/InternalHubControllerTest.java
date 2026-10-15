package com.notfound.logistics.hub.presentation.controller.hub;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.notfound.logistics.hub.application.dto.hub.HubResult;
import com.notfound.logistics.hub.application.service.hub.HubQueryService;
import com.notfound.logistics.hub.domain.exception.hub.HubBusinessException;
import com.notfound.logistics.hub.domain.exception.hub.HubErrorCode;
import com.notfound.logistics.hub.presentation.exception.GlobalExceptionHandler;
import com.notfound.logistics.hub.presentation.exception.hub.HubExceptionHandler;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(InternalHubController.class)
@Import({GlobalExceptionHandler.class, HubExceptionHandler.class})
class InternalHubControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private HubQueryService hubQueryService;

  @Test
  void getInternalHubsByNameSuccess() throws Exception {
    UUID hubId = UUID.randomUUID();
    String name = "서울특별시 센터";

    when(hubQueryService.getHubsByName(name))
      .thenReturn(
        List.of(
          new HubResult(
            hubId,
            name,
            "서울특별시 송파구 송파대로 55")));

    mockMvc
      .perform(get("/internal/hubs").param("name", name))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.length()").value(1))
      .andExpect(jsonPath("$.data[0].id").value(hubId.toString()))
      .andExpect(jsonPath("$.data[0].name").value(name))
      .andExpect(jsonPath("$.data[0].address").doesNotExist());
  }

  @Test
  void getInternalHubsByNameEmpty() throws Exception {
    String name = "존재하지 않는 허브";

    when(hubQueryService.getHubsByName(name))
      .thenReturn(List.of());

    mockMvc
      .perform(get("/internal/hubs").param("name", name))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data").isEmpty());
  }

  @Test
  void getInternalHubsByNameMissing() throws Exception {
    mockMvc
      .perform(get("/internal/hubs"))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  void getInternalHubsByNameBlank() throws Exception {
    mockMvc
      .perform(get("/internal/hubs").param("name", " "))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  void getInternalHubSuccess() throws Exception {
    UUID hubId = UUID.randomUUID();

    when(hubQueryService.getHub(hubId))
      .thenReturn(
        new HubResult(
          hubId,
          "서울특별시 센터",
          "서울특별시 송파구 송파대로 55"));

    mockMvc
      .perform(get("/internal/hubs/{id}", hubId))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.id").value(hubId.toString()))
      .andExpect(jsonPath("$.data.name").value("서울특별시 센터"))
      .andExpect(
        jsonPath("$.data.address")
          .value("서울특별시 송파구 송파대로 55"));
  }

  @Test
  void getInternalHubNotFound() throws Exception {
    UUID hubId = UUID.randomUUID();

    when(hubQueryService.getHub(hubId))
      .thenThrow(
        new HubBusinessException(HubErrorCode.HUB_NOT_FOUND));

    mockMvc
      .perform(get("/internal/hubs/{id}", hubId))
      .andExpect(status().isNotFound())
      .andExpect(jsonPath("$.code").value("HUB_NOT_FOUND"));
  }

  @Test
  void getInternalHubInvalidUuid() throws Exception {
    mockMvc
      .perform(get("/internal/hubs/not-a-uuid"))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }
}
