package com.notfound.logistics.hub.infra.audit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class HubAuditorAwareTest {

  @Test
  void failsClosedUntilVerifiedActorIntegrationExists() {
    HubAuditorAware auditorAware = new HubAuditorAware();

    assertThatThrownBy(auditorAware::getCurrentAuditor)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("감사 주체 연동");
  }
}
