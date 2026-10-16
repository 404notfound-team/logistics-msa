package com.notfound.logistics.hub.presentation.controller.stock;

import com.notfound.logistics.common.api.ApiResponse;
import com.notfound.logistics.common.api.PageResponse;
import com.notfound.logistics.hub.application.dto.stock.StockResult;
import com.notfound.logistics.hub.application.service.stock.StockCommandService;
import com.notfound.logistics.hub.application.service.stock.StockQueryService;
import com.notfound.logistics.hub.presentation.dto.stock.StockAddRequest;
import com.notfound.logistics.hub.presentation.dto.stock.StockCreateRequest;
import com.notfound.logistics.hub.presentation.dto.stock.StockResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Tag(name = "Stock", description = "허브별 상품 재고 조회 API")
@RequestMapping("/api")
public class StockController {

  private final StockCommandService stockCommandService;
  private final StockQueryService stockQueryService;

  @Operation(summary = "재고 최초 등록", description = "허브·상품과 초기 수량을 지정해 재고를 등록합니다.")
  @PostMapping("/stocks")
  public ResponseEntity<ApiResponse<StockResponse>> createStock(
      @Valid @RequestBody StockCreateRequest request) {
    var result =
        stockCommandService.create(request.hubId(), request.productId(), request.quantity());
    return ResponseEntity.created(
            URI.create(
                "/api/hubs/" + result.hubId() + "/products/" + result.productId() + "/stocks"))
        .body(new ApiResponse<>(StockResponse.from(result)));
  }

  @Operation(summary = "허브별 재고 목록 조회")
  @GetMapping("/hubs/{hubId}/stocks")
  public ApiResponse<PageResponse<StockResponse>> getHubStocks(
      @PathVariable UUID hubId,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
      @RequestParam(required = false) @Pattern(regexp = "(?i)(created_at|id),(asc|desc)")
          String sort) {
    return pageResponse(stockQueryService.getStocks(hubId, null, page, size, sort));
  }

  @Operation(summary = "상품의 전체 허브 재고 조회")
  @GetMapping("/products/{productId}/stocks")
  public ApiResponse<PageResponse<StockResponse>> getProductStocks(
      @PathVariable UUID productId,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
      @RequestParam(required = false) @Pattern(regexp = "(?i)(created_at|id),(asc|desc)")
          String sort) {

    return pageResponse(stockQueryService.getStocks(null, productId, page, size, sort));
  }

  private ApiResponse<PageResponse<StockResponse>> pageResponse(Page<StockResult> result) {
    return new ApiResponse<>(
        new PageResponse<>(
            result.getContent().stream().map(StockResponse::from).toList(),
            result.getNumber(),
            result.getSize(),
            result.getTotalElements()));
  }

  @Operation(summary = "허브·상품 기준 재고 상세 조회")
  @GetMapping("/hubs/{hubId}/products/{productId}/stocks")
  public ApiResponse<StockResponse> getStock(
      @PathVariable UUID hubId, @PathVariable UUID productId) {

    return new ApiResponse<>(StockResponse.from(stockQueryService.getStock(hubId, productId)));
  }

  @Operation(summary = "재고 추가", description = "입고된 상품의 수량을 기존 재고에 추가합니다.")
  @PostMapping("/hubs/{hubId}/products/{productId}/stocks/add")
  public ApiResponse<StockResponse> addStock(
      @PathVariable UUID hubId,
      @PathVariable UUID productId,
      @Valid @RequestBody StockAddRequest request) {

    StockResult result = stockCommandService.add(hubId, productId, request.quantity());

    return new ApiResponse<>(StockResponse.from(result));
  }
}
