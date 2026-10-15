package com.notfound.logistics.users.service;

import com.notfound.logistics.users.dto.UserRegisterRequestDto;
import com.notfound.logistics.users.dto.UserRegisterResponseDto;
import com.notfound.logistics.users.dto.UserSignInRequestDto;
import com.notfound.logistics.users.dto.UserSignInResponseDto;
import com.notfound.logistics.users.entity.User;
import com.notfound.logistics.users.exception.UserErrorCode;
import com.notfound.logistics.users.exception.UserException;
import com.notfound.logistics.users.infra.CompanyLookupClient;
import com.notfound.logistics.users.infra.HubLookupClient;
import com.notfound.logistics.users.repository.UserRepository;
import feign.FeignException;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.beans.factory.annotation.Value;
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

      UUID companyId = UUID.fromString("11111111-1111-1111-1111-111111111111");
      UUID hubId = null;

//      TODO
//    UUID companyId = null;
//    UUID hubId = null;
//    if (request.companyName() != null && !request.companyName().isBlank()) {
//      List<CompanyLookupClient.CompanyLookupResponse> matches;
//      try {
//        matches = companyLookupClient.findByName(request.companyName());
//      } catch (FeignException exception) {
//        throw new UserException(UserErrorCode.AFFILIATION_SERVICE_UNAVAILABLE);
//      }
//      if (matches == null || matches.isEmpty()) {
//        throw new UserException(UserErrorCode.AFFILIATION_NOT_FOUND);
//      }
//      if (matches.size() > 1) {
//        throw new UserException(UserErrorCode.AFFILIATION_NAME_AMBIGUOUS);
//      }
//      companyId = matches.get(0).id();
//    } else {
//      List<HubLookupClient.HubLookupResponse> matches;
//      try {
//        matches = hubLookupClient.findByName(request.hubName());
//      } catch (FeignException exception) {
//        throw new UserException(UserErrorCode.AFFILIATION_SERVICE_UNAVAILABLE);
//      }
//      if (matches == null || matches.isEmpty()) {
//        throw new UserException(UserErrorCode.AFFILIATION_NOT_FOUND);
//      }
//      if (matches.size() > 1) {
//        throw new UserException(UserErrorCode.AFFILIATION_NAME_AMBIGUOUS);
//      }
//      hubId = matches.get(0).id();
//    }

    User user = User.signUp(
        request.username(), request.name(), passwordEncoder.encode(request.password()),
        request.slackId(), companyId, hubId);
    try {
      User saved = userRepository.save(user);
      return new UserRegisterResponseDto(saved.getId(), saved.getUsername());
    } catch (DataIntegrityViolationException exception) {

      throw new UserException(UserErrorCode.USERNAME_ALREADY_EXISTS);
    }
  }

  public UserSignInResponseDto signIn(UserSignInRequestDto request) {
    User user = userRepository.findByUsername(request.username())
        .orElseThrow(() -> new UserException(UserErrorCode.INVALID_CREDENTIALS));
    if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
      throw new UserException(UserErrorCode.INVALID_CREDENTIALS);
    }

    Instant issuedAt = Instant.now();
    Instant expiresAt = issuedAt.plus(accessTokenTtl);
    JwtClaimsSet claims = JwtClaimsSet.builder()
        .issuer("logistics-users")
        .subject(user.getUsername())
        .issuedAt(issuedAt)
        .expiresAt(expiresAt)
        .claim("role", user.getRole().name())
        .build();
    String token = jwtEncoder.encode(JwtEncoderParameters.from(
        JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims)).getTokenValue();
    return new UserSignInResponseDto(token, "Bearer", Duration.between(issuedAt, expiresAt).toSeconds());
  }
}
