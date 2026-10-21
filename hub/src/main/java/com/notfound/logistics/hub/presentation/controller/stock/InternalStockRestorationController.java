package com.notfound.logistics.hub.presentation.controller.stock;

import com.notfound.logistics.common.api.ApiResponse;
import com.notfound.logistics.hub.application.dto.stock.StockResult;
import com.notfound.logistics.hub.application.service.stock.StockCommandService;
import com.notfound.logistics.hub.presentation.dto.stock.StockResponse;
import com.notfound.logistics.hub.presentation.dto.stock.StockRestoreRequest;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/stocks")
public class InternalStockRestorationController {

  private final StockCommandService stockCommandService;

  @Operation(summary = "내부 재고 복원", description = "주문 서비스의 요청에 따라 지정된 재고 수량을 복원합니다.")
  @PostMapping("/restorations")
  public ApiResponse<StockResponse> restore(@Valid @RequestBody StockRestoreRequest request) {
    StockResult result =
        stockCommandService.restore(
            request.orderId(), request.hubId(), request.productId(), request.quantity());

    return new ApiResponse<>(StockResponse.from(result));
  }
}
