package com.notfound.logistics.users.infrastructure.config;

import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** local Compose에서만 사용하는 실제 로그인 계정. 기존 비밀번호·권한·삭제 상태는 보존한다. */
@Component
@Profile("local")
@ConditionalOnProperty(name = "users.dev-data.enabled", havingValue = "true")
public class LocalDevUsers implements ApplicationRunner {
  private static final List<Sample> SAMPLES =
      List.of(
          new Sample("c0ffee39-0006-4000-8000-000000000001", "devmaster", "MASTER", 1, false),
          new Sample("c0ffee39-0000-4000-8000-000000000099", "devseed", "USER", 1, false),
          new Sample("c0ffee39-0000-4000-8000-000000000098", "devseed2", "USER", 2, true));

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final PasswordEncoder encoder;
  private final String password;

  public LocalDevUsers(
      JdbcTemplate jdbc,
      PlatformTransactionManager manager,
      PasswordEncoder encoder,
      @Value("${users.dev-data.password:}") String password) {
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(manager);
    this.encoder = encoder;
    this.password = password;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (password.isBlank()) {
      throw new IllegalStateException(
          "DEV_DATA_PASSWORD is required for local development accounts");
    }
    transactions.executeWithoutResult(
        status -> {
          jdbc.execute("LOCK TABLE users.p_users IN SHARE ROW EXCLUSIVE MODE");
          for (Sample sample : SAMPLES) {
            var occupied =
                jdbc.queryForList(
                    "SELECT id, username, created_by FROM users.p_users WHERE id = ? OR username = ?",
                    UUID.fromString(sample.id()),
                    sample.username());
            for (var row : occupied) {
              if (!UUID.fromString(sample.id()).equals(row.get("id"))
                  || !sample.username().equals(row.get("username"))
                  || !"dev-seed".equals(row.get("created_by"))) {
                throw new IllegalStateException(
                    "Reserved development user belongs to another actor");
              }
            }
          }
          String hash = encoder.encode(password);
          for (Sample sample : SAMPLES) {
            jdbc.update(
                """
                INSERT INTO users.p_users
                  (id, username, name, password_hash, role, slack_id, hub_id, is_delivery_staff,
                   created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, 'dev-synthetic', ?, ?,
                        CURRENT_TIMESTAMP, 'dev-seed', CURRENT_TIMESTAMP, 'dev-seed')
                ON CONFLICT (id) DO NOTHING
                """,
                UUID.fromString(sample.id()),
                sample.username(),
                "개발 계정 " + sample.username(),
                hash,
                sample.role(),
                UUID.fromString("00000000-0000-0000-0000-%012d".formatted(sample.hub())),
                sample.deliveryStaff());
          }
        });
  }

  private record Sample(String id, String username, String role, int hub, boolean deliveryStaff) {}
}
