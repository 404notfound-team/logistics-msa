package com.notfound.logistics.users.repository;

import com.notfound.logistics.users.entity.User;
import java.util.UUID;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {
  boolean existsByUsername(String username);

  Optional<User> findByUsername(String username);
}
