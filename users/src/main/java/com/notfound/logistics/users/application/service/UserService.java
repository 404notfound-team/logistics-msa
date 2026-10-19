package com.notfound.logistics.users.application.service;

import com.notfound.logistics.common.api.PageResponse;
import com.notfound.logistics.users.application.dto.UserIdentity;
import com.notfound.logistics.users.domain.exception.UserException;
import com.notfound.logistics.users.domain.model.User;
import com.notfound.logistics.users.domain.model.UserRoleEnum;
import com.notfound.logistics.users.domain.repository.UserRepository;
import com.notfound.logistics.users.domain.repository.UserSearchCondition;
import com.notfound.logistics.users.domain.repository.UserSpecifications;
import com.notfound.logistics.users.infrastructure.audit.SignupAuditScope;
import com.notfound.logistics.users.infrastructure.client.CompanyLookupClient;
import com.notfound.logistics.users.infrastructure.client.HubLookupClient;
import com.notfound.logistics.users.presentation.dto.UserAuthorizationResponseDto;
import com.notfound.logistics.users.presentation.dto.UserCreateRequestDto;
import com.notfound.logistics.users.presentation.dto.UserRegisterRequestDto;
import com.notfound.logistics.users.presentation.dto.UserRegisterResponseDto;
import com.notfound.logistics.users.presentation.dto.UserResponseDto;
import com.notfound.logistics.users.presentation.dto.UserSignInRequestDto;
import com.notfound.logistics.users.presentation.dto.UserSignInResponseDto;
import com.notfound.logistics.users.presentation.dto.UserUpdateRequestDto;
import com.notfound.logistics.users.presentation.exception.UserErrorCode;
import feign.FeignException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {
  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final CompanyLookupClient companyLookupClient;
  private final HubLookupClient hubLookupClient;
  private final JwtEncoder jwtEncoder;
  private final Clock clock;

  @Value("${users.jwt.access-token-ttl:15m}")
  private Duration accessTokenTtl;

  public UserRegisterResponseDto register(UserRegisterRequestDto request) {
    if (userRepository.existsByUsernameIncludingDeleted(request.username())) {
      throw new UserException(UserErrorCode.USERNAME_ALREADY_EXISTS);
    }

    UUID companyId = null;
    UUID hubId = null;
    if (request.companyName() != null && !request.companyName().isBlank()) {
      String companyName = request.companyName().trim();
      List<CompanyLookupClient.CompanyLookupResponse> matches;
      try {
        matches = companyLookupClient.findByName(companyName).data();
      } catch (FeignException exception) {
        throw new UserException(UserErrorCode.AFFILIATION_LOOKUP_UNAVAILABLE);
      }
      if (matches == null || matches.isEmpty()) {
        throw new UserException(UserErrorCode.ENTITY_NOT_FOUND);
      }
      if (matches.size() > 1) {
        throw new UserException(UserErrorCode.AFFILIATION_AMBIGUOUS);
      }
      companyId = matches.get(0).id();
    } else {
      String hubName = request.hubName().trim();
      List<HubLookupClient.HubLookupResponse> matches;
      try {
        matches = hubLookupClient.findByName(hubName).data();
      } catch (FeignException exception) {
        throw new UserException(UserErrorCode.AFFILIATION_LOOKUP_UNAVAILABLE);
      }
      if (matches == null || matches.isEmpty()) {
        throw new UserException(UserErrorCode.ENTITY_NOT_FOUND);
      }
      if (matches.size() > 1) {
        throw new UserException(UserErrorCode.AFFILIATION_AMBIGUOUS);
      }
      hubId = matches.get(0).id();
    }

    User user =
        User.signUp(
            request.username(),
            request.name(),
            passwordEncoder.encode(request.password()),
            request.slackId(),
            request.role(),
            companyId,
            hubId);
    try {
      User saved = SignupAuditScope.run(() -> userRepository.save(user));
      return new UserRegisterResponseDto(saved.getId());
    } catch (DataIntegrityViolationException exception) {
      throw new UserException(UserErrorCode.USERNAME_ALREADY_EXISTS);
    }
  }

  public UserSignInResponseDto signIn(UserSignInRequestDto request) {
    User user =
        userRepository
            .findByUsername(request.username())
            .orElseThrow(() -> new UserException(UserErrorCode.INVALID_CREDENTIALS));
    if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
      throw new UserException(UserErrorCode.INVALID_CREDENTIALS);
    }

    Instant issuedAt = Instant.now();
    Instant expiresAt = issuedAt.plus(accessTokenTtl);
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer("logistics-users")
            .subject(user.getUsername())
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .claim("user_id", user.getId().toString())
            .claim("role", user.getRole().name())
            .claim("is_delivery_staff", user.isDeliveryStaff())
            .build();
    String token =
        jwtEncoder
            .encode(
                JwtEncoderParameters.from(
                    JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims))
            .getTokenValue();
    return new UserSignInResponseDto(
        token, "Bearer", Duration.between(issuedAt, expiresAt).toSeconds());
  }

  public UserAuthorizationResponseDto getAuthorization(UUID id) {
    return userRepository
        .findById(id)
        .map(
            user ->
                new UserAuthorizationResponseDto(
                    user.getId(), user.getUsername(), true, user.getRole(), user.isDeliveryStaff()))
        .orElseGet(() -> new UserAuthorizationResponseDto(id, null, false, null, false));
  }

  /** 주문자와 선택적인 배송 담당자만 조회하며 삭제된 사용자는 제외합니다. */
  @Transactional(readOnly = true)
  public List<UserIdentity> getIdentities(String username, UUID assigneeId) {
    var identities = new LinkedHashMap<UUID, UserIdentity>();
    userRepository
        .findByUsername(username)
        .ifPresent(user -> identities.put(user.getId(), UserIdentity.from(user)));
    if (assigneeId != null && !identities.containsKey(assigneeId)) {
      userRepository
          .findById(assigneeId)
          .ifPresent(user -> identities.put(user.getId(), UserIdentity.from(user)));
    }
    return List.copyOf(identities.values());
  }

  @Transactional(readOnly = true)
  public PageResponse<UserResponseDto> getUsers(UserSearchCondition condition, Pageable pageable) {
    Page<User> users = userRepository.findAll(UserSpecifications.matching(condition), pageable);
    return new PageResponse<>(
        users.getContent().stream().map(UserResponseDto::from).toList(),
        users.getNumber(),
        users.getSize(),
        users.getTotalElements());
  }

  @Transactional(readOnly = true)
  public UserResponseDto getUser(UUID id) {
    return UserResponseDto.from(findUser(id));
  }

  /** 회원 탈퇴입니다. 대상은 JWT의 user_id, 삭제 작업자는 JWT의 sub(username)입니다. */
  @Transactional
  public void withdraw(UUID userId, String actorUsername) {
    findUserForUpdate(userId).markDeleted(actorUsername, clock.instant());
  }

  @Transactional
  public UserResponseDto createUser(UserCreateRequestDto request) {
    if (!User.isValidAffiliation(request.role(), request.companyId(), request.hubId())) {
      throw new UserException(UserErrorCode.INVALID_AFFILIATION);
    }
    if (userRepository.existsByUsernameIncludingDeleted(request.username())) {
      throw new UserException(UserErrorCode.USERNAME_ALREADY_EXISTS);
    }
    verifyAffiliationExists(request.companyId(), request.hubId());

    User user =
        User.create(
            request.username(),
            request.name(),
            passwordEncoder.encode(request.password()),
            request.slackId(),
            request.role(),
            request.companyId(),
            request.hubId(),
            Boolean.TRUE.equals(request.isDeliveryStaff()));
    try {
      return UserResponseDto.from(userRepository.saveAndFlush(user));
    } catch (DataIntegrityViolationException exception) {
      throw new UserException(UserErrorCode.USERNAME_ALREADY_EXISTS);
    }
  }

  @Transactional
  public UserResponseDto updateUser(UUID id, UserUpdateRequestDto request) {
    User user = findUserForUpdate(id);
    UserRoleEnum role = request.role() != null ? request.role() : user.getRole();
    UUID companyId = request.changesAffiliation() ? request.companyId() : user.getCompanyId();
    UUID hubId = request.changesAffiliation() ? request.hubId() : user.getHubId();
    if (!User.isValidAffiliation(role, companyId, hubId)) {
      throw new UserException(UserErrorCode.INVALID_AFFILIATION);
    }
    if (request.changesAffiliation()) {
      verifyAffiliationExists(companyId, hubId);
    }

    user.update(
        request.name() != null ? request.name() : user.getName(),
        request.slackId() != null ? request.slackId() : user.getSlackId(),
        request.password() != null
            ? passwordEncoder.encode(request.password())
            : user.getPasswordHash(),
        role,
        request.isDeliveryStaff() != null ? request.isDeliveryStaff() : user.isDeliveryStaff(),
        companyId,
        hubId);
    // 응답의 updated_at·updated_by가 이번 변경을 반영하도록 감사 필드를 먼저 기록합니다.
    userRepository.flush();
    return UserResponseDto.from(user);
  }

  @Transactional
  public void deleteUser(UUID id, String actorUsername) {
    findUserForUpdate(id).markDeleted(actorUsername, clock.instant());
  }

  private User findUser(UUID id) {
    return userRepository
        .findById(id)
        .orElseThrow(() -> new UserException(UserErrorCode.USER_NOT_FOUND));
  }

  private User findUserForUpdate(UUID id) {
    return userRepository
        .findByIdForUpdate(id)
        .orElseThrow(() -> new UserException(UserErrorCode.USER_NOT_FOUND));
  }

  private void verifyAffiliationExists(UUID companyId, UUID hubId) {
    try {
      if (companyId != null) {
        companyLookupClient.findById(companyId);
      } else if (hubId != null) {
        hubLookupClient.findById(hubId);
      }
    } catch (FeignException.NotFound exception) {
      throw new UserException(UserErrorCode.ENTITY_NOT_FOUND);
    } catch (FeignException exception) {
      throw new UserException(UserErrorCode.AFFILIATION_LOOKUP_UNAVAILABLE);
    }
  }
}
