package com.notfound.logistics.e2e;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class RecoveryCleanupTest {
  @Test
  void rollsBackAndClosesOnlyItsConnectionWhenScenarioFails() throws Exception {
    final var connection = mock(Connection.class);
    assertThatThrownBy(
            () -> {
              try (var delay = new ServiceDatabase.DelayedCommit(connection)) {
                throw new IllegalStateException("scenario failed");
              }
            })
        .hasMessage("scenario failed");
    verify(connection).rollback();
    verify(connection).close();
  }

  @Test
  void closesConnectionEvenWhenRollbackFails() throws Exception {
    final var connection = mock(Connection.class);
    doThrow(new SQLException("database unavailable")).when(connection).rollback();
    assertThatThrownBy(() -> new ServiceDatabase.DelayedCommit(connection).close())
        .isInstanceOf(SQLException.class);
    verify(connection).close();
  }

  @Test
  void restartsOrdersAfterFailureWhileStopped() throws Exception {
    final var stack = mock(ComposeStack.class);
    assertThatThrownBy(
            () -> {
              try (var restart = new ComposeStack.OrdersRestart(stack)) {
                restart.stop();
                throw new IllegalStateException("commit check failed");
              }
            })
        .hasMessage("commit check failed");
    verify(stack).startOrders();
  }

  @Test
  void attemptsRestartEvenWhenStopCommandFailsAfterAffectingContainer() throws Exception {
    final var stack = mock(ComposeStack.class);
    doThrow(new IOException("stop timed out")).when(stack).command("stop", "-t", "10", "orders");
    assertThatThrownBy(
            () -> {
              try (var restart = new ComposeStack.OrdersRestart(stack)) {
                restart.stop();
              }
            })
        .hasMessage("stop timed out");
    verify(stack).startOrders();
  }

  @Test
  void doesNotRestartUntouchedOrAlreadyStartedOrders() throws Exception {
    final var stack = mock(ComposeStack.class);
    try (var untouched = new ComposeStack.OrdersRestart(stack)) {
      verifyNoInteractions(stack);
    }
    verifyNoInteractions(stack);
    try (var restart = new ComposeStack.OrdersRestart(stack)) {
      restart.stop();
      restart.start();
    }
    verify(stack, times(1)).startOrders();
  }
}
