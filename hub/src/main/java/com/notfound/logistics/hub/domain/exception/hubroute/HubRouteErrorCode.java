package com.notfound.logistics.hub.domain.exception.hubroute;

public enum HubRouteErrorCode {
  INVALID_HUB_ROUTE_DURATION("소요시간은 음수일 수 없습니다."),
  INVALID_HUB_ROUTE_DISTANCE("이동거리는 음수일 수 없습니다."),
  INVALID_SAME_HUB_ROUTE("출발 허브와 도착 허브가 같으면 소요시간과 이동거리는 0이어야 합니다."),
  HUB_ROUTE_NOT_FOUND("해당 허브간 이동 경로가 존재하지 않습니다.");

  private final String description;

  HubRouteErrorCode(String description) {
    this.description = description;
  }

  public String code() {
    return name();
  }

  public String message() {
    return description;
  }
}
