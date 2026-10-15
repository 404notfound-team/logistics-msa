package com.notfound.logistics.hub.infrastructure.persistence.hub;

import com.notfound.logistics.hub.domain.model.hub.Hub;
import com.notfound.logistics.hub.domain.repository.hub.HubRepository;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface HubJpaRepository extends JpaRepository<Hub, UUID>, HubRepository {}
