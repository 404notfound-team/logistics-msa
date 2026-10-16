package com.notfound.logistics.company.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.willThrow;

import com.notfound.logistics.company.domain.exception.CompanyError;
import com.notfound.logistics.company.domain.exception.CompanyException;
import feign.FeignException;
import feign.Request;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FeignHubVerifierTest {
  private static final UUID HUB_ID = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");

  @Mock private HubClient hubClient;
  @InjectMocks private FeignHubVerifier verifier;

  @Test
  void existingHubPasses() {
    assertThatCode(() -> verifier.verifyExists(HUB_ID)).doesNotThrowAnyException();
  }

  @Test
  void missingHubIsHubNotFound() {
    willThrow(failure(404)).given(hubClient).getHub(HUB_ID);

    assertThatThrownBy(() -> verifier.verifyExists(HUB_ID))
        .isInstanceOf(CompanyException.class)
        .extracting(e -> ((CompanyException) e).getError())
        .isEqualTo(CompanyError.HUB_NOT_FOUND);
  }

  // 인증 거절·서버 오류·장애는 요청자 잘못이 아니므로 모두 503으로 바꾼다.
  @ParameterizedTest
  @ValueSource(ints = {401, 403, 500, 503})
  void otherFailuresAreHubUnavailable(int status) {
    willThrow(failure(status)).given(hubClient).getHub(HUB_ID);

    assertThatThrownBy(() -> verifier.verifyExists(HUB_ID))
        .isInstanceOf(CompanyException.class)
        .extracting(e -> ((CompanyException) e).getError())
        .isEqualTo(CompanyError.HUB_UNAVAILABLE);
  }

  private static FeignException failure(int status) {
    Request request =
        Request.create(
            Request.HttpMethod.GET,
            "/internal/hubs/" + HUB_ID,
            Map.of(),
            null,
            StandardCharsets.UTF_8,
            null);
    return FeignException.errorStatus(
        "HubClient#getHub",
        feign.Response.builder().status(status).request(request).headers(Map.of()).build());
  }
}
