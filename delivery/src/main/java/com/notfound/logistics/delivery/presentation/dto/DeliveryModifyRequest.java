package com.notfound.logistics.delivery.presentation.dto;

import com.notfound.logistics.delivery.domain.model.DeliveryStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "배송 정보 수정·수동 배정. status와 assignee_id 중 하나 이상을 보낸다")
public record DeliveryModifyRequest(
    @Schema(description = "변경할 배송 상태", nullable = true) DeliveryStatus status,
    @Schema(description = "배정할 업체 배송 담당 사용자 ID", nullable = true) UUID assigneeId) {}
