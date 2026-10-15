package com.notfound.logistics.hub.infrastructure.persistence.hubroute;

import com.notfound.logistics.hub.domain.model.hubroute.HubRoute;
import com.notfound.logistics.hub.domain.repository.hubroute.HubRouteRepository;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface HubRouteJpaRepository extends JpaRepository<HubRoute, UUID>, HubRouteRepository {}
