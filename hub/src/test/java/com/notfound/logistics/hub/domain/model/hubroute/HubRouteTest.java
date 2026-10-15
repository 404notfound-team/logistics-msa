package com.notfound.logistics.hub.domain.model.hubroute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.notfound.logistics.hub.domain.exception.hubroute.HubRouteBusinessException;
import com.notfound.logistics.hub.domain.exception.hubroute.HubRouteErrorCode;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HubRouteTest {
  @Test
  void createsNormalRouteWithIndependentIdentityAndUnchangedValues() {
    UUID from = UUID.randomUUID();
    UUID to = UUID.randomUUID();
    var route = HubRoute.create(from, to, Integer.MAX_VALUE, new BigDecimal("99999999.99"));
    assertThat(route.getId()).isNotNull();
    assertThat(route.getDepartureHubId()).isEqualTo(from);
    assertThat(route.getArrivalHubId()).isEqualTo(to);
    assertThat(route.getDurationMinutes()).isEqualTo(Integer.MAX_VALUE);
    assertThat(route.getDistanceKm()).isEqualByComparingTo("99999999.99");
    assertThat(route.isSameHubRoute()).isFalse();
    assertThat(HubRoute.create(from, to, 0, BigDecimal.ZERO).getId()).isNotEqualTo(route.getId());
  }

  @Test
  void rejectsMinimumIntegerDuration() {
    assertThatThrownBy(
            () ->
                HubRoute.create(
                    UUID.randomUUID(), UUID.randomUUID(), Integer.MIN_VALUE, BigDecimal.ZERO))
        .isInstanceOf(HubRouteBusinessException.class);
  }

  @Test
  void rejectsMissingDepartureArrivalAndDistance() {
    UUID id = UUID.randomUUID();
    assertThatThrownBy(() -> HubRoute.create(null, id, 0, BigDecimal.ZERO))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> HubRoute.create(id, null, 0, BigDecimal.ZERO))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> HubRoute.create(id, id, 0, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void rejectsNegativeDuration() {
    var exception =
        catchThrowableOfType(
            () -> HubRoute.create(UUID.randomUUID(), UUID.randomUUID(), -1, BigDecimal.ONE),
            HubRouteBusinessException.class);
    assertThat(exception.getErrorCode()).isEqualTo(HubRouteErrorCode.INVALID_HUB_ROUTE_DURATION);
  }

  @Test
  void rejectsNegativeDistance() {
    var exception =
        catchThrowableOfType(
            () -> HubRoute.create(UUID.randomUUID(), UUID.randomUUID(), 1, new BigDecimal("-0.01")),
            HubRouteBusinessException.class);
    assertThat(exception.getErrorCode()).isEqualTo(HubRouteErrorCode.INVALID_HUB_ROUTE_DISTANCE);
  }

  @Test
  void rejectsNonzeroDurationForSameHub() {
    UUID hubId = UUID.randomUUID();
    var exception =
        catchThrowableOfType(
            () -> HubRoute.create(hubId, hubId, 1, BigDecimal.ZERO),
            HubRouteBusinessException.class);
    assertThat(exception.getErrorCode()).isEqualTo(HubRouteErrorCode.INVALID_SAME_HUB_ROUTE);
  }

  @Test
  void rejectsNonzeroDistanceForSameHub() {
    UUID hubId = UUID.randomUUID();
    var exception =
        catchThrowableOfType(
            () -> HubRoute.create(hubId, hubId, 0, BigDecimal.ONE),
            HubRouteBusinessException.class);
    assertThat(exception.getErrorCode()).isEqualTo(HubRouteErrorCode.INVALID_SAME_HUB_ROUTE);
  }

  @Test
  void acceptsZeroDistanceAndDurationForSameHub() {
    UUID hubId = UUID.randomUUID();
    assertThat(HubRoute.create(hubId, hubId, 0, BigDecimal.ZERO).isSameHubRoute()).isTrue();
  }
}
