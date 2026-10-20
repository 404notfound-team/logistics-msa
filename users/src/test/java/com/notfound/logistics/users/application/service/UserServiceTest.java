package com.notfound.logistics.users.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.notfound.logistics.common.api.ApiResponse;
import com.notfound.logistics.users.domain.exception.UserException;
import com.notfound.logistics.users.domain.model.User;
import com.notfound.logistics.users.domain.model.UserRoleEnum;
import com.notfound.logistics.users.domain.repository.UserRepository;
import com.notfound.logistics.users.infrastructure.client.CompanyLookupClient;
import com.notfound.logistics.users.infrastructure.client.HubLookupClient;
import com.notfound.logistics.users.infrastructure.security.RefreshTokenCodec;
import com.notfound.logistics.users.infrastructure.security.RefreshTokenCodec.RefreshClaims;
import com.notfound.logistics.users.presentation.dto.UserCreateRequestDto;
import com.notfound.logistics.users.presentation.dto.UserRegisterRequestDto;
import com.notfound.logistics.users.presentation.dto.UserSignInRequestDto;
import com.notfound.logistics.users.presentation.dto.UserUpdateRequestDto;
import com.notfound.logistics.users.presentation.exception.UserErrorCode;
import feign.FeignException;
import feign.Request;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.util.ReflectionTestUtils;

class UserServiceTest {
  private static final Instant NOW = Instant.parse("2026-10-16T01:00:00Z");
  private static final UUID USER_ID = UUID.randomUUID();
  private static final UUID COMPANY_ID = UUID.randomUUID();
  private static final UUID HUB_ID = UUID.randomUUID();

  private final UserRepository userRepository = mock(UserRepository.class);
  private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
  private final CompanyLookupClient companyLookupClient = mock(CompanyLookupClient.class);
  private final HubLookupClient hubLookupClient = mock(HubLookupClient.class);
  private final JwtEncoder jwtEncoder = mock(JwtEncoder.class);
  private final RefreshTokenCodec refreshTokenCodec =
      new RefreshTokenCodec(
          new SecretKeySpec(
              "test-only-refresh-key-for-users-module-32bytes".getBytes(StandardCharsets.UTF_8),
              "HmacSHA256"),
          Duration.ofDays(14));
  private final UserService userService =
      new UserService(
          userRepository,
          passwordEncoder,
          companyLookupClient,
          hubLookupClient,
          jwtEncoder,
          refreshTokenCodec,
          Clock.fixed(NOW, ZoneOffset.UTC));

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void signupResolvesOneTrimmedAffiliationAndSavesHashedPassword(boolean company) {
    affiliationMatches(company, 1);
    given(passwordEncoder.encode("Passw0rd!")).willReturn("password-hash");
    given(userRepository.save(any(User.class)))
        .willAnswer(
            call -> {
              User saved = call.getArgument(0);
              ReflectionTestUtils.setField(saved, "id", USER_ID);
              return saved;
            });

    var response = userService.register(signupRequest(company));

    var saved = ArgumentCaptor.forClass(User.class);
    verify(userRepository).save(saved.capture());
    assertThat(response.id()).isEqualTo(USER_ID);
    assertThat(saved.getValue().getUsername()).isEqualTo("user01");
    assertThat(saved.getValue().getPasswordHash()).isEqualTo("password-hash");
    assertThat(saved.getValue().getCompanyId()).isEqualTo(company ? COMPANY_ID : null);
    assertThat(saved.getValue().getHubId()).isEqualTo(company ? null : HUB_ID);
    if (company) {
      verifyNoInteractions(hubLookupClient);
    } else {
      verifyNoInteractions(companyLookupClient);
    }
  }

  @Test
  void signupRejectsAnExistingUsernameBeforeAnyLookupOrPasswordHashing() {
    given(userRepository.existsByUsernameIncludingDeleted("user01")).willReturn(true);

    assertThatThrownBy(() -> userService.register(signupRequest(true)))
        .isInstanceOfSatisfying(
            UserException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(UserErrorCode.USERNAME_ALREADY_EXISTS));
    verifyNoInteractions(companyLookupClient, hubLookupClient, passwordEncoder);
    verify(userRepository, never()).save(any(User.class));
  }

