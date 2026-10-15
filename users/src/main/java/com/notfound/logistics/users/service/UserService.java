package com.notfound.logistics.users.service;

import com.notfound.logistics.users.dto.UserRegisterRequestDto;
import com.notfound.logistics.users.dto.UserRegisterResponseDto;
import com.notfound.logistics.users.entity.User;
import com.notfound.logistics.users.exception.UserErrorCode;
import com.notfound.logistics.users.exception.UserException;
import com.notfound.logistics.users.infra.CompanyLookupClient;
import com.notfound.logistics.users.infra.HubLookupClient;
import com.notfound.logistics.users.repository.UserRepository;
import feign.FeignException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserService {
  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final CompanyLookupClient companyLookupClient;
  private final HubLookupClient hubLookupClient;

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
}
