package com.notfound.logistics.hub.presentation.controller.stock;

import com.notfound.logistics.common.api.ApiErrorResponse;
import com.notfound.logistics.common.api.ApiResponse;
import com.notfound.logistics.common.api.PageResponse;
import com.notfound.logistics.hub.application.dto.stock.StockResult;
import com.notfound.logistics.hub.application.service.stock.StockCommandService;
import com.notfound.logistics.hub.application.service.stock.StockQueryService;
import com.notfound.logistics.hub.presentation.dto.stock.StockAddRequest;
import com.notfound.logistics.hub.presentation.dto.stock.StockCreateRequest;
import com.notfound.logistics.hub.presentation.dto.stock.StockResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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
@ApiResponses({
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "400",
      description = "요청 형식 또는 값 검증 실패",
      content =
          @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = ApiErrorResponse.class),
              examples =
                  @ExampleObject(
                      name = "INVALID_REQUEST",
                      value =
                          "{\"code\": \"INVALID_REQUEST\", \"message\": \"요청 값이 올바르지 않습니다.\", \"trace_id\": null, \"field_errors\": []}"))),
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "500",
      description = "예상 밖 서버 오류",
      content =
          @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = ApiErrorResponse.class),
              examples =
                  @ExampleObject(
                      name = "INTERNAL_ERROR",
                      value =
                          "{\"code\": \"INTERNAL_ERROR\", \"message\": \"요청 처리 중 오류가 발생했습니다.\", \"trace_id\": null, \"field_errors\": []}"))),
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "401",
      description = "토큰 누락·만료·위조 또는 현재 사용자 인증 실패",
      content =
          @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = ApiErrorResponse.class),
              examples =
                  @ExampleObject(
                      name = "UNAUTHORIZED",
                      value =
                          "{\"code\": \"UNAUTHORIZED\", \"message\": \"인증이 필요합니다.\", \"trace_id\": null, \"field_errors\": []}")))
})
@Tag(name = "Stock", description = "허브별 상품 재고 조회 API")
@RequestMapping("/api")
public class StockController {

  private final StockCommandService stockCommandService;
  private final StockQueryService stockQueryService;

