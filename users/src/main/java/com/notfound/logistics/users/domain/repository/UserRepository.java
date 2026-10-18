package com.notfound.logistics.users.domain.repository;

import com.notfound.logistics.users.domain.model.User;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {
  /**
   * 탈퇴·삭제한 사용자의 username도 재사용할 수 없으므로 삭제 여부와 관계없이 확인합니다. 엔티티의 {@code @SQLRestriction}을 우회하려고 네이티브
   * 쿼리를 사용합니다.
   */
  @Query(
      value = "SELECT EXISTS (SELECT 1 FROM {h-schema}p_users WHERE username = :username)",
      nativeQuery = true)
  boolean existsByUsernameIncludingDeleted(@Param("username") String username);

  Optional<User> findByUsername(String username);

  /** 수정·탈퇴·삭제가 같은 사용자의 이전 상태를 덮어쓰지 않도록 트랜잭션 종료까지 잠급니다. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select u from User u where u.id = :id")
  Optional<User> findByIdForUpdate(@Param("id") UUID id);
}
