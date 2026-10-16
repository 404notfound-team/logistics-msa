package com.notfound.logistics.hub.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.notfound.logistics.hub.support.HubSecurityTestTokens;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HubSecurityConfigurationTest {
  @Test
  void configuredJwtSecretMustContainAtLeast32Utf8Bytes() {
    assertThatThrownBy(() -> new HubSecurityConfiguration().hubJwtDecoder("too-short"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void serviceSecretsMustIdentifyDistinctCallersAndMeetMinimumLength() {
    assertThatThrownBy(() -> new HubServiceAuthenticationFilter(Map.of("ORDERS", "short"), null))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                new HubServiceAuthenticationFilter(
                    Map.of(
                        "ORDERS",
                        HubSecurityTestTokens.ORDERS,
                        "USERS",
                        HubSecurityTestTokens.ORDERS),
                    null))
        .isInstanceOf(IllegalStateException.class);
  }
}
