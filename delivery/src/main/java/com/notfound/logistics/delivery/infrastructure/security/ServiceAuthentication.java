package com.notfound.logistics.delivery.infrastructure.security;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/** X-Service-Token으로 인증된 호출 서비스와, 함께 전달된 원 요청자. */
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

  /** 호출 서비스가 X-Actor-Username을 보내지 않았으면 null. */
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
