package com.notfound.logistics.hub.presentation.exception.hubroute;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.notfound.logistics.hub.application.service.hubroute.HubRouteQueryService;
import com.notfound.logistics.hub.domain.exception.hubroute.HubRouteBusinessException;
import com.notfound.logistics.hub.domain.exception.hubroute.HubRouteErrorCode;
import com.notfound.logistics.hub.presentation.controller.hubroute.HubRouteController;
import com.notfound.logistics.hub.presentation.exception.GlobalExceptionHandler;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.MDC;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class HubRouteExceptionHandlerTest {
  @ParameterizedTest
  @EnumSource(HubRouteErrorCode.class)
  void usesSharedErrorContractBeforeGlobalFallback(HubRouteErrorCode errorCode) throws Exception {
    var service = mock(HubRouteQueryService.class);
    when(service.getRoutes(null, null, 0, 20)).thenThrow(new HubRouteBusinessException(errorCode));
    var mvc =
        MockMvcBuilders.standaloneSetup(new HubRouteController(service))
            .setControllerAdvice(new GlobalExceptionHandler(), new HubRouteExceptionHandler())
            .build();

    try (var ignored = MDC.putCloseable("traceId", "hubroute-test-trace")) {
      mvc.perform(get("/api/hub-routes"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value(errorCode.code()))
          .andExpect(jsonPath("$.message").value(errorCode.message()))
          .andExpect(jsonPath("$.trace_id").value("hubroute-test-trace"))
          .andExpect(jsonPath("$.field_errors").isEmpty());
    }
  }
}
