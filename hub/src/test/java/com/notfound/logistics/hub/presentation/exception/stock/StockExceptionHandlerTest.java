package com.notfound.logistics.hub.presentation.exception.stock;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class StockExceptionHandlerTest {
  private final StockExceptionHandler handler = new StockExceptionHandler();

  @Test
  void onlyStockKeyConflictReturnsDuplicateError() {
    var duplicate =
        new DataIntegrityViolationException(
            "database detail",
            new ConstraintViolationException(
                "database detail", new SQLException(), "uk_stocks_hub_product"));
    var response = handler.handleIntegrityViolation(duplicate);
    assertThat(response.getStatusCode().value()).isEqualTo(409);
    assertThat(response.getBody().code()).isEqualTo("STOCK_ALREADY_EXISTS");
    assertThat(response.getBody().message()).doesNotContain("database detail");
  }

  @Test
  void otherConstraintsRemainInternalErrors() {
    var other =
        new DataIntegrityViolationException(
            "database secret",
            new ConstraintViolationException(
                "database secret", new SQLException(), "another_constraint"));
    var response = handler.handleIntegrityViolation(other);
    assertThat(response.getStatusCode().value()).isEqualTo(500);
    assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
    assertThat(response.getBody().message()).doesNotContain("database secret");
  }
}
