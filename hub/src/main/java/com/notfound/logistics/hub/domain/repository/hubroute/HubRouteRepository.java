package com.notfound.logistics.hub.domain.repository.hubroute;

import com.notfound.logistics.hub.domain.model.hubroute.HubRoute;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface HubRouteRepository {

  Page<HubRoute> findAll(Pageable pageable);

  Page<HubRoute> findByDepartureHubId(UUID departureHubId, Pageable pageable);

  Page<HubRoute> findByArrivalHubId(UUID arrivalHubId, Pageable pageable);

  Page<HubRoute> findByDepartureHubIdAndArrivalHubId(
      UUID departureHubId, UUID arrivalHubId, Pageable pageable);

  long count();

  long countByDepartureHubId(UUID departureHubId);

  long countByArrivalHubId(UUID arrivalHubId);

  long countByDepartureHubIdAndArrivalHubId(UUID departureHubId, UUID arrivalHubId);
}
