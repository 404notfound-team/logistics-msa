package com.notfound.logistics.hub.infrastructure.persistence.stock;

import static com.notfound.logistics.hub.support.HubSecurityTestRequests.authenticatedGet;
import static com.notfound.logistics.hub.support.HubSecurityTestRequests.authenticatedPost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.notfound.logistics.hub.HubApplication;
import com.notfound.logistics.hub.application.service.stock.StockCommandService;
import com.notfound.logistics.hub.application.service.stock.StockQueryService;
import com.notfound.logistics.hub.domain.exception.stock.StockBusinessException;
import com.notfound.logistics.hub.domain.exception.stock.StockErrorCode;
import com.notfound.logistics.hub.domain.model.stock.Stock;
import com.notfound.logistics.hub.domain.repository.stock.StockRepository;
import com.notfound.logistics.hub.support.HubSecurityTest;
import com.notfound.logistics.hub.support.HubSecurityTestTokens;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
@HubSecurityTest
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
  void realAuthenticationControlsWritesAndPreservesOriginalAuditActor() throws Exception {
    UUID product = UUID.randomUUID();
    String creation =
        "{\"hub_id\":\"" + hubId + "\",\"product_id\":\"" + product + "\",\"quantity\":100}";
    String addition = "/api/hubs/" + hubId + "/products/" + product + "/stocks/add";
    var userToken = HubSecurityTestTokens.token("customer", "USER");
    var creatorToken = HubSecurityTestTokens.token("creator", "MASTER");
    var managerToken = HubSecurityTestTokens.token("manager", "MASTER");
    var missingSubjectToken =
        HubSecurityTestTokens.sign(
            HubSecurityTestTokens.claims("creator", "MASTER")
                .claims(values -> values.remove("sub"))
                .build(),
            HubSecurityTestTokens.SECRET);
    SecurityContextHolder.clearContext();
    mvc.perform(post("/api/stocks").contentType(MediaType.APPLICATION_JSON).content(creation))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/stocks")
                .header("Authorization", "Bearer " + missingSubjectToken)
                .header("X-Actor-Username", "forged")
                .contentType(MediaType.APPLICATION_JSON)
                .content(creation))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/stocks")
                .header("Authorization", "Bearer " + userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(creation))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    assertThat(stocks.count()).isZero();
    mvc.perform(
            post("/api/stocks")
                .header("Authorization", "Bearer " + creatorToken)
                .header("X-Actor-Username", "forged")
                .contentType(MediaType.APPLICATION_JSON)
                .content(creation))
        .andExpect(status().isCreated());
    UUID stockId = query.getStock(hubId, product).id();
    var created = jdbc.queryForMap("SELECT * FROM hub.p_stocks WHERE id = ?", stockId);
    assertThat(created.get("created_by")).isEqualTo("creator");
    assertThat(created.get("updated_by")).isEqualTo("creator");
    mvc.perform(
            post(addition)
                .header("Authorization", "Bearer " + userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":5}"))
        .andExpect(status().isForbidden());
    assertThat(query.getStock(hubId, product).quantity()).isEqualTo(100);
    mvc.perform(
            post(addition)
                .header("Authorization", "Bearer " + managerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":5}"))
        .andExpect(status().isOk());
    String deduction =
        "{\"order_id\":\""
            + UUID.randomUUID()
            + "\",\"hub_id\":\""
            + hubId
            + "\",\"product_id\":\""
            + product
            + "\",\"quantity\":10}";
    mvc.perform(
            post("/internal/stocks/deductions")
                .header("X-Service-Token", HubSecurityTestTokens.ORDERS)
                .contentType(MediaType.APPLICATION_JSON)
                .content(deduction))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/internal/stocks/deductions")
                .header("X-Service-Token", HubSecurityTestTokens.COMPANY)
                .header("X-Actor-Username", "customer")
                .contentType(MediaType.APPLICATION_JSON)
                .content(deduction))
        .andExpect(status().isForbidden());
    var added = jdbc.queryForMap("SELECT * FROM hub.p_stocks WHERE id = ?", stockId);
    assertThat(added.get("quantity")).isEqualTo(105L);
    assertThat(added.get("updated_by")).isEqualTo("manager");
    String expiredUserJwt =
        HubSecurityTestTokens.sign(
            HubSecurityTestTokens.claims("creator", "MASTER")
                .issuedAt(Instant.now().minusSeconds(120))
                .expiresAt(Instant.now().minusSeconds(60))
                .build(),
            HubSecurityTestTokens.SECRET);
    mvc.perform(
            post("/internal/stocks/deductions")
                .header("Authorization", "Bearer " + expiredUserJwt)
                .header("X-Service-Token", HubSecurityTestTokens.ORDERS)
                .header("X-Actor-Username", "customer")
                .contentType(MediaType.APPLICATION_JSON)
                .content(deduction))
        .andExpect(status().isOk());
    var deducted = jdbc.queryForMap("SELECT * FROM hub.p_stocks WHERE id = ?", stockId);
    assertThat(deducted.get("quantity")).isEqualTo(95L);
    assertThat(deducted.get("created_by")).isEqualTo("creator");
    assertThat(deducted.get("created_at")).isEqualTo(created.get("created_at"));
    assertThat(deducted.get("updated_by")).isEqualTo("customer");
    mvc.perform(
            post("/internal/stocks/deductions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(deduction))
        .andExpect(status().isUnauthorized());
    assertThat(query.getStock(hubId, product).quantity()).isEqualTo(95);
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  void applicationMethodsEnforceUserAndServicePermissionsBeforeChangingStock() {
    UUID product = UUID.randomUUID();
    command.create(hubId, product, 10);
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(
                "manager", null, List.of(new SimpleGrantedAuthority("ROLE_MASTER"))));
    assertThatThrownBy(() -> command.deduct(hubId, product, 1))
        .isInstanceOf(AccessDeniedException.class);
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(
                "customer", null, List.of(new SimpleGrantedAuthority("SERVICE_ORDERS"))));
    assertThatThrownBy(() -> command.add(hubId, product, 1))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> command.create(hubId, UUID.randomUUID(), 1))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(query.getStock(hubId, product).quantity()).isEqualTo(10);
    assertThat(stocks.count()).isEqualTo(1);
  }

  @Test
  void httpRejectsFractionalQuantityAndStoresMaximumIntegerExactly() throws Exception {
    UUID product = UUID.randomUUID();
    String prefix =
        "{\"hub_id\":\"" + hubId + "\",\"product_id\":\"" + product + "\",\"quantity\":";
    mvc.perform(
            authenticatedPost("/api/stocks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(prefix + "-0.9}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    assertThat(stocks.count()).isZero();
    mvc.perform(
            authenticatedPost("/api/stocks")
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
      mvc.perform(authenticatedGet(path))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value("STOCK_NOT_FOUND"));
    }
    authenticate();
    var created = command.create(hubId, product, 1);
    for (String path : List.of(external, internal)) {
      mvc.perform(authenticatedGet(path))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.quantity").value(1));
    }
    jdbc.update(
        "UPDATE hub.p_stocks SET deleted_at = NOW(), deleted_by = 'stock-tester' WHERE id = ?",
        created.id());
    for (String path : List.of(external, internal)) {
      mvc.perform(authenticatedGet(path))
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
              authenticatedGet(path)
                  .param("page", Integer.toString(page))
                  .param("size", Integer.toString(size)))
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
            new UsernamePasswordAuthenticationToken(
                "stock-tester",
                null,
                List.of(
                    new SimpleGrantedAuthority("ROLE_MASTER"),
                    new SimpleGrantedAuthority("SERVICE_ORDERS"))));
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

  @Test
  void deductPersistsUpdatedQuantity() {
    UUID productId = UUID.randomUUID();

    var created = command.create(hubId, productId, 100);
    var result = command.deduct(hubId, productId, 30);

    assertThat(result.id()).isEqualTo(created.id());
    assertThat(result.quantity()).isEqualTo(70);

    Long quantity =
        jdbc.queryForObject(
            "SELECT quantity FROM hub.p_stocks WHERE id = ?", Long.class, created.id());

    assertThat(quantity).isEqualTo(70L);
    assertThat(query.getStock(hubId, productId).quantity()).isEqualTo(70);
  }

  @Test
  void deductExactQuantityLeavesZero() {
    UUID productId = UUID.randomUUID();

    var created = command.create(hubId, productId, 10);

    var result = command.deduct(hubId, productId, 10);

    assertThat(result.quantity()).isZero();

    Long quantity =
        jdbc.queryForObject(
            "SELECT quantity FROM hub.p_stocks WHERE id = ?", Long.class, created.id());

    assertThat(quantity).isZero();
  }

  @Test
  void insufficientDeductionDoesNotModifyDatabase() {
    UUID productId = UUID.randomUUID();

    var created = command.create(hubId, productId, 10);

    assertThatThrownBy(() -> command.deduct(hubId, productId, 20))
        .isInstanceOf(StockBusinessException.class)
        .extracting(e -> ((StockBusinessException) e).getErrorCode())
        .isEqualTo(StockErrorCode.INSUFFICIENT_STOCK);

    Long quantity =
        jdbc.queryForObject(
            "SELECT quantity FROM hub.p_stocks WHERE id = ?", Long.class, created.id());

    assertThat(quantity).isEqualTo(10L);
  }

  @Test
  void deletedStockCannotBeDeducted() {
    UUID productId = UUID.randomUUID();

    var created = command.create(hubId, productId, 100);

    jdbc.update(
        """
      UPDATE hub.p_stocks
      SET deleted_at = NOW(),
          deleted_by = 'stock-tester'
      WHERE id = ?
      """,
        created.id());

    assertThatThrownBy(() -> command.deduct(hubId, productId, 10))
        .isInstanceOf(StockBusinessException.class)
        .extracting(e -> ((StockBusinessException) e).getErrorCode())
        .isEqualTo(StockErrorCode.STOCK_NOT_FOUND);

    Long quantity =
        jdbc.queryForObject(
            "SELECT quantity FROM hub.p_stocks WHERE id = ?", Long.class, created.id());

    assertThat(quantity).isEqualTo(100L);
  }

  @Test
  void deductionUpdatesAuditFields() {
    UUID productId = UUID.randomUUID();

    var created = command.create(hubId, productId, 100);

    var before =
        jdbc.queryForMap(
            """
        SELECT created_at, created_by, updated_at
        FROM hub.p_stocks
        WHERE id = ?
        """,
            created.id());

    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "deduction-actor", null, List.of(new SimpleGrantedAuthority("SERVICE_ORDERS"))));

    command.deduct(hubId, productId, 10);

    var after =
        jdbc.queryForMap(
            """
        SELECT quantity, created_at, created_by, updated_at, updated_by
        FROM hub.p_stocks
        WHERE id = ?
        """,
            created.id());

    assertThat(after.get("quantity")).isEqualTo(90L);
    assertThat(after.get("created_by")).isEqualTo("stock-tester");
    assertThat(after.get("created_at")).isEqualTo(before.get("created_at"));
    assertThat(after.get("updated_by")).isEqualTo("deduction-actor");

    assertThat((Timestamp) after.get("updated_at"))
        .isAfterOrEqualTo((Timestamp) before.get("updated_at"));
  }

  @Test
  void deductHttpRequestUpdatesDatabase() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID productId = UUID.randomUUID();

    var created = command.create(hubId, productId, 100);

    String body =
        """
      {
        "order_id": "%s",
        "hub_id": "%s",
        "product_id": "%s",
        "quantity": 30
      }
      """
            .formatted(orderId, hubId, productId);

    mvc.perform(
            authenticatedPost("/internal/stocks/deductions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(created.id().toString()))
        .andExpect(jsonPath("$.data.hub_id").value(hubId.toString()))
        .andExpect(jsonPath("$.data.product_id").value(productId.toString()))
        .andExpect(jsonPath("$.data.quantity").value(70))
        .andExpect(jsonPath("$.data.state").doesNotExist());

    Long quantity =
        jdbc.queryForObject(
            "SELECT quantity FROM hub.p_stocks WHERE id = ?", Long.class, created.id());

    assertThat(quantity).isEqualTo(70L);
  }

  @Test
  void concurrentDeductionsAllowOnlyOneWhenInsufficient() throws Exception {
    UUID productId = UUID.randomUUID();

    var created = command.create(hubId, productId, 10);

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);

    try (var executor = Executors.newFixedThreadPool(2)) {

      java.util.concurrent.Callable<String> task =
          () -> {
            authenticate();

            try {
              ready.countDown();

              if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시 차감 시작 대기 시간 초과");
              }

              try {
                command.deduct(hubId, productId, 7);
                return "SUCCESS";
              } catch (StockBusinessException exception) {
                if (exception.getErrorCode() == StockErrorCode.INSUFFICIENT_STOCK) {
                  return "INSUFFICIENT_STOCK";
                }
                throw exception;
              }

            } finally {
              SecurityContextHolder.clearContext();
            }
          };

      Future<String> first = executor.submit(task);
      Future<String> second = executor.submit(task);

      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder("SUCCESS", "INSUFFICIENT_STOCK");
    }

    Long quantity =
        jdbc.queryForObject(
            "SELECT quantity FROM hub.p_stocks WHERE id = ?", Long.class, created.id());

    assertThat(quantity).isEqualTo(3L);
  }

  @Test
  void concurrentDeductionsDoNotLoseUpdates() throws Exception {
    UUID productId = UUID.randomUUID();

    var created = command.create(hubId, productId, 100);

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);

    try (var executor = Executors.newFixedThreadPool(2)) {

      java.util.concurrent.Callable<Long> deduct30 =
          () -> {
            authenticate();

            try {
              ready.countDown();

              if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시 차감 시작 대기 시간 초과");
              }

              return command.deduct(hubId, productId, 30).quantity();

            } finally {
              SecurityContextHolder.clearContext();
            }
          };

      java.util.concurrent.Callable<Long> deduct40 =
          () -> {
            authenticate();

            try {
              ready.countDown();

              if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시 차감 시작 대기 시간 초과");
              }

              return command.deduct(hubId, productId, 40).quantity();

            } finally {
              SecurityContextHolder.clearContext();
            }
          };

      Future<Long> first = executor.submit(deduct30);
      Future<Long> second = executor.submit(deduct40);

      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      assertThat(first.get(20, TimeUnit.SECONDS)).isIn(70L, 30L);

      assertThat(second.get(20, TimeUnit.SECONDS)).isIn(60L, 30L);
    }

    Long quantity =
        jdbc.queryForObject(
            "SELECT quantity FROM hub.p_stocks WHERE id = ?", Long.class, created.id());

    assertThat(quantity).isEqualTo(30L);
  }
}