  @ParameterizedTest
  @CsvSource({"true,0", "false,0", "true,2", "false,2"})
  void signupRejectsMissingAndAmbiguousAffiliationsWithoutSaving(boolean company, int matches) {
    affiliationMatches(company, matches);

    assertThatThrownBy(() -> userService.register(signupRequest(company)))
        .isInstanceOfSatisfying(
            UserException.class,
            e ->
                assertThat(e.getErrorCode())
                    .isEqualTo(
                        matches > 1
                            ? UserErrorCode.AFFILIATION_AMBIGUOUS
                            : UserErrorCode.ENTITY_NOT_FOUND));
    verify(userRepository, never()).save(any(User.class));
    verifyNoInteractions(passwordEncoder);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void signupReportsLookupFailureWithoutSaving(boolean company) {
    if (company) {
      given(companyLookupClient.findByName("물류업체")).willThrow(feignError(503));
    } else {
      given(hubLookupClient.findByName("서울 허브")).willThrow(feignError(503));
    }

    assertThatThrownBy(() -> userService.register(signupRequest(company)))
        .isInstanceOfSatisfying(
            UserException.class,
            e ->
                assertThat(e.getErrorCode())
                    .isEqualTo(UserErrorCode.AFFILIATION_LOOKUP_UNAVAILABLE));
    verify(userRepository, never()).save(any(User.class));
    verifyNoInteractions(passwordEncoder);
  }

  @Test
  void signupReportsAUsernameConflictThatOccursAfterThePrecheck() {
    affiliationMatches(true, 1);
    given(passwordEncoder.encode("Passw0rd!")).willReturn("password-hash");
    given(userRepository.save(any(User.class)))
        .willThrow(new DataIntegrityViolationException("duplicate username"));

    assertThatThrownBy(() -> userService.register(signupRequest(true)))
        .isInstanceOfSatisfying(
            UserException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(UserErrorCode.USERNAME_ALREADY_EXISTS));
  }

  @Test
  void signinOfAnUnknownOrDeletedUsernameDoesNotEncodeAToken() {
    given(userRepository.findByUsername("user01")).willReturn(Optional.empty());

    assertThatThrownBy(() -> userService.signIn(new UserSignInRequestDto("user01", "Passw0rd!")))
        .isInstanceOfSatisfying(
            UserException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(UserErrorCode.INVALID_CREDENTIALS));
    verifyNoInteractions(passwordEncoder, jwtEncoder);
  }

  @Test
  void signinWithTheWrongPasswordDoesNotEncodeAToken() {
    given(userRepository.findByUsername("user01")).willReturn(Optional.of(regularUser()));
    given(passwordEncoder.matches("wrong-password", "hash")).willReturn(false);

    assertThatThrownBy(
            () -> userService.signIn(new UserSignInRequestDto("user01", "wrong-password")))
        .isInstanceOfSatisfying(
            UserException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(UserErrorCode.INVALID_CREDENTIALS));
    verifyNoInteractions(jwtEncoder);
  }

  @ParameterizedTest
  @CsvSource({"USER,false", "MASTER,true"})
  void signinIssuesTheStoredIdentityAndConfiguredLifetimeWithoutPasswordClaims(
      UserRoleEnum role, boolean deliveryStaff) {
    User user =
        User.create("user01", "사용자", "hash", "slack", role, COMPANY_ID, null, deliveryStaff);
    ReflectionTestUtils.setField(user, "id", USER_ID);
    ReflectionTestUtils.setField(userService, "accessTokenTtl", Duration.ofMinutes(15));
    given(userRepository.findByUsername("user01")).willReturn(Optional.of(user));
    given(passwordEncoder.matches("Passw0rd!", "hash")).willReturn(true);
    given(jwtEncoder.encode(any()))
        .willReturn(
            Jwt.withTokenValue("signed-access-token")
                .header("alg", "HS256")
                .subject("user01")
                .build());

    var response = userService.signIn(new UserSignInRequestDto("user01", "Passw0rd!"));

    assertThat(response.accessToken()).isEqualTo("signed-access-token");
    assertThat(response.accessTokenTtl()).isEqualTo(Duration.ofMinutes(15));
    assertThat(response.refreshTokenTtl()).isEqualTo(Duration.ofDays(14));
    assertThat(refreshTokenCodec.verify(response.refreshToken()))
        .contains(new RefreshClaims(USER_ID, 0));
    var parameters = ArgumentCaptor.forClass(JwtEncoderParameters.class);
    verify(jwtEncoder).encode(parameters.capture());
    var claims = parameters.getValue().getClaims();
    assertThat(parameters.getValue().getJwsHeader().getAlgorithm()).isEqualTo(MacAlgorithm.HS256);
    assertThat(parameters.getValue().getJwsHeader().getType()).isEqualTo("JWT");
    assertThat(claims.getSubject()).isEqualTo("user01");
    assertThat(claims.getClaims())
        .containsEntry("iss", "logistics-users")
        .containsEntry("user_id", USER_ID.toString())
        .containsEntry("role", role.name())
        .containsEntry("is_delivery_staff", deliveryStaff)
        .containsEntry("token_version", 0L)
        .doesNotContainKeys("password", "password_hash", "passwordHash")
        .doesNotContainValue("Passw0rd!")
        .doesNotContainValue("hash");
    assertThat(Duration.between(claims.getIssuedAt(), claims.getExpiresAt()))
        .isEqualTo(Duration.ofMinutes(15));
  }

  @Test
  void refreshIssuesNewTokensWithTheCurrentRole() {
    User user =
        User.create("user01", "사용자", "hash", "slack", UserRoleEnum.MASTER, null, null, true);
    ReflectionTestUtils.setField(user, "id", USER_ID);
    ReflectionTestUtils.setField(userService, "accessTokenTtl", Duration.ofMinutes(15));
    given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
    given(jwtEncoder.encode(any()))
        .willReturn(
            Jwt.withTokenValue("renewed-access-token")
                .header("alg", "HS256")
                .subject("user01")
                .build());

    var tokens = userService.refresh(refreshTokenCodec.issue(USER_ID, 0, Instant.now()));

    assertThat(tokens.accessToken()).isEqualTo("renewed-access-token");
    assertThat(refreshTokenCodec.verify(tokens.refreshToken()))
        .contains(new RefreshClaims(USER_ID, 0));
    var parameters = ArgumentCaptor.forClass(JwtEncoderParameters.class);
    verify(jwtEncoder).encode(parameters.capture());
    assertThat(parameters.getValue().getClaims().getClaims())
        .containsEntry("role", "MASTER")
        .containsEntry("is_delivery_staff", true);
  }

  @Test
  void refreshRejectsTokensRevokedBySignOutOrWithdrawal() {
    User user = regularUser();
    ReflectionTestUtils.setField(user, "id", USER_ID);
    given(userRepository.findByIdForUpdate(USER_ID)).willReturn(Optional.of(user));
    given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
    String beforeSignOut = refreshTokenCodec.issue(USER_ID, 0, Instant.now());

    userService.signOut(USER_ID);

    expectInvalidRefreshToken(beforeSignOut);
    UUID withdrawn = UUID.randomUUID();
    given(userRepository.findById(withdrawn)).willReturn(Optional.empty());
    expectInvalidRefreshToken(refreshTokenCodec.issue(withdrawn, 0, Instant.now()));
    verifyNoInteractions(jwtEncoder);
  }

  @Test
  void refreshRejectsMissingForgedExpiredOrAccessTokens() {
    var otherKey =
        new SecretKeySpec(
            "another-signing-key-that-users-must-reject-32b".getBytes(StandardCharsets.UTF_8),
            "HmacSHA256");
    String forged =
        new RefreshTokenCodec(otherKey, Duration.ofDays(14)).issue(USER_ID, 0, Instant.now());
    String expired = refreshTokenCodec.issue(USER_ID, 0, Instant.now().minus(Duration.ofDays(15)));

    for (String token : new String[] {null, "", "not-a-jwt", forged, expired}) {
      expectInvalidRefreshToken(token);
    }
    verifyNoInteractions(userRepository, jwtEncoder);
  }

  private void expectInvalidRefreshToken(String token) {
    assertThatThrownBy(() -> userService.refresh(token))
        .isInstanceOfSatisfying(
            UserException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(UserErrorCode.INVALID_REFRESH_TOKEN));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void authorizationReflectsWhetherTheUserIsCurrentlyActive(boolean active) {
    User user = regularUser();
    ReflectionTestUtils.setField(user, "id", USER_ID);
    given(userRepository.findById(USER_ID))
        .willReturn(active ? Optional.of(user) : Optional.empty());

    var authorization = userService.getAuthorization(USER_ID);

    assertThat(authorization.userId()).isEqualTo(USER_ID);
    assertThat(authorization.active()).isEqualTo(active);
    assertThat(authorization.username()).isEqualTo(active ? "user01" : null);
    assertThat(authorization.role()).isEqualTo(active ? UserRoleEnum.USER : null);
    assertThat(authorization.isDeliveryStaff()).isFalse();
    assertThat(authorization.tokenVersion()).isZero();
  }

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
    given(userRepository.findByIdForUpdate(USER_ID)).willReturn(Optional.of(user));

    userService.withdraw(USER_ID, "user01");

    assertThat(user.getDeletedAt()).isEqualTo(NOW);
    assertThat(user.getDeletedBy()).isEqualTo("user01");
    verify(userRepository, never()).delete(any(User.class));
  }

  @Test
  void signOutRaisesTokenVersionThatNewTokensCarry() {
    User user = regularUser();
    ReflectionTestUtils.setField(user, "id", USER_ID);
    given(userRepository.findByIdForUpdate(USER_ID)).willReturn(Optional.of(user));
    given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

    userService.signOut(USER_ID);
    userService.signOut(USER_ID);

    assertThat(user.getTokenVersion()).isEqualTo(2);
    assertThat(userService.getAuthorization(USER_ID).tokenVersion()).isEqualTo(2);
  }

  @Test
  void signOutOfMissingUserIsNotFound() {
    given(userRepository.findByIdForUpdate(USER_ID)).willReturn(Optional.empty());

    assertThatThrownBy(() -> userService.signOut(USER_ID))
        .isInstanceOfSatisfying(
            UserException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(UserErrorCode.USER_NOT_FOUND));
  }

  @Test
  void deleteRecordsMasterAsActor() {
    User user = regularUser();
    given(userRepository.findByIdForUpdate(USER_ID)).willReturn(Optional.of(user));

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
    given(userRepository.findByIdForUpdate(USER_ID)).willReturn(Optional.of(user));
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
    given(userRepository.findByIdForUpdate(USER_ID)).willReturn(Optional.of(user));

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

  private UserRegisterRequestDto signupRequest(boolean company) {
    return new UserRegisterRequestDto(
        "user01",
        "사용자",
        "Passw0rd!",
        UserRoleEnum.USER,
        "slack",
        company ? "  물류업체  " : null,
        company ? null : "  서울 허브  ");
  }

  private void affiliationMatches(boolean company, int count) {
    if (company) {
      var match = new CompanyLookupClient.CompanyLookupResponse(COMPANY_ID, "물류업체", HUB_ID);
      List<CompanyLookupClient.CompanyLookupResponse> matches =
          switch (count) {
            case 0 -> List.of();
            case 1 -> List.of(match);
            default ->
                List.of(
                    match,
                    new CompanyLookupClient.CompanyLookupResponse(
                        UUID.randomUUID(), "물류업체", HUB_ID));
          };
      given(companyLookupClient.findByName("물류업체")).willReturn(new ApiResponse<>(matches));
    } else {
      var match = new HubLookupClient.HubLookupResponse(HUB_ID, "서울 허브");
      List<HubLookupClient.HubLookupResponse> matches =
          switch (count) {
            case 0 -> List.of();
            case 1 -> List.of(match);
            default ->
                List.of(match, new HubLookupClient.HubLookupResponse(UUID.randomUUID(), "서울 허브"));
          };
      given(hubLookupClient.findByName("서울 허브")).willReturn(new ApiResponse<>(matches));
    }
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
