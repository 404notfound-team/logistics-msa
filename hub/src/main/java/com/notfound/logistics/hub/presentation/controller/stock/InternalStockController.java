package com.notfound.logistics.hub.presentation.controller.stock;

import com.notfound.logistics.common.api.ApiResponse;
import com.notfound.logistics.hub.application.service.stock.StockQueryService;
import com.notfound.logistics.hub.presentation.dto.stock.StockResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/hubs/{hubId}/stocks")
// TODO(Users/Gateway): 서비스 간 인증 구현 후 허용된 주문 서비스만 내부 조회를 호출하도록 검증한다.
public class InternalStockController {
  private final StockQueryService query;

  @GetMapping("/{productId}")
  public ApiResponse<StockResponse> get(@PathVariable UUID hubId, @PathVariable UUID productId) {
    return new ApiResponse<>(StockResponse.from(query.getStock(hubId, productId)));
  }
}
