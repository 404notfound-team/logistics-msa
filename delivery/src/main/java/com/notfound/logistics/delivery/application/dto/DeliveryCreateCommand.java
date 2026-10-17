package com.notfound.logistics.delivery.application.dto;

import java.util.UUID;

/** 배송 생성 입력값. presentation 요청 DTO에 application이 의존하지 않도록 분리한다. */
public record DeliveryCreateCommand(
    UUID orderId,
    UUID departureHubId,
    UUID destinationHubId,
    String address,
    String recipientName) {}
