package com.notfound.logistics.users.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserTest {
  private static final UUID COMPANY_ID = UUID.randomUUID();
  private static final UUID HUB_ID = UUID.randomUUID();

  @Test
  void masterMayHaveNoAffiliationButRegularUserMustHaveExactlyOne() {
    assertThat(User.isValidAffiliation(UserRoleEnum.MASTER, null, null)).isTrue();
    assertThat(User.isValidAffiliation(UserRoleEnum.MASTER, COMPANY_ID, null)).isTrue();
    assertThat(User.isValidAffiliation(UserRoleEnum.USER, null, HUB_ID)).isTrue();
    assertThat(User.isValidAffiliation(UserRoleEnum.USER, null, null)).isFalse();
    assertThat(User.isValidAffiliation(UserRoleEnum.MASTER, COMPANY_ID, HUB_ID)).isFalse();
  }

  @Test
  void updateReplacesAccountValuesTogether() {
    User user = regularUser();

    user.update("새이름", "new-slack", "new-hash", UserRoleEnum.MASTER, true, null, null);

    assertThat(user.getName()).isEqualTo("새이름");
    assertThat(user.getSlackId()).isEqualTo("new-slack");
    assertThat(user.getPasswordHash()).isEqualTo("new-hash");
    assertThat(user.getRole()).isEqualTo(UserRoleEnum.MASTER);
    assertThat(user.isDeliveryStaff()).isTrue();
    assertThat(user.getCompanyId()).isNull();
    assertThat(user.getHubId()).isNull();
    assertThat(user.getUsername()).isEqualTo("user01");
  }

  @Test
  void updateRejectsRegularUserWithoutAffiliationAndKeepsPreviousState() {
    User user = regularUser();

    assertThatThrownBy(
            () -> user.update("이름", "slack", "hash", UserRoleEnum.USER, false, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(user.getCompanyId()).isEqualTo(COMPANY_ID);
  }

  @Test
  void deletionIsRecordedOnlyOnce() {
    User user = regularUser();
    Instant first = Instant.parse("2026-10-16T00:00:00Z");

    user.markDeleted("master01", first);
    user.markDeleted("other", first.plusSeconds(60));

    assertThat(user.getDeletedAt()).isEqualTo(first);
    assertThat(user.getDeletedBy()).isEqualTo("master01");
  }

  private User regularUser() {
    return User.create(
        "user01", "사용자", "hash", "slack", UserRoleEnum.USER, COMPANY_ID, null, false);
  }
}
