package com.notfound.logistics.orders.presentation.controller;

import com.notfound.logistics.common.api.ApiErrorResponse;
import com.notfound.logistics.common.api.ApiResponse;
import com.notfound.logistics.orders.application.service.OrderService;
import com.notfound.logistics.orders.presentation.dto.OrderRecoveryResponse;
import com.notfound.logistics.orders.presentation.dto.ResumeOrderRecoveryRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Order recovery", description = "MASTER 전용 차단 주문 확인·1회 재개")
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "401",
      description = "인증 실패",
      content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "403",
      description = "MASTER만 접근 가능",
      content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "404",
      description = "주문 없음 또는 논리 삭제됨",
      content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "500",
      description = "저장 또는 처리 실패. 새 조회로 현재 단계 확인",
      content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
})
@RestController
@RequestMapping("/api/orders/{id}/recovery")
@RequiredArgsConstructor
public class OrderRecoveryController {
  private final OrderService service;

  @Operation(summary = "주문 복구 상태 확인", description = "차단 여부·단계·실패 횟수·최근 오류·갱신 시각을 조회합니다.")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "200", description = "현재 복구 상태", useReturnTypeSchema = true)
  @GetMapping
  public ApiResponse<OrderRecoveryResponse> get(
      @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal, @PathVariable UUID id) {
    return new ApiResponse<>(
        OrderRecoveryResponse.from(service.recoveryStatus(id, isMaster(principal))));
  }

  @Operation(
      summary = "차단 주문 1회 재개",
      description =
          "운영자가 원격 결과와 원인을 확인한 뒤 최신 조회값으로 요청합니다. 저장된 단계·최초 입력·작업자를 보존하며 같은 단계의 자동 차단을 해제하지 않습니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "재개한 업무 종료. status와 last_error 확인",
        useReturnTypeSchema = true),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "202",
        description = "결과 불명 또는 차단 유지. 새 조회로 상태 확인",
        useReturnTypeSchema = true),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "입력 오류 또는 운영 확인 누락",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "409",
        description = "조회값 불일치·진행 중·미차단·종료·단계 불일치",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  })
  @PostMapping
  public ResponseEntity<ApiResponse<OrderRecoveryResponse>> resume(
      @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal,
      @PathVariable UUID id,
      @Valid @RequestBody ResumeOrderRecoveryRequest request) {
    final var result =
        service.resumeBlocked(
            id, request.toCommand(), principal.getSubject(), isMaster(principal));
    final var response = new ApiResponse<>(OrderRecoveryResponse.from(result));
    return result.recoveryPhase() == null
        ? ResponseEntity.ok(response)
        : ResponseEntity.accepted().body(response);
  }

  private boolean isMaster(Jwt principal) {
    return "MASTER".equals(principal.getClaimAsString("role"));
  }
}
