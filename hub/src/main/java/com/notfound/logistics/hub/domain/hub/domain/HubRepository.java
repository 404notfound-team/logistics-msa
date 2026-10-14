package com.notfound.logistics.hub.domain.hub.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface HubRepository {

  List<Hub> findAll();

  Optional<Hub> findById(UUID id);

  Page<Hub> findAll(Pageable pageable);
}
