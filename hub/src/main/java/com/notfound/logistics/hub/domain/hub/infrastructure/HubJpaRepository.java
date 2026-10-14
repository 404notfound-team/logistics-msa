package com.notfound.logistics.hub.domain.hub.infrastructure;

import com.notfound.logistics.hub.domain.hub.domain.Hub;
import com.notfound.logistics.hub.domain.hub.domain.HubRepository;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface HubJpaRepository extends JpaRepository<Hub, UUID>, HubRepository {}
