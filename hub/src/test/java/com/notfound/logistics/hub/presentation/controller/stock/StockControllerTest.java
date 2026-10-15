package com.notfound.logistics.hub.presentation.controller.stock;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.notfound.logistics.hub.application.dto.stock.StockResult;
import com.notfound.logistics.hub.application.service.stock.StockCommandService;
import com.notfound.logistics.hub.application.service.stock.StockQueryService;
import com.notfound.logistics.hub.domain.exception.hub.HubBusinessException;
import com.notfound.logistics.hub.domain.exception.hub.HubErrorCode;
import com.notfound.logistics.hub.domain.exception.stock.StockBusinessException;
import com.notfound.logistics.hub.domain.exception.stock.StockErrorCode;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({StockController.class, InternalStockController.class})
class StockControllerTest {
  @Autowired private MockMvc mvc;
  @MockitoBean private StockCommandService command;
  @MockitoBean private StockQueryService query;
  private final UUID hubId = UUID.randomUUID();
  private final UUID productId = UUID.randomUUID();
  private final UUID stockId = UUID.randomUUID();

  private String creationBody(long quantity) {
    return creationBody(Long.toString(quantity));
  }

  private String creationBody(String quantity) {
    return "{\"hub_id\":\""
        + hubId
        + "\",\"product_id\":\""
        + productId
        + "\",\"quantity\":"
        + quantity
        + "}";
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "1.9",
        "-0.9",
        "1.0",
        "1e3",
        "\"10\"",
        "\"1.9\"",
        "\"\"",
        "true",
        "[]",
        "{}",
        "null",
        "9223372036854775808",
        "-9223372036854775809",
        "-9223372036854775808"
      })
  void rejectsCoercedOrInvalidQuantitiesWithoutCallingService(String quantity) throws Exception {
    mvc.perform(
            post("/api/stocks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(creationBody(quantity)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    verifyNoInteractions(command);
  }

  @ParameterizedTest
  @ValueSource(longs = {0, 1, Long.MAX_VALUE})
  void acceptsIntegerQuantityBoundariesWithoutLosingPrecision(long quantity) throws Exception {
    when(command.create(hubId, productId, quantity))
        .thenReturn(new StockResult(stockId, hubId, productId, quantity));
    mvc.perform(
            post("/api/stocks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(creationBody(quantity)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.quantity").value(quantity));
    verify(command).create(hubId, productId, quantity);
  }

  @Test
  void concurrentDatabaseDuplicateReturnsConflictWithoutAdapter() throws Exception {
    when(command.create(hubId, productId, 1))
        .thenThrow(
            new DataIntegrityViolationException(
                "duplicate",
                new ConstraintViolationException(
                    "duplicate", new SQLException(), "uk_stocks_hub_product")));
    mvc.perform(
            post("/api/stocks").contentType(MediaType.APPLICATION_JSON).content(creationBody(1)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("STOCK_ALREADY_EXISTS"));
  }

  @Test
  void createsThroughExternalContract() throws Exception {
    when(command.create(hubId, productId, 0))
        .thenReturn(new StockResult(stockId, hubId, productId, 0));
    mvc.perform(
            post("/api/stocks").contentType(MediaType.APPLICATION_JSON).content(creationBody(0)))
        .andExpect(status().isCreated())
        .andExpect(
            header()
                .string("Location", "/api/hubs/" + hubId + "/products/" + productId + "/stocks"))
        .andExpect(jsonPath("$.data.id").value(stockId.toString()))
        .andExpect(jsonPath("$.data.hub_id").value(hubId.toString()));
    verify(command).create(hubId, productId, 0);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"quantity\":1}",
        "{\"product_id\":\"00000000-0000-0000-0000-000000000001\",\"quantity\":1}",
        "{\"hub_id\":\"00000000-0000-0000-0000-000000000001\",\"quantity\":1}",
        "{\"hub_id\":\"00000000-0000-0000-0000-000000000001\",\"product_id\":\"00000000-0000-0000-0000-000000000001\"}",
        "{\"hub_id\":\"bad\",\"product_id\":\"00000000-0000-0000-0000-000000000001\",\"quantity\":1}",
        "{\"hub_id\":\"00000000-0000-0000-0000-000000000001\",\"product_id\":\"bad\",\"quantity\":1}",
        "{\"hub_id\":\"00000000-0000-0000-0000-000000000001\",\"product_id\":\"00000000-0000-0000-0000-000000000001\",\"quantity\":-1}",
        "{\"hub_id\":\"00000000-0000-0000-0000-000000000001\",\"product_id\":\"00000000-0000-0000-0000-000000000001\",\"quantity\":9223372036854775808}"
      })
  void validatesCreationBody(String body) throws Exception {
    mvc.perform(post("/api/stocks").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    verifyNoInteractions(command);
  }

  @Test
  void handlesHubAndStockErrorsUsingSharedContract() throws Exception {
    when(command.create(hubId, productId, 1))
        .thenThrow(new HubBusinessException(HubErrorCode.HUB_NOT_FOUND));
    mvc.perform(
            post("/api/stocks").contentType(MediaType.APPLICATION_JSON).content(creationBody(1)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("HUB_NOT_FOUND"));
    doThrow(new StockBusinessException(StockErrorCode.STOCK_ALREADY_EXISTS))
        .when(command)
        .create(hubId, productId, 1);
    mvc.perform(
            post("/api/stocks").contentType(MediaType.APPLICATION_JSON).content(creationBody(1)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("STOCK_ALREADY_EXISTS"));
  }

  @Test
  void externalAndInternalDetailsUseHubAndProductIdentity() throws Exception {
    when(query.getStock(hubId, productId))
        .thenReturn(new StockResult(stockId, hubId, productId, 123));
    mvc.perform(get("/api/hubs/{hubId}/products/{productId}/stocks", hubId, productId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.quantity").value(123));
    mvc.perform(get("/internal/hubs/{hubId}/stocks/{productId}", hubId, productId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.product_id").value(productId.toString()));
    when(query.getStock(hubId, productId))
        .thenThrow(new StockBusinessException(StockErrorCode.STOCK_NOT_FOUND));
    mvc.perform(get("/api/hubs/{hubId}/products/{productId}/stocks", hubId, productId))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("STOCK_NOT_FOUND"));
  }

  @Test
  void listsHubAndProductStocksWithCorrectFilters() throws Exception {
    var result =
        new PageImpl<>(
            List.of(new StockResult(stockId, hubId, productId, 10)), PageRequest.of(0, 20), 1);
    when(query.getStocks(hubId, null, 0, 20)).thenReturn(result);
    when(query.getStocks(null, productId, 0, 20)).thenReturn(result);
    mvc.perform(get("/api/hubs/{hubId}/stocks", hubId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total_elements").value(1));
    mvc.perform(get("/api/products/{productId}/stocks", productId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].quantity").value(10));
    verify(query).getStocks(hubId, null, 0, 20);
    verify(query).getStocks(null, productId, 0, 20);
  }

  @ParameterizedTest
  @CsvSource({
    "page,-1",
    "page,-2147483648",
    "page,2147483648",
    "page,9223372036854775808",
    "page,abc",
    "page,1.5",
    "size,-1",
    "size,0",
    "size,101",
    "size,2147483648",
    "size,1.5",
    "size,abc"
  })
  void rejectsInvalidListParameters(String name, String value) throws Exception {
    mvc.perform(get("/api/hubs/{hubId}/stocks", hubId).param(name, value))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    mvc.perform(get("/api/products/{productId}/stocks", productId).param(name, value))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    verifyNoInteractions(query);
  }

  @Test
  void internalCreationIsNoLongerExposed() throws Exception {
    mvc.perform(
            post("/internal/hubs/{hubId}/stocks", hubId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(creationBody(1)))
        .andExpect(status().isNotFound());
    verifyNoInteractions(command);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/hubs/invalid/stocks",
        "/api/products/invalid/stocks",
        "/api/hubs/invalid/products/00000000-0000-0000-0000-000000000001/stocks",
        "/internal/hubs/00000000-0000-0000-0000-000000000001/stocks/invalid"
      })
  void rejectsInvalidPathIdentifiers(String path) throws Exception {
    mvc.perform(get(path))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    verifyNoInteractions(query);
  }

  @ParameterizedTest
  @CsvSource({
    "0,1",
    "0,100",
    "2174222,20",
    "2174222,100",
    "21474836,100",
    "21474837,100",
    "2147483647,100"
  })
  void bothListEndpointsAcceptPaginationBoundaries(int page, int size) throws Exception {
    var empty = new PageImpl<StockResult>(List.of(), PageRequest.of(page, size), 0);
    when(query.getStocks(hubId, null, page, size)).thenReturn(empty);
    when(query.getStocks(null, productId, page, size)).thenReturn(empty);
    mvc.perform(
            get("/api/hubs/{hubId}/stocks", hubId)
                .param("page", String.valueOf(page))
                .param("size", String.valueOf(size)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content").isEmpty())
        .andExpect(jsonPath("$.data.page").value(page));
    mvc.perform(
            get("/api/products/{productId}/stocks", productId)
                .param("page", String.valueOf(page))
                .param("size", String.valueOf(size)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content").isEmpty())
        .andExpect(jsonPath("$.data.size").value(size));
  }
}
