package com.notfound.logistics.delivery.application.dto;

/**
 * 배송 생성 결과. created가 true면 이번 요청으로 새로 만들었고, false면 같은 주문·같은 내용의 기존 배송을 돌려준 것이다. 컨트롤러가 201/200을 구분하는
 * 데 쓴다.
 */
public record DeliveryCreationResult(DeliveryResult delivery, boolean created) {}
