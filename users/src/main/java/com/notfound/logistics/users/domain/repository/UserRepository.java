package com.notfound.logistics.users.domain.repository;

import com.notfound.logistics.users.domain.model.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {
  boolean existsByUsername(String username);

  Optional<User> findByUsername(String username);
}
