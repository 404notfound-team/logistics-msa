package com.notfound.logistics.delivery.application.service;

import com.notfound.logistics.delivery.application.dto.DeliveryCreateCommand;
import com.notfound.logistics.delivery.application.dto.DeliveryResult;
import com.notfound.logistics.delivery.domain.exception.DeliveryBusinessException;
import com.notfound.logistics.delivery.domain.exception.DeliveryErrorCode;
import com.notfound.logistics.delivery.domain.model.Delivery;
import com.notfound.logistics.delivery.domain.model.DeliveryRoute;
import com.notfound.logistics.delivery.domain.model.DeliveryRouteStatus;
import com.notfound.logistics.delivery.domain.repository.DeliveryRepository;
import com.notfound.logistics.delivery.infrastructure.client.HubClient;
import com.notfound.logistics.delivery.infrastructure.client.HubClient.HubRoute;
import feign.FeignException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 주문에 대한 배송과 허브 간 이동 경로(DeliveryRoute)를 생성한다. */
@Service
public class DeliveryCreationService {
  private static final long SECONDS_PER_MINUTE = 60;

  private final DeliveryRepository deliveryRepository;
  private final HubClient hubClient;
  private final TransactionTemplate transactionTemplate;

  public DeliveryCreationService(
      DeliveryRepository deliveryRepository,
      HubClient hubClient,
      PlatformTransactionManager transactionManager) {
    this.deliveryRepository = deliveryRepository;
    this.hubClient = hubClient;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  /**
   * 1) Hub에서 출발 → 목적지 허브의 기준 경로를 조회하고 2) 배송과 이동 구간을 한 트랜잭션으로 저장한다.
   *
   * <p>원격 호출 동안 DB 커넥션을 잡지 않도록 Hub 조회는 트랜잭션 밖에서 먼저 수행한다.
   *
   * <p>주문 1건에는 배송 1건만 만든다. Orders는 응답을 받지 못한 생성 요청을 다시 보내므로, 같은 내용의 재요청이면 기존 배송을 그대로 돌려주고(멱등), 내용이
   * 다르면 DELIVERY_ALREADY_EXISTS(409)로 거절한다.
   */
  public DeliveryResult create(DeliveryCreateCommand command) {
    Optional<DeliveryResult> existing = findExisting(command);
    if (existing.isPresent()) {
      return existing.get();
    }

    List<HubRoute> hubRoutes = findHubRoutes(command.departureHubId(), command.destinationHubId());
    try {
      return transactionTemplate.execute(status -> save(command, hubRoutes));
    } catch (DataIntegrityViolationException exception) {
      // 동시에 들어온 같은 주문의 요청이 먼저 저장되면 order_id 유니크 인덱스에 걸린다. 먼저 저장된 배송으로 다시 판단한다.
      return findExisting(command).orElseThrow(() -> exception);
    }
  }

  // 주문의 기존 배송을 찾는다. 요청 내용이 같으면 기존 배송(경로 포함)을 반환하고, 다르면 409로 거절한다.
  private Optional<DeliveryResult> findExisting(DeliveryCreateCommand command) {
    return deliveryRepository
        .findByOrderId(command.orderId())
        .map(
            delivery -> {
              if (!isSameRequest(delivery, command)) {
                throw new DeliveryBusinessException(DeliveryErrorCode.DELIVERY_ALREADY_EXISTS);
              }
              return DeliveryResult.of(
                  delivery, deliveryRepository.findRoutesByDeliveryId(delivery.getId()));
            });
  }

  // 기존 배송이 같은 요청으로 만들어졌는지 확인한다. 생성 후 바뀌지 않는 값(허브, 주소, 수령인)을 비교한다.
  private static boolean isSameRequest(Delivery delivery, DeliveryCreateCommand command) {
    return delivery.getDepartureHubId().equals(command.departureHubId())
        && delivery.getDestinationHubId().equals(command.destinationHubId())
        && delivery.getAddress().equals(command.address())
        && delivery.getRecipientName().equals(command.recipientName());
  }

  // 출발 허브와 목적지 허브가 같으면 허브 간 이동이 없으므로 경로를 조회하지 않는다.
  private List<HubRoute> findHubRoutes(UUID departureHubId, UUID destinationHubId) {
    if (departureHubId.equals(destinationHubId)) {
      return List.of();
    }
    return List.of(fetchRoute(departureHubId, destinationHubId));
  }

  // Hub 기준 경로 1건을 조회한다. 404는 경로 없음, 그 외 실패(연결 실패·5xx·인증 오류)는 Hub 이용 불가로 변환한다.
  private HubRoute fetchRoute(UUID departureHubId, UUID arrivalHubId) {
    try {
      return hubClient.getRoute(departureHubId, arrivalHubId).data();
    } catch (FeignException.NotFound exception) {
      throw new DeliveryBusinessException(DeliveryErrorCode.HUB_ROUTE_NOT_FOUND);
    } catch (FeignException exception) {
      throw new DeliveryBusinessException(DeliveryErrorCode.HUB_UNAVAILABLE);
    }
  }

  // 배송을 저장한 뒤, 조회한 경로를 순서(sequence 1부터)대로 대기(WAITING) 상태의 이동 구간으로 저장한다.
  private DeliveryResult save(DeliveryCreateCommand command, List<HubRoute> hubRoutes) {
    Delivery delivery =
        deliveryRepository.save(
            Delivery.create(
                command.orderId(),
                command.departureHubId(),
                command.destinationHubId(),
                command.address(),
                command.recipientName()));

    List<DeliveryRoute> routes =
        deliveryRepository.saveRoutes(
            IntStream.range(0, hubRoutes.size())
                .mapToObj(index -> toRoute(delivery, index + 1, hubRoutes.get(index)))
                .toList());
    return DeliveryResult.of(delivery, routes);
  }

  // Hub 기준 경로를 배송 이동 구간으로 변환한다. Hub의 소요 시간(분)을 초 단위로 바꾸고, 담당자는 아직 배정하지 않는다.
  private DeliveryRoute toRoute(Delivery delivery, int sequence, HubRoute hubRoute) {
    return new DeliveryRoute(
        UUID.randomUUID(),
        delivery,
        sequence,
        hubRoute.arrivalHubId(),
        hubRoute.departureHubId(),
        hubRoute.distanceKm(),
        hubRoute.durationMinutes() * SECONDS_PER_MINUTE,
        DeliveryRouteStatus.WAITING,
        null);
  }
}