  @Operation(summary = "재고 최초 등록", description = "허브·상품과 초기 수량을 지정해 재고를 등록합니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "201",
        description = "재고 등록 완료",
        useReturnTypeSchema = true),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "403",
        description = "MASTER만 재고 등록 가능",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = {
                  @ExampleObject(
                      name = "FORBIDDEN",
                      value =
                          "{\"code\": \"FORBIDDEN\", \"message\": \"접근 권한이 없습니다.\", \"trace_id\": null, \"field_errors\": []}")
                })),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "허브 없음",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = {
                  @ExampleObject(
                      name = "HUB_NOT_FOUND",
                      value =
                          "{\"code\": \"HUB_NOT_FOUND\", \"message\": \"허브를 찾을 수 없습니다.\", \"trace_id\": null, \"field_errors\": []}")
                })),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "409",
        description = "동일 허브·상품의 재고가 이미 등록됨",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = {
                  @ExampleObject(
                      name = "STOCK_ALREADY_EXISTS",
                      value =
                          "{\"code\": \"STOCK_ALREADY_EXISTS\", \"message\": \"해당 허브에 동일한 상품의 재고가 이미 존재합니다.\", \"trace_id\": null, \"field_errors\": []}")
                })),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "503",
        description = "상품 확인 실패 또는 Gateway 사용자 확인 실패",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = {
                  @ExampleObject(
                      name = "SERVICE_UNAVAILABLE",
                      value =
                          "{\"code\": \"SERVICE_UNAVAILABLE\", \"message\": \"서비스를 일시적으로 사용할 수 없습니다.\", \"trace_id\": null, \"field_errors\": []}")
                })),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "415",
        description = "지원하지 않는 요청 본문 형식",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples =
                    @ExampleObject(
                        name = "UNSUPPORTED_MEDIA_TYPE",
                        value =
                            "{\"code\": \"UNSUPPORTED_MEDIA_TYPE\", \"message\": \"지원하지 않는 요청 형식입니다.\", \"trace_id\": null, \"field_errors\": []}")))
  })
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
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "조회 성공",
        useReturnTypeSchema = true),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "503",
        description = "Gateway가 현재 사용자 정보를 확인할 수 없음",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples =
                    @ExampleObject(
                        name = "SERVICE_UNAVAILABLE",
                        value =
                            "{\"code\": \"SERVICE_UNAVAILABLE\", \"message\": \"서비스를 일시적으로 사용할 수 없습니다.\", \"trace_id\": null, \"field_errors\": []}")))
  })
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
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "조회 성공",
        useReturnTypeSchema = true),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "503",
        description = "Gateway가 현재 사용자 정보를 확인할 수 없음",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples =
                    @ExampleObject(
                        name = "SERVICE_UNAVAILABLE",
                        value =
                            "{\"code\": \"SERVICE_UNAVAILABLE\", \"message\": \"서비스를 일시적으로 사용할 수 없습니다.\", \"trace_id\": null, \"field_errors\": []}")))
  })
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
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "조회 성공",
        useReturnTypeSchema = true),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "허브·상품에 해당하는 재고 없음",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = {
                  @ExampleObject(
                      name = "STOCK_NOT_FOUND",
                      value =
                          "{\"code\": \"STOCK_NOT_FOUND\", \"message\": \"재고를 찾을 수 없습니다.\", \"trace_id\": null, \"field_errors\": []}")
                })),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "503",
        description = "Gateway가 현재 사용자 정보를 확인할 수 없음",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples =
                    @ExampleObject(
                        name = "SERVICE_UNAVAILABLE",
                        value =
                            "{\"code\": \"SERVICE_UNAVAILABLE\", \"message\": \"서비스를 일시적으로 사용할 수 없습니다.\", \"trace_id\": null, \"field_errors\": []}")))
  })
  @GetMapping("/hubs/{hubId}/products/{productId}/stocks")
  public ApiResponse<StockResponse> getStock(
      @PathVariable UUID hubId, @PathVariable UUID productId) {

    return new ApiResponse<>(StockResponse.from(stockQueryService.getStock(hubId, productId)));
  }

  @Operation(summary = "재고 추가", description = "입고된 상품의 수량을 기존 재고에 추가합니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "재고 추가 완료",
        useReturnTypeSchema = true),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "403",
        description = "MASTER만 재고 추가 가능",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = {
                  @ExampleObject(
                      name = "FORBIDDEN",
                      value =
                          "{\"code\": \"FORBIDDEN\", \"message\": \"접근 권한이 없습니다.\", \"trace_id\": null, \"field_errors\": []}")
                })),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "재고 없음",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = {
                  @ExampleObject(
                      name = "STOCK_NOT_FOUND",
                      value =
                          "{\"code\": \"STOCK_NOT_FOUND\", \"message\": \"재고를 찾을 수 없습니다.\", \"trace_id\": null, \"field_errors\": []}")
                })),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "409",
        description = "재고 수량 범위 초과",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = {
                  @ExampleObject(
                      name = "STOCK_QUANTITY_OVERFLOW",
                      value =
                          "{\"code\": \"STOCK_QUANTITY_OVERFLOW\", \"message\": \"재고 수량이 허용 범위를 초과했습니다.\", \"trace_id\": null, \"field_errors\": []}")
                })),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "503",
        description = "Gateway가 현재 사용자 정보를 확인할 수 없음",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples =
                    @ExampleObject(
                        name = "SERVICE_UNAVAILABLE",
                        value =
                            "{\"code\": \"SERVICE_UNAVAILABLE\", \"message\": \"서비스를 일시적으로 사용할 수 없습니다.\", \"trace_id\": null, \"field_errors\": []}"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "415",
        description = "지원하지 않는 요청 본문 형식",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples =
                    @ExampleObject(
                        name = "UNSUPPORTED_MEDIA_TYPE",
                        value =
                            "{\"code\": \"UNSUPPORTED_MEDIA_TYPE\", \"message\": \"지원하지 않는 요청 형식입니다.\", \"trace_id\": null, \"field_errors\": []}")))
  })
  @PostMapping("/hubs/{hubId}/products/{productId}/stocks/add")
  public ApiResponse<StockResponse> addStock(
      @PathVariable UUID hubId,
      @PathVariable UUID productId,
      @Valid @RequestBody StockAddRequest request) {

    StockResult result = stockCommandService.add(hubId, productId, request.quantity());

    return new ApiResponse<>(StockResponse.from(result));
  }
}
