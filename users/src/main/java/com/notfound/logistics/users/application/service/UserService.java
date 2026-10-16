package com.notfound.logistics.users.application.service;

import com.notfound.logistics.users.domain.exception.UserException;
import com.notfound.logistics.users.domain.model.User;
import com.notfound.logistics.users.domain.repository.UserRepository;
import com.notfound.logistics.users.infrastructure.client.CompanyLookupClient;
import com.notfound.logistics.users.infrastructure.client.HubLookupClient;
import com.notfound.logistics.users.presentation.dto.UserRegisterRequestDto;
import com.notfound.logistics.users.presentation.dto.UserRegisterResponseDto;
import com.notfound.logistics.users.presentation.dto.UserSignInRequestDto;
import com.notfound.logistics.users.presentation.dto.UserSignInResponseDto;
import com.notfound.logistics.users.presentation.exception.UserErrorCode;
import feign.FeignException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserService {
  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final CompanyLookupClient companyLookupClient;
  private final HubLookupClient hubLookupClient;
  private final JwtEncoder jwtEncoder;

  @Value("${users.jwt.access-token-ttl:15m}")
  private Duration accessTokenTtl;

  public UserRegisterResponseDto register(UserRegisterRequestDto request) {
    if (userRepository.existsByUsername(request.username())) {
      throw new UserException(UserErrorCode.USERNAME_ALREADY_EXISTS);
    }

    UUID companyId = null;
    UUID hubId = null;
    if (request.companyName() != null && !request.companyName().isBlank()) {
      List<CompanyLookupClient.CompanyLookupResponse> matches;
      try {
        matches = companyLookupClient.findByName(request.companyName());
      } catch (FeignException exception) {
        throw new UserException(UserErrorCode.AFFILIATION_SERVICE_UNAVAILABLE);
      }
      if (matches == null || matches.isEmpty()) {
        throw new UserException(UserErrorCode.AFFILIATION_NOT_FOUND);
      }
      if (matches.size() > 1) {
        throw new UserException(UserErrorCode.AFFILIATION_NAME_AMBIGUOUS);
      }
      companyId = matches.get(0).id();
    } else {
      List<HubLookupClient.HubLookupResponse> matches;
      try {
        matches = hubLookupClient.findByName(request.hubName());
      } catch (FeignException exception) {
        throw new UserException(UserErrorCode.AFFILIATION_SERVICE_UNAVAILABLE);
      }
      if (matches == null || matches.isEmpty()) {
        throw new UserException(UserErrorCode.AFFILIATION_NOT_FOUND);
      }
      if (matches.size() > 1) {
        throw new UserException(UserErrorCode.AFFILIATION_NAME_AMBIGUOUS);
      }
      hubId = matches.get(0).id();
    }

    User user =
        User.signUp(
            request.username(),
            request.name(),
            passwordEncoder.encode(request.password()),
            request.slackId(),
            companyId,
            hubId);
    try {
      User saved = userRepository.save(user);
      return new UserRegisterResponseDto(saved.getId(), saved.getUsername());
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
            .claim("role", user.getRole().name())
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
}
