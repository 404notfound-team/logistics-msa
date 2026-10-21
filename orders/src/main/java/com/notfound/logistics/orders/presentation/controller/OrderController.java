package com.notfound.logistics.orders.presentation.controller;

import com.notfound.logistics.common.api.ApiErrorResponse;
import com.notfound.logistics.common.api.ApiResponse;
import com.notfound.logistics.common.api.PageResponse;
import com.notfound.logistics.orders.application.dto.OrderOperationResult;
import com.notfound.logistics.orders.application.service.OrderService;
import com.notfound.logistics.orders.domain.exception.OrderBusinessException;
import com.notfound.logistics.orders.domain.model.OrderStatus;
import com.notfound.logistics.orders.presentation.dto.CreateOrderRequest;
import com.notfound.logistics.orders.presentation.dto.OrderDetailResponse;
import com.notfound.logistics.orders.presentation.dto.OrderResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@ApiResponses({
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
@Tag(name = "Order", description = "주문 생성·조회·취소·삭제")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {
  private final OrderService service;

  @Operation(summary = "주문 생성", description = "멱등 키로 중복 생성을 방지하며 재고·배송 처리를 진행합니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "201",
        description = "최초 주문 확정",
        useReturnTypeSchema = true),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "동일 멱등 키로 처리한 기존 주문 반환",
        useReturnTypeSchema = true),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "202",
        description = "처리 중이거나 연동 결과 확인·복구 중",
        useReturnTypeSchema = true),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "업체·상품·재고를 찾을 수 없음",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = {
                  @ExampleObject(
                      name = "COMPANY_NOT_FOUND",
                      value =
                          "{\"code\": \"COMPANY_NOT_FOUND\", \"message\": \"업체를 찾을 수 없습니다.\", \"trace_id\": null, \"field_errors\": []}"),
                  @ExampleObject(
                      name = "PRODUCT_NOT_FOUND",
                      value =
                          "{\"code\": \"PRODUCT_NOT_FOUND\", \"message\": \"상품을 찾을 수 없습니다.\", \"trace_id\": null, \"field_errors\": []}"),
                  @ExampleObject(
                      name = "STOCK_NOT_FOUND",
                      value =
                          "{\"code\": \"STOCK_NOT_FOUND\", \"message\": \"재고를 찾을 수 없습니다.\", \"trace_id\": null, \"field_errors\": []}")
                })),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "409",
        description = "재고 부족, 다른 본문으로 멱등 키 재사용 또는 상품·공급업체 불일치",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = {
                  @ExampleObject(
                      name = "INSUFFICIENT_STOCK",
                      value =
                          "{\"code\": \"INSUFFICIENT_STOCK\", \"message\": \"재고가 부족합니다.\", \"trace_id\": null, \"field_errors\": []}"),
                  @ExampleObject(
                      name = "IDEMPOTENCY_KEY_REUSED",
                      value =
                          "{\"code\": \"IDEMPOTENCY_KEY_REUSED\", \"message\": \"같은 멱등 키로 다른 주문을 요청할 수 없습니다.\", \"trace_id\": null, \"field_errors\": []}"),
                  @ExampleObject(
                      name = "PRODUCT_SUPPLIER_MISMATCH",
                      value =
                          "{\"code\": \"PRODUCT_SUPPLIER_MISMATCH\", \"message\": \"상품이 해당 공급업체에 속하지 않습니다.\", \"trace_id\": null, \"field_errors\": []}")
                })),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "502",
        description = "배송 생성 실패가 확정됨",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = {
                  @ExampleObject(
                      name = "DELIVERY_CREATION_FAILED",
                      value =
                          "{\"code\": \"DELIVERY_CREATION_FAILED\", \"message\": \"배송 생성에 실패했습니다.\", \"trace_id\": null, \"field_errors\": []}")
                })),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "503",
        description = "연동 서비스 조회·처리 실패 또는 Gateway 사용자 확인 실패",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = {
                  @ExampleObject(
                      name = "UPSTREAM_UNAVAILABLE",
                      value =
                          "{\"code\": \"UPSTREAM_UNAVAILABLE\", \"message\": \"연동 서비스의 처리 결과를 확인할 수 없습니다.\", \"trace_id\": null, \"field_errors\": []}"),
                  @ExampleObject(
                      name = "SERVICE_UNAVAILABLE",
                      value =
                          "{\"code\": \"SERVICE_UNAVAILABLE\", \"message\": \"서비스를 일시적으로 사용할 수 없습니다.\", \"trace_id\": null, \"field_errors\": []}")
                })),
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
  @PostMapping
  public ResponseEntity<ApiResponse<OrderResponse>> create(
      @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal,
      @Valid @RequestBody CreateOrderRequest request,
      @Parameter(description = "요청별 UUID. 같은 주문 재시도 시 동일 값 사용", required = true)
          @RequestHeader("Idempotency-Key")
          UUID key) {
    return operation(service.create(request.toCommand(key), principal.getSubject()));
  }

  @Operation(summary = "주문 상세 조회", description = "MASTER 또는 주문 작성자가 주문 당시 정보를 조회합니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "조회 성공",
        useReturnTypeSchema = true),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "403",
        description = "MASTER 또는 주문 작성자만 조회 가능",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = {
                  @ExampleObject(
                      name = "ORDER_FORBIDDEN",
                      value =
                          "{\"code\": \"ORDER_FORBIDDEN\", \"message\": \"주문에 접근할 권한이 없습니다.\", \"trace_id\": null, \"field_errors\": []}")
                })),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "주문 없음",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = {
                  @ExampleObject(
                      name = "ORDER_NOT_FOUND",
                      value =
                          "{\"code\": \"ORDER_NOT_FOUND\", \"message\": \"주문을 찾을 수 없습니다.\", \"trace_id\": null, \"field_errors\": []}")
                })),
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
  @GetMapping("/{id}")
  public ApiResponse<OrderDetailResponse> get(
      @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal, @PathVariable UUID id) {
    return new ApiResponse<>(
        OrderDetailResponse.from(service.get(id, principal.getSubject(), isMaster(principal))));
  }

  private boolean isMaster(Jwt principal) {
    return "MASTER".equals(principal.getClaimAsString("role"));
  }

  @Operation(summary = "주문 목록·검색", description = "USER는 본인 주문, MASTER는 전체 주문을 검색합니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "조회 성공",
        useReturnTypeSchema = true),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "잘못된 검색·페이지·정렬 조건",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = {
                  @ExampleObject(
                      name = "INVALID_ORDER_INPUT",
                      value =
                          "{\"code\": \"INVALID_ORDER_INPUT\", \"message\": \"주문 정보가 올바르지 않습니다.\", \"trace_id\": null, \"field_errors\": []}"),
                  @ExampleObject(
                      name = "INVALID_REQUEST",
                      value =
                          "{\"code\": \"INVALID_REQUEST\", \"message\": \"요청 값이 올바르지 않습니다.\", \"trace_id\": null, \"field_errors\": []}")
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
  @GetMapping
  public ApiResponse<PageResponse<OrderDetailResponse>> search(
      @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal,
      @RequestParam(name = "product_id", required = false) UUID productId,
      @RequestParam(required = false) OrderStatus status,
      @RequestParam(name = "created_from", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant from,
      @RequestParam(name = "created_to", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "created_at,desc") String sort) {
    var result =
        service.search(
            productId,
            status,
            from,
            to,
            page,
            size,
            sort,
            principal.getSubject(),
            isMaster(principal));
    return new ApiResponse<>(
        new PageResponse<>(
            result.content().stream().map(OrderDetailResponse::from).toList(),
            result.page(),
            result.size(),
            result.totalElements()));
  }

  @Operation(summary = "주문 취소", description = "배송 취소 완료를 확인한 뒤 주문을 취소합니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "취소 완료 또는 이미 취소된 주문",
        useReturnTypeSchema = true),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "202",
        description = "취소 처리·배송 결과 확인·재고 복원 중 (CANCELLING 또는 RECOVERY_REQUIRED)",
        useReturnTypeSchema = true),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "403",
        description = "MASTER 또는 주문 작성자만 취소 가능",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = {
                  @ExampleObject(
                      name = "ORDER_FORBIDDEN",
                      value =
                          "{\"code\": \"ORDER_FORBIDDEN\", \"message\": \"주문에 접근할 권한이 없습니다.\", \"trace_id\": null, \"field_errors\": []}")
                })),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "주문 없음",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = {
                  @ExampleObject(
                      name = "ORDER_NOT_FOUND",
                      value =
                          "{\"code\": \"ORDER_NOT_FOUND\", \"message\": \"주문을 찾을 수 없습니다.\", \"trace_id\": null, \"field_errors\": []}")
                })),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "409",
        description = "취소할 수 없는 주문 상태 또는 이미 출발한 배송",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = {
                  @ExampleObject(
                      name = "ORDER_STATE_CONFLICT",
                      value =
                          "{\"code\": \"ORDER_STATE_CONFLICT\", \"message\": \"현재 주문 상태에서는 요청을 처리할 수 없습니다.\", \"trace_id\": null, \"field_errors\": []}")
                })),
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
  @PostMapping("/{id}/cancel")
  public ResponseEntity<ApiResponse<OrderResponse>> cancel(
      @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal, @PathVariable UUID id) {
    return operation(service.cancel(id, principal.getSubject(), isMaster(principal)));
  }

  @Operation(summary = "주문 논리 삭제", description = "MASTER가 종료된 주문과 연관 배송·경로를 논리 삭제합니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "204",
        description = "논리 삭제 완료",
        content = @Content),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "403",
        description = "MASTER만 삭제 가능",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = {
                  @ExampleObject(
                      name = "ORDER_FORBIDDEN",
                      value =
                          "{\"code\": \"ORDER_FORBIDDEN\", \"message\": \"주문에 접근할 권한이 없습니다.\", \"trace_id\": null, \"field_errors\": []}"),
                  @ExampleObject(
                      name = "FORBIDDEN",
                      value =
                          "{\"code\": \"FORBIDDEN\", \"message\": \"접근 권한이 없습니다.\", \"trace_id\": null, \"field_errors\": []}")
                })),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "주문 없음",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = {
                  @ExampleObject(
                      name = "ORDER_NOT_FOUND",
                      value =
                          "{\"code\": \"ORDER_NOT_FOUND\", \"message\": \"주문을 찾을 수 없습니다.\", \"trace_id\": null, \"field_errors\": []}")
                })),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "409",
        description = "종료되지 않은 주문·배송 또는 다른 작업 진행 중",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = {
                  @ExampleObject(
                      name = "ORDER_STATE_CONFLICT",
                      value =
                          "{\"code\": \"ORDER_STATE_CONFLICT\", \"message\": \"현재 주문 상태에서는 요청을 처리할 수 없습니다.\", \"trace_id\": null, \"field_errors\": []}")
                })),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "503",
        description = "배송 삭제 결과 또는 Gateway 사용자 정보를 확인할 수 없음",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = {
                  @ExampleObject(
                      name = "UPSTREAM_UNAVAILABLE",
                      value =
                          "{\"code\": \"UPSTREAM_UNAVAILABLE\", \"message\": \"연동 서비스의 처리 결과를 확인할 수 없습니다.\", \"trace_id\": null, \"field_errors\": []}"),
                  @ExampleObject(
                      name = "SERVICE_UNAVAILABLE",
                      value =
                          "{\"code\": \"SERVICE_UNAVAILABLE\", \"message\": \"서비스를 일시적으로 사용할 수 없습니다.\", \"trace_id\": null, \"field_errors\": []}")
                })),
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
                            "{\"code\": \"INVALID_REQUEST\", \"message\": \"요청 값이 올바르지 않습니다.\", \"trace_id\": null, \"field_errors\": []}")))
  })
  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(
      @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal, @PathVariable UUID id) {
    service.delete(id, principal.getSubject(), isMaster(principal));
    return ResponseEntity.noContent().build();
  }

  private ResponseEntity<ApiResponse<OrderResponse>> operation(OrderOperationResult result) {
    if (result.error() != null) throw new OrderBusinessException(result.error());
    var body = new ApiResponse<>(OrderResponse.from(result.order()));
    return switch (result.outcome()) {
      case CREATED ->
          ResponseEntity.created(URI.create("/api/orders/" + result.order().orderId())).body(body);
      case RETURNED -> ResponseEntity.ok(body);
      case IN_PROGRESS -> ResponseEntity.accepted().body(body);
      case REJECTED ->
          throw new IllegalStateException("Rejected order operation requires an error code");
    };
  }
}
