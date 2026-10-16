package com.notfound.logistics.company.application.service;

import java.util.UUID;

/** 업체가 속할 허브가 hub 서비스에 실제로 있는지 확인한다. 구현은 infrastructure.client에 있다. */
public interface HubVerifier {
  /**
   * 없거나 삭제된 허브면 CompanyError.HUB_NOT_FOUND, hub 서비스를 확인할 수 없으면 CompanyError.HUB_UNAVAILABLE로 실패한다.
   */
  void verifyExists(UUID hubId);
}
