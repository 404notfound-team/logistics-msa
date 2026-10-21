package com.notfound.logistics.company.infrastructure.client;

import com.notfound.logistics.company.application.service.HubVerifier;
import com.notfound.logistics.company.domain.exception.CompanyError;
import com.notfound.logistics.company.domain.exception.CompanyException;
import feign.FeignException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
class FeignHubVerifier implements HubVerifier {
  private final HubClient hubClient;

  @Override
  public void verifyExists(UUID hubId) {
    try {
      hubClient.getHub(hubId);
    } catch (FeignException.NotFound e) {
      throw new CompanyException(CompanyError.HUB_NOT_FOUND);
    } catch (FeignException e) {
      // 인증 거절(401·403)도 company 설정 문제라 요청자 잘못이 아니므로 503으로 응답한다.
      log.warn("허브 확인 실패: status={}", e.status());
      throw new CompanyException(CompanyError.HUB_UNAVAILABLE);
    }
  }
}
