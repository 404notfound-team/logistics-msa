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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HubController.class)
@Import({GlobalExceptionHandler.class, HubExceptionHandler.class})
class HubControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private HubQueryService hubQueryService;

  @Test
  void getHubsSuccess() throws Exception {
    UUID id = UUID.randomUUID();

    HubResult hub = new HubResult(id, "서울특별시 센터", "서울특별시 송파구 송파대로 55");

    when(hubQueryService.getHubs(0, 20, "desc"))
        .thenReturn(new PageImpl<>(List.of(hub), PageRequest.of(0, 20), 1));

    mockMvc
        .perform(get("/api/hubs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].id").value(id.toString()))
        .andExpect(jsonPath("$.data.content[0].name").value("서울특별시 센터"))
        .andExpect(jsonPath("$.data.total_elements").value(1))
        .andExpect(jsonPath("$.data.page").value(0))
        .andExpect(jsonPath("$.data.size").value(20));
  }

  @Test
  void getHubSuccess() throws Exception {
    UUID id = UUID.randomUUID();

    when(hubQueryService.getHub(id)).thenReturn(new HubResult(id, "서울특별시 센터", "서울특별시 송파구 송파대로 55"));

    mockMvc
        .perform(get("/api/hubs/{id}", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(id.toString()))
        .andExpect(jsonPath("$.data.name").value("서울특별시 센터"));
  }

  @Test
  void getHubNotFound() throws Exception {
    UUID id = UUID.randomUUID();

    when(hubQueryService.getHub(id))
        .thenThrow(new HubBusinessException(HubErrorCode.HUB_NOT_FOUND));

    mockMvc
        .perform(get("/api/hubs/{id}", id))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("HUB_NOT_FOUND"));
  }

  @Test
  void getHubInvalidUuid() throws Exception {
    mockMvc.perform(get("/api/hubs/not-a-uuid")).andExpect(status().isBadRequest());
  }

  @Test
  void getHubsNegativePage() throws Exception {
    mockMvc
        .perform(get("/api/hubs").param("page", "-1"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.field_errors[0].field").value("page"))
        .andExpect(jsonPath("$.field_errors[0].message").exists());
  }

  @Test
  void getHubsZeroSize() throws Exception {
    mockMvc.perform(get("/api/hubs").param("size", "0")).andExpect(status().isBadRequest());
  }

  @Test
  void getHubsSizeExceeded() throws Exception {
    mockMvc.perform(get("/api/hubs").param("size", "101")).andExpect(status().isBadRequest());
  }

  @Test
  void getHubsInvalidDirection() throws Exception {
    mockMvc.perform(get("/api/hubs").param("direction", "abc")).andExpect(status().isBadRequest());
  }

  @Test
  void getHubsOutOfRangePage() throws Exception {
    when(hubQueryService.getHubs(5, 10, "desc"))
        .thenReturn(new PageImpl<>(List.of(), PageRequest.of(5, 10), 17));

    mockMvc
        .perform(get("/api/hubs").param("page", "5").param("size", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content").isEmpty())
        .andExpect(jsonPath("$.data.total_elements").value(17));
  }

  @Test
  void getHubsWithVeryLargePageReturnsEmptyContent() throws Exception {
    int page = 21474837;
    int size = 100;

    when(hubQueryService.getHubs(page, size, "desc"))
        .thenReturn(new PageImpl<>(List.of(), PageRequest.of(page, size), 17));

    mockMvc
        .perform(
            get("/api/hubs")
                .param("page", String.valueOf(page))
                .param("size", String.valueOf(size)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content").isEmpty())
        .andExpect(jsonPath("$.data.page").value(page))
        .andExpect(jsonPath("$.data.size").value(size))
        .andExpect(jsonPath("$.data.total_elements").value(17));
  }
}
