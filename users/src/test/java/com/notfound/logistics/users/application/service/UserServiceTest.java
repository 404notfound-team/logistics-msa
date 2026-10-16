package com.notfound.logistics.users.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.notfound.logistics.users.domain.exception.UserException;
import com.notfound.logistics.users.domain.model.User;
import com.notfound.logistics.users.domain.model.UserRoleEnum;
import com.notfound.logistics.users.domain.repository.UserRepository;
import com.notfound.logistics.users.infrastructure.client.CompanyLookupClient;
import com.notfound.logistics.users.infrastructure.client.HubLookupClient;
import com.notfound.logistics.users.presentation.dto.UserCreateRequestDto;
import com.notfound.logistics.users.presentation.dto.UserUpdateRequestDto;
import com.notfound.logistics.users.presentation.exception.UserErrorCode;
import feign.FeignException;
import feign.Request;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;

class UserServiceTest {
  private static final Instant NOW = Instant.parse("2026-10-16T01:00:00Z");
  private static final UUID USER_ID = UUID.randomUUID();
  private static final UUID COMPANY_ID = UUID.randomUUID();
  private static final UUID HUB_ID = UUID.randomUUID();

  private final UserRepository userRepository = mock(UserRepository.class);
  private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
  private final CompanyLookupClient companyLookupClient = mock(CompanyLookupClient.class);
  private final HubLookupClient hubLookupClient = mock(HubLookupClient.class);
  private final UserService userService =
      new UserService(
          userRepository,
          passwordEncoder,
          companyLookupClient,
          hubLookupClient,
          mock(JwtEncoder.class),
          Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void missingOrDeletedUserIsNotFound() {
    given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

    assertThatThrownBy(() -> userService.getUser(USER_ID))
        .isInstanceOfSatisfying(
            UserException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(UserErrorCode.USER_NOT_FOUND));
  }

  @Test
  void withdrawRecordsActorAndClockTime() {
    User user = regularUser();
    given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

    userService.withdraw(USER_ID, "user01");

    assertThat(user.getDeletedAt()).isEqualTo(NOW);
    assertThat(user.getDeletedBy()).isEqualTo("user01");
    verify(userRepository, never()).delete(any(User.class));
  }

  @Test
  void deleteRecordsMasterAsActor() {
    User user = regularUser();
    given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

    userService.deleteUser(USER_ID, "master01");

    assertThat(user.getDeletedBy()).isEqualTo("master01");
    assertThat(user.getDeletedAt()).isEqualTo(NOW);
  }

  @Test
  void createRejectsUsernameOfWithdrawnUser() {
    given(userRepository.existsByUsernameIncludingDeleted("user01")).willReturn(true);

    assertThatThrownBy(() -> userService.createUser(createRequest(UserRoleEnum.USER, HUB_ID)))
        .isInstanceOfSatisfying(
            UserException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(UserErrorCode.USERNAME_ALREADY_EXISTS));
  }

  @Test
  void createRejectsRegularUserWithoutAffiliationBeforeLookup() {
    assertThatThrownBy(() -> userService.createUser(createRequest(UserRoleEnum.USER, null)))
        .isInstanceOfSatisfying(
            UserException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(UserErrorCode.INVALID_AFFILIATION));
    verifyNoInteractions(hubLookupClient, companyLookupClient);
  }

  @Test
  void createAllowsMasterWithoutAffiliation() {
    given(passwordEncoder.encode("Passw0rd!")).willReturn("hash");
    given(userRepository.saveAndFlush(any(User.class))).willAnswer(call -> call.getArgument(0));

    var response = userService.createUser(createRequest(UserRoleEnum.MASTER, null));

    assertThat(response.role()).isEqualTo(UserRoleEnum.MASTER);
    assertThat(response.companyId()).isNull();
    assertThat(response.hubId()).isNull();
    verifyNoInteractions(hubLookupClient, companyLookupClient);
  }

  @Test
  void createReportsUnknownHub() {
    given(hubLookupClient.findById(HUB_ID)).willThrow(feignError(404));

    assertThatThrownBy(() -> userService.createUser(createRequest(UserRoleEnum.USER, HUB_ID)))
        .isInstanceOfSatisfying(
            UserException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(UserErrorCode.ENTITY_NOT_FOUND));
  }

  @Test
  void createReportsUnavailableLookup() {
    given(hubLookupClient.findById(HUB_ID)).willThrow(feignError(503));

    assertThatThrownBy(() -> userService.createUser(createRequest(UserRoleEnum.USER, HUB_ID)))
        .isInstanceOfSatisfying(
            UserException.class,
            e ->
                assertThat(e.getErrorCode())
                    .isEqualTo(UserErrorCode.AFFILIATION_LOOKUP_UNAVAILABLE));
  }

  @Test
  void updateKeepsOmittedFieldsAndHashesNewPassword() {
    User user = regularUser();
    given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
    given(passwordEncoder.encode("N3wPassw0rd!")).willReturn("new-hash");

    var response =
        userService.updateUser(
            USER_ID, new UserUpdateRequestDto(null, "N3wPassw0rd!", null, null, true, null, null));

    assertThat(user.getPasswordHash()).isEqualTo("new-hash");
    assertThat(response.name()).isEqualTo("사용자");
    assertThat(response.companyId()).isEqualTo(COMPANY_ID);
    assertThat(response.isDeliveryStaff()).isTrue();
    verifyNoInteractions(hubLookupClient, companyLookupClient);
    verify(userRepository).flush();
  }

  @Test
  void updateReplacesWholeAffiliationWhenOneIdIsGiven() {
    User user = regularUser();
    given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

    var response =
        userService.updateUser(
            USER_ID, new UserUpdateRequestDto(null, null, null, null, null, null, HUB_ID));

    assertThat(response.hubId()).isEqualTo(HUB_ID);
    assertThat(response.companyId()).isNull();
    verify(hubLookupClient).findById(HUB_ID);
  }

  private User regularUser() {
    return User.create(
        "user01", "사용자", "hash", "slack", UserRoleEnum.USER, COMPANY_ID, null, false);
  }

  private UserCreateRequestDto createRequest(UserRoleEnum role, UUID hubId) {
    return new UserCreateRequestDto("user01", "사용자", "Passw0rd!", role, "slack", null, hubId, null);
  }

  private FeignException feignError(int status) {
    Request request =
        Request.create(
            Request.HttpMethod.GET,
            "http://hub/internal/hubs/" + HUB_ID,
            Map.of(),
            null,
            StandardCharsets.UTF_8,
            null);
    return FeignException.errorStatus(
        "findById",
        feign.Response.builder().status(status).request(request).headers(Map.of()).build());
  }
}
