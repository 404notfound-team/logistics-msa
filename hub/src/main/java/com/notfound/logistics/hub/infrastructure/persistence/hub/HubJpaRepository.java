package com.notfound.logistics.hub.infrastructure.persistence.hub;

import com.notfound.logistics.hub.domain.model.hub.Hub;
import com.notfound.logistics.hub.domain.repository.hub.HubRepository;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface HubJpaRepository extends JpaRepository<Hub, UUID>, HubRepository {
  @Override
  @Query("SELECT h FROM Hub h WHERE LOWER(h.name) LIKE :pattern ESCAPE '\\'")
  Page<Hub> searchByName(@Param("pattern") String pattern, Pageable pageable);

  @Override
  @Query("SELECT COUNT(h) FROM Hub h WHERE LOWER(h.name) LIKE :pattern ESCAPE '\\'")
  long countByNamePattern(@Param("pattern") String pattern);
}
