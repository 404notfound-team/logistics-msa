package com.notfound.logistics.delivery.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeliveryRouteTest {
  private static final BigDecimal DISTANCE = new BigDecimal("10.5");
  private static final long DURATION = 600L;

  @Test
  void sequenceStartsAtOne() {
    assertThat(route(1, DISTANCE, DURATION).getSequence()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
  void sequenceBelowOneIsRejected(int sequence) {
    assertThatThrownBy(() -> route(sequence, DISTANCE, DURATION))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sequence");
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "0.000", "0.001"})
  void zeroOrPositiveExpectedDistanceIsAccepted(String distance) {
    assertThat(route(1, new BigDecimal(distance), DURATION).getExpectedDistanceKm())
        .isEqualByComparingTo(new BigDecimal(distance));
  }

  @ParameterizedTest
  @ValueSource(strings = {"-0.001", "-1"})
  void negativeExpectedDistanceIsRejected(String distance) {
    assertThatThrownBy(() -> route(1, new BigDecimal(distance), DURATION))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("expectedDistanceKm");
  }

  @ParameterizedTest
  @ValueSource(longs = {0L, 1L})
  void zeroOrPositiveExpectedDurationIsAccepted(long duration) {
    assertThat(route(1, DISTANCE, duration).getExpectedDurationSeconds()).isEqualTo(duration);
  }

  @ParameterizedTest
  @ValueSource(longs = {-1L, Long.MIN_VALUE})
  void negativeExpectedDurationIsRejected(long duration) {
    assertThatThrownBy(() -> route(1, DISTANCE, duration))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("expectedDurationSeconds");
  }

  private static DeliveryRoute route(int sequence, BigDecimal distanceKm, long durationSeconds) {
    return new DeliveryRoute(
        UUID.randomUUID(),
        mock(Delivery.class),
        sequence,
        UUID.randomUUID(),
        UUID.randomUUID(),
        distanceKm,
        durationSeconds,
        DeliveryRouteStatus.WAITING,
        null);
  }
}
