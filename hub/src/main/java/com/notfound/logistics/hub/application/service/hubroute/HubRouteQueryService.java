package com.notfound.logistics.hub.application.service.hubroute;

import com.notfound.logistics.hub.application.dto.hubroute.HubRouteResult;
import com.notfound.logistics.hub.domain.model.hubroute.HubRoute;
import com.notfound.logistics.hub.domain.repository.hubroute.HubRouteRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HubRouteQueryService {

  private final HubRouteRepository hubRouteRepository;

  public Page<HubRouteResult> getRoutes(
      UUID departureHubId, UUID arrivalHubId, int page, int size) {

    Pageable pageable =
        PageRequest.of(
            page,
            size,
            Sort.by(Sort.Direction.ASC, "departureHubId")
                .and(Sort.by(Sort.Direction.ASC, "arrivalHubId")));

    // JPA Offset의 정수 범위 초과 방지
    if (pageable.getOffset() >= Integer.MAX_VALUE) {
      long totalElements = countRoutes(departureHubId, arrivalHubId);

      return new PageImpl<>(List.of(), pageable, totalElements);
    }

    Page<HubRoute> routes;

    if (departureHubId != null && arrivalHubId != null) {
      routes =
          hubRouteRepository.findByDepartureHubIdAndArrivalHubId(
              departureHubId, arrivalHubId, pageable);

    } else if (departureHubId != null) {
      routes = hubRouteRepository.findByDepartureHubId(departureHubId, pageable);

    } else if (arrivalHubId != null) {
      routes = hubRouteRepository.findByArrivalHubId(arrivalHubId, pageable);

    } else {
      routes = hubRouteRepository.findAll(pageable);
    }

    return routes.map(HubRouteResult::from);
  }

  private long countRoutes(UUID departureHubId, UUID arrivalHubId) {

    if (departureHubId != null && arrivalHubId != null) {
      return hubRouteRepository.countByDepartureHubIdAndArrivalHubId(departureHubId, arrivalHubId);
    }

    if (departureHubId != null) {
      return hubRouteRepository.countByDepartureHubId(departureHubId);
    }

    if (arrivalHubId != null) {
      return hubRouteRepository.countByArrivalHubId(arrivalHubId);
    }

    return hubRouteRepository.count();
  }
}
