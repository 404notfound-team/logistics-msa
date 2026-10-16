package com.notfound.logistics.users.infrastructure.security;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/** X-Service-Token으로 인증된 호출 서비스와, 함께 전달된 원 요청자입니다. */
public class ServiceAuthentication extends AbstractAuthenticationToken {
  private final String serviceName;
  private final String actorUsername;

  public ServiceAuthentication(String serviceName, String actorUsername) {
    super(List.of());
    this.serviceName = serviceName;
    this.actorUsername = actorUsername;
    setAuthenticated(true);
  }

  public String getServiceName() {
    return serviceName;
  }

  /** 원 요청자가 없는 호출(예: 회원가입 중 조회)이면 null입니다. */
  public String getActorUsername() {
    return actorUsername;
  }

  @Override
  public Object getCredentials() {
    return "";
  }

  @Override
  public Object getPrincipal() {
    return serviceName;
  }
}
