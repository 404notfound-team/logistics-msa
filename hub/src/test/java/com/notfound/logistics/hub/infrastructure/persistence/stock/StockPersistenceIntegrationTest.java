package com.notfound.logistics.hub.infrastructure.persistence.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.notfound.logistics.hub.HubApplication;
import com.notfound.logistics.hub.application.service.stock.StockCommandService;
import com.notfound.logistics.hub.application.service.stock.StockQueryService;
import com.notfound.logistics.hub.domain.exception.stock.StockBusinessException;
import com.notfound.logistics.hub.domain.model.stock.Stock;
import com.notfound.logistics.hub.domain.repository.stock.StockRepository;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@ActiveProfiles("local")
@SpringBootTest(
    classes = HubApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
      "eureka.client.enabled=false",
      "management.tracing.enabled=false",
      "springdoc.api-docs.enabled=false"
    })
@Testcontainers
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StockPersistenceIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> DATABASE =
      new PostgreSQLContainer<>("postgres:18")
          .withDatabaseName("hub_db")
          .withUsername("hub_test")
          .withPassword("hub-test-only")
          .withInitScript("db/hub-test-schema.sql");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
    registry.add("spring.datasource.username", DATABASE::getUsername);
    registry.add("spring.datasource.password", DATABASE::getPassword);
  }

  @Autowired private StockCommandService command;
  @Autowired private StockQueryService query;
  @Autowired private StockRepository stocks;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactions;
  @Autowired private EntityManager entities;
  @Autowired private MockMvc mvc;
  private final UUID hubId = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Test
  void httpRejectsFractionalQuantityAndStoresMaximumIntegerExactly() throws Exception {
    UUID product = UUID.randomUUID();
    String prefix =
        "{\"hub_id\":\"" + hubId + "\",\"product_id\":\"" + product + "\",\"quantity\":";
    mvc.perform(
            post("/api/stocks").contentType(MediaType.APPLICATION_JSON).content(prefix + "-0.9}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    assertThat(stocks.count()).isZero();
    mvc.perform(
            post("/api/stocks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(prefix + Long.MAX_VALUE + "}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.quantity").value(Long.MAX_VALUE));
    assertThat(query.getStock(hubId, product).quantity()).isEqualTo(Long.MAX_VALUE);
  }

  @Test
  void externalAndInternalHttpDetailsReturn404ForMissingOrDeletedStock() throws Exception {
    UUID product = UUID.randomUUID();
    String external = "/api/hubs/" + hubId + "/products/" + product + "/stocks";
    String internal = "/internal/hubs/" + hubId + "/stocks/" + product;
    for (String path : List.of(external, internal)) {
      mvc.perform(get(path))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value("STOCK_NOT_FOUND"));
    }
    var created = command.create(hubId, product, 1);
    for (String path : List.of(external, internal)) {
      mvc.perform(get(path))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.quantity").value(1));
    }
    jdbc.update(
        "UPDATE hub.p_stocks SET deleted_at = NOW(), deleted_by = 'stock-tester' WHERE id = ?",
        created.id());
    for (String path : List.of(external, internal)) {
      mvc.perform(get(path))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value("STOCK_NOT_FOUND"));
    }
  }

  @ParameterizedTest
  @CsvSource({
    "2174222,20",
    "2174222,100",
    "2147483646,1",
    "2147483647,1",
    "21474836,100",
    "21474837,100",
    "2147483647,100"
  })
  void realHttpAndDatabaseReturn200EmptyPageAndMatchingCountForHugePages(int page, int size)
      throws Exception {
    UUID product = UUID.randomUUID();
    command.create(hubId, product, 1);
    for (String path :
        List.of("/api/hubs/" + hubId + "/stocks", "/api/products/" + product + "/stocks")) {
      mvc.perform(
              get(path).param("page", Integer.toString(page)).param("size", Integer.toString(size)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content").isEmpty())
          .andExpect(jsonPath("$.data.page").value(page))
          .andExpect(jsonPath("$.data.size").value(size))
          .andExpect(jsonPath("$.data.total_elements").value(1));
    }
  }

  @ParameterizedTest
  @CsvSource({"false,false,3", "true,false,2", "false,true,2", "true,true,1"})
  void mixedDeletedAndActiveRowsUseSameSearchAndCountFilters(
      boolean byHub, boolean byProduct, int total) {
    UUID otherHub = UUID.fromString("00000000-0000-0000-0000-000000000002");
    UUID product = UUID.randomUUID();
    command.create(hubId, product, 1);
    command.create(otherHub, product, 2);
    command.create(hubId, UUID.randomUUID(), 3);
    var deleted = command.create(hubId, UUID.randomUUID(), 4);
    jdbc.update(
        "UPDATE hub.p_stocks SET deleted_at = NOW(), deleted_by = 'stock-tester' WHERE id = ?",
        deleted.id());
    UUID hub = byHub ? hubId : null;
    UUID item = byProduct ? product : null;
    List<UUID> seen = new ArrayList<>();
    for (int page = 0; page < total; page++) {
      var result = query.getStocks(hub, item, page, 1);
      assertThat(result.getTotalElements()).isEqualTo(total);
      assertThat(result.getContent()).hasSize(1);
      seen.add(result.getContent().getFirst().id());
    }
    assertThat(seen).hasSize(total).doesNotHaveDuplicates().doesNotContain(deleted.id());
    var beyond = query.getStocks(hub, item, total, 1);
    assertThat(beyond.getContent()).isEmpty();
    assertThat(beyond.getTotalElements()).isEqualTo(total);
    var huge = query.getStocks(hub, item, Integer.MAX_VALUE, 100);
    assertThat(huge.getContent()).isEmpty();
    assertThat(huge.getTotalElements()).isEqualTo(total);
  }

  @BeforeEach
  void prepare() {
    jdbc.update("DELETE FROM hub.p_stocks");
    authenticate();
  }

  private void authenticate() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken("stock-tester", null, List.of()));
  }

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void savesAuditMetadataAndReadsUsingHubProductIdentity() {
    UUID product = UUID.randomUUID();
    var result = command.create(hubId, product, Long.MAX_VALUE);
    assertThat(query.getStock(hubId, product)).isEqualTo(result);
    var row = jdbc.queryForMap("SELECT * FROM hub.p_stocks WHERE id = ?", result.id());
    assertThat(row.get("created_by")).isEqualTo("stock-tester");
    assertThat(row.get("updated_by")).isEqualTo("stock-tester");
    assertThat(row.get("created_at")).isNotNull();
    assertThat(row.get("updated_at")).isNotNull();
    assertThat(row.get("deleted_at")).isNull();
    assertThat(row.get("deleted_by")).isNull();
  }

  @Test
  void changingQuantityUpdatesAuditWithoutOverwritingCreator() {
    var result = command.create(hubId, UUID.randomUUID(), 10);
    var createdAt =
        jdbc.queryForObject(
            "SELECT created_at FROM hub.p_stocks WHERE id = ?", Timestamp.class, result.id());
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken("second-actor", null, List.of()));
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              entities.find(Stock.class, result.id()).add(5);
              entities.flush();
            });
    var row = jdbc.queryForMap("SELECT * FROM hub.p_stocks WHERE id = ?", result.id());
    assertThat(row.get("quantity")).isEqualTo(15L);
    assertThat(row.get("created_by")).isEqualTo("stock-tester");
    assertThat(row.get("created_at")).isEqualTo(createdAt);
    assertThat(row.get("updated_by")).isEqualTo("second-actor");
  }

  @Test
  void unauthenticatedWriteRollsBackWithoutInventingActor() {
    SecurityContextHolder.clearContext();
    assertThatThrownBy(() -> command.create(hubId, UUID.randomUUID(), 1))
        .isInstanceOf(RuntimeException.class);
    assertThat(stocks.count()).isZero();
  }

  @Test
  void deletedStockIsHiddenButItsKeyCannotBeRegisteredAgain() {
    UUID product = UUID.randomUUID();
    var result = command.create(hubId, product, 1);
    jdbc.update(
        "UPDATE hub.p_stocks SET deleted_at = NOW(), deleted_by = 'stock-tester' WHERE id = ?",
        result.id());
    assertThatThrownBy(() -> query.getStock(hubId, product))
        .isInstanceOf(StockBusinessException.class);
    assertThat(query.getStocks(hubId, product, 0, 20).getTotalElements()).isZero();
    assertThat(query.getStocks(hubId, product, Integer.MAX_VALUE, 100).getTotalElements()).isZero();
    assertThatThrownBy(() -> command.create(hubId, product, 1))
        .isInstanceOf(StockBusinessException.class)
        .hasMessageContaining("동일한 상품");
  }

  @Test
  void concurrentCreationRollsBackDuplicateAndKeepsOnlyOneStock() throws Exception {
    UUID product = UUID.randomUUID();
    var barrier = new CyclicBarrier(2);
    try (var executor = Executors.newFixedThreadPool(2)) {
      java.util.concurrent.Callable<String> create =
          () -> {
            authenticate();
            try {
              return new TransactionTemplate(transactions)
                  .execute(
                      status -> {
                        assertThat(stocks.existsByHubIdAndProductId(hubId, product)).isFalse();
                        try {
                          barrier.await(10, TimeUnit.SECONDS);
                        } catch (Exception exception) {
                          throw new IllegalStateException(exception);
                        }
                        stocks.saveAndFlush(Stock.create(hubId, product, 10));
                        return "CREATED";
                      });
            } catch (DataIntegrityViolationException exception) {
              return "DUPLICATE";
            } finally {
              SecurityContextHolder.clearContext();
            }
          };
      var first = executor.submit(create);
      var second = executor.submit(create);
      assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder("CREATED", "DUPLICATE");
    }
    assertThat(stocks.count()).isEqualTo(1);
    assertThat(query.getStock(hubId, product).quantity()).isEqualTo(10);
  }

  @Test
  void databaseRejectsNegativeQuantityIndependentlyOfDomainValidation() {
    var result = command.create(hubId, UUID.randomUUID(), 0);
    assertThatThrownBy(
            () -> jdbc.update("UPDATE hub.p_stocks SET quantity = -1 WHERE id = ?", result.id()))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(stocks.findById(result.id()).orElseThrow().getQuantity()).isZero();
  }

  @Test
  void searchesAllFiltersWithStableSortingAndLargePages() {
    UUID otherHub = UUID.fromString("00000000-0000-0000-0000-000000000002");
    UUID product = UUID.randomUUID();
    var first = command.create(hubId, product, 1);
    var second = command.create(otherHub, product, 2);
    var third = command.create(hubId, UUID.randomUUID(), 3);
    var old = Timestamp.from(Instant.parse("2020-01-01T00:00:00Z"));
    var recent = Timestamp.from(Instant.parse("2020-01-02T00:00:00Z"));
    jdbc.update("UPDATE hub.p_stocks SET created_at = ?", old);
    jdbc.update("UPDATE hub.p_stocks SET created_at = ? WHERE id = ?", recent, third.id());
    assertThat(query.getStocks(null, null, 0, 1).getContent().getFirst().id())
        .isEqualTo(third.id());
    var tied =
        List.of(first.id(), second.id()).stream()
            .sorted(java.util.Comparator.comparing(UUID::toString).reversed())
            .toList();
    assertThat(query.getStocks(null, null, 1, 1).getContent().getFirst().id())
        .isEqualTo(tied.getFirst());
    assertThat(query.getStocks(null, product, 0, 20).getTotalElements()).isEqualTo(2);
    assertThat(query.getStocks(hubId, null, 0, 20).getTotalElements()).isEqualTo(2);
    assertThat(query.getStocks(hubId, product, 0, 20).getTotalElements()).isEqualTo(1);
    assertThat(query.getStocks(null, null, 3, 1).getContent()).isEmpty();
    for (UUID hub : new UUID[] {null, hubId}) {
      for (UUID item : new UUID[] {null, product}) {
        var huge = query.getStocks(hub, item, 21474837, 100);
        assertThat(huge.getContent()).isEmpty();
        assertThat(huge.getTotalElements())
            .isEqualTo(query.getStocks(hub, item, 0, 100).getTotalElements());
      }
    }
  }
}
