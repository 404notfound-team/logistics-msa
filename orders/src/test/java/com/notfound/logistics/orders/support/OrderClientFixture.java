package com.notfound.logistics.orders.support;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.notfound.logistics.common.api.ApiResponse;
import com.notfound.logistics.orders.domain.model.Order;
import com.notfound.logistics.orders.infrastructure.client.CompanyClient;
import com.notfound.logistics.orders.infrastructure.client.DeliveryClient;
import com.notfound.logistics.orders.infrastructure.client.OrderClientTypes.Company;
import com.notfound.logistics.orders.infrastructure.client.OrderClientTypes.Deduction;
import com.notfound.logistics.orders.infrastructure.client.OrderClientTypes.Delivery;
import com.notfound.logistics.orders.infrastructure.client.OrderClientTypes.DeliveryRequest;
import com.notfound.logistics.orders.infrastructure.client.OrderClientTypes.Product;
import com.notfound.logistics.orders.infrastructure.client.OrderClientTypes.Restoration;
import com.notfound.logistics.orders.infrastructure.client.OrderClientTypes.Stock;
import com.notfound.logistics.orders.infrastructure.client.RemoteFailure;
import com.notfound.logistics.orders.infrastructure.client.StockClient;
import com.notfound.logistics.orders.infrastructure.notification.SlackNotifier;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * In-process client answers for failure injection; never starts a server or implements HTTP APIs.
 */
public class OrderClientFixture implements AutoCloseable {
  public static final String SECRET = "orders-integration-fixture-secret-32-bytes-minimum";
  public static final String SERVICE_TOKEN = "orders-integration-service-token";
  public static final String RECEIVER = "147259cc-6ff6-42c7-a89a-8798c8187811";
  public static final String SUPPLIER = "1ef5bfb1-6b53-40ef-b09a-54eb71846d73";
  public static final String PRODUCT = "64f1ff8f-cd59-4504-b665-ef18727a6306";
  private static final UUID ORIGIN = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID DESTINATION = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID STOCK = UUID.fromString("00000000-0000-0000-0000-000000000003");
  public final CompanyClient companies = mock(CompanyClient.class);
  public final StockClient stocks = mock(StockClient.class);
  public final DeliveryClient deliveries = mock(DeliveryClient.class);
  public final SlackNotifier notifications = mock(SlackNotifier.class);
  private final ObjectMapper mapper =
      new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final Map<String, Map<String, Object>> rules = new HashMap<>();
  private final Map<String, Integer> counts = new HashMap<>();
  private final Map<String, String> actors = new HashMap<>();
  private final Map<String, UUID> hubs = new HashMap<>();
  private final Map<UUID, DeliveryRequest> requests = new LinkedHashMap<>();
  private final Map<UUID, Delivery> shipping = new LinkedHashMap<>();
  private final Map<UUID, Stock> restorations = new LinkedHashMap<>();
  private final Map<UUID, Deduction> deductionRequests = new LinkedHashMap<>();
  private final Map<UUID, Stock> deductions = new LinkedHashMap<>();
  private final Map<UUID, RemoteFailure> rejectedDeductions = new LinkedHashMap<>();
  private final List<UUID> notices = new ArrayList<>();
  private long quantity;
  private String productName;
  private BigDecimal price;
  private boolean productActive;
  private int inflight;
  private long timeoutMs = 1000;

  public OrderClientFixture() {
    reset();
    when(companies.company(any()))
        .thenAnswer(
            call ->
                invoke(
                    "company.get",
                    Company.class,
                    () -> {
                      UUID id = call.getArgument(0);
                      UUID hub = hubs.get(id.toString());
                      if (hub == null) throw new RemoteFailure(404, "COMPANY_NOT_FOUND");
                      return new Company(id, hub);
                    }));
    when(companies.product(any()))
        .thenAnswer(
            call ->
                invoke(
                    "product.get",
                    Product.class,
                    () -> {
                      UUID id = call.getArgument(0);
                      if (!id.toString().equals(PRODUCT) || !productActive)
                        throw new RemoteFailure(404, "PRODUCT_NOT_FOUND");
                      return new Product(id, UUID.fromString(SUPPLIER), productName, price);
                    }));
    when(stocks.deduct(any()))
        .thenAnswer(
            call ->
                invoke(
                    "stock.deduct",
                    Stock.class,
                    () -> {
                      Deduction request = call.getArgument(0);
                      var previous = deductionRequests.putIfAbsent(request.orderId(), request);
                      if (previous != null && !previous.equals(request))
                        throw new RemoteFailure(409, "STOCK_DEDUCTION_CONFLICT");
                      if (restorations.containsKey(request.orderId()))
                        throw new RemoteFailure(409, "STOCK_ALREADY_RESTORED");
                      if (rejectedDeductions.containsKey(request.orderId()))
                        throw rejectedDeductions.get(request.orderId());
                      if (deductions.containsKey(request.orderId()))
                        return deductions.get(request.orderId());
                      if (quantity < request.quantity()) {
                        var rejection = new RemoteFailure(409, "INSUFFICIENT_STOCK");
                        rejectedDeductions.put(request.orderId(), rejection);
                        throw rejection;
                      }
                      quantity -= request.quantity();
                      var result = stock();
                      deductions.put(request.orderId(), result);
                      return result;
                    }));
    when(stocks.restore(any()))
        .thenAnswer(
            call ->
                invoke(
                    "stock.restore",
                    Stock.class,
                    () -> {
                      Restoration request = call.getArgument(0);
                      var deduction = deductionRequests.get(request.orderId());
                      if (!deductions.containsKey(request.orderId()))
                        throw new RemoteFailure(404, "STOCK_DEDUCTION_NOT_FOUND");
                      if (!deduction.hubId().equals(request.hubId())
                          || !deduction.productId().equals(request.productId())
                          || deduction.quantity() != request.quantity())
                        throw new RemoteFailure(409, "STOCK_RESTORATION_CONFLICT");
                      return restorations.computeIfAbsent(
                          request.orderId(),
                          id -> {
                            quantity = Math.addExact(quantity, request.quantity());
                            return stock();
                          });
                    }));
    when(deliveries.byOrder(any()))
        .thenAnswer(
            call ->
                invoke(
                    "delivery.get",
                    Delivery.class,
                    () -> {
                      Delivery result = shipping.get(call.getArgument(0));
                      if (result == null) throw new RemoteFailure(404, "DELIVERY_NOT_FOUND");
                      return result;
                    }));
    when(deliveries.create(any()))
        .thenAnswer(
            call ->
                invoke(
                    "delivery.create",
                    Delivery.class,
                    () -> {
                      DeliveryRequest request = call.getArgument(0);
                      DeliveryRequest previous = requests.putIfAbsent(request.orderId(), request);
                      if (previous != null && !previous.equals(request))
                        throw new RemoteFailure(409, "DELIVERY_ALREADY_EXISTS");
                      return shipping.computeIfAbsent(
                          request.orderId(),
                          id -> new Delivery(UUID.randomUUID(), id, "HUB_WAITING", null));
                    }));
    when(deliveries.cancel(any()))
        .thenAnswer(
            call ->
                invoke(
                    "delivery.cancel",
                    Delivery.class,
                    () -> {
                      Delivery previous = byId(call.getArgument(0));
                      if (!List.of("HUB_WAITING", "CANCELLED").contains(previous.status()))
                        throw new RemoteFailure(409, "DELIVERY_ALREADY_STARTED");
                      Delivery cancelled =
                          new Delivery(
                              previous.deliveryId(),
                              previous.orderId(),
                              "CANCELLED",
                              previous.assigneeId());
                      shipping.put(previous.orderId(), cancelled);
                      return cancelled;
                    }));
    doAnswer(
            call -> {
              invoke(
                  "delivery.delete",
                  Delivery.class,
                  () -> {
                    UUID id = call.getArgument(0);
                    shipping.values().removeIf(value -> value.deliveryId().equals(id));
                    return null;
                  });
              return null;
            })
        .when(deliveries)
        .delete(any());
    doAnswer(
            call -> {
              try {
                invoke(
                    "slack",
                    UUID.class,
                    () -> {
                      UUID id = call.<Order>getArgument(0).getId();
                      notices.add(id);
                      return id;
                    });
              } catch (RemoteFailure ignored) {
                // Matches SlackNotifier's best-effort boundary, without external messages.
              }
              return null;
            })
        .when(notifications)
        .confirmed(any(), any());
  }

  private Stock stock() {
    return new Stock(STOCK, ORIGIN, UUID.fromString(PRODUCT), quantity);
  }

  private Delivery byId(UUID id) {
    return shipping.values().stream()
        .filter(value -> value.deliveryId().equals(id))
        .findFirst()
        .orElseThrow(() -> new RemoteFailure(404, "DELIVERY_NOT_FOUND"));
  }

  private <T> ApiResponse<T> invoke(String operation, Class<T> type, Supplier<T> work)
      throws Exception {
    Map<String, Object> rule;
    synchronized (this) {
      counts.merge(operation, 1, Integer::sum);
      var authentication = SecurityContextHolder.getContext().getAuthentication();
      actors.put(operation, authentication == null ? null : authentication.getName());
      var configured = rules.get(operation);
      rule =
          configured == null || ((Number) configured.getOrDefault("remaining", 1)).intValue() == 0
              ? Map.of()
              : new HashMap<>(configured);
      if (!rule.isEmpty())
        configured.put("remaining", ((Number) rule.getOrDefault("remaining", 1)).intValue() - 1);
      inflight++;
    }
    var future =
        executor.submit(
            () -> {
              try {
                pause(rule, "delay_before_ms");
                if (rule.containsKey("status"))
                  throw new RemoteFailure(
                      ((Number) rule.get("status")).intValue(),
                      (String) rule.getOrDefault("code", "UPSTREAM_UNAVAILABLE"));
                T result;
                synchronized (this) {
                  result = work.get();
                }
                pause(rule, "delay_after_ms");
                if (rule.containsKey("response")) {
                  var target =
                      mapper.getTypeFactory().constructParametricType(ApiResponse.class, type);
                  try {
                    return mapper.<ApiResponse<T>>convertValue(rule.get("response"), target);
                  } catch (IllegalArgumentException exception) {
                    throw new RemoteFailure(503, "UPSTREAM_UNAVAILABLE");
                  }
                }
                return result == null ? null : new ApiResponse<>(result);
              } finally {
                synchronized (this) {
                  inflight--;
                  notifyAll();
                }
              }
            });
    try {
      return future.get(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (TimeoutException exception) {
      // Let the remote-side effect finish, exactly as with a lost HTTP response.
      throw new RemoteFailure(503, "UPSTREAM_UNAVAILABLE");
    } catch (ExecutionException exception) {
      if (exception.getCause() instanceof RuntimeException cause) throw cause;
      throw new IllegalStateException(exception.getCause());
    }
  }

  private void pause(Map<String, Object> rule, String key) throws InterruptedException {
    Thread.sleep(((Number) rule.getOrDefault(key, 0)).longValue());
  }

  public synchronized void reset() {
    rules.clear();
    counts.clear();
    actors.clear();
    hubs.clear();
    requests.clear();
    shipping.clear();
    restorations.clear();
    deductionRequests.clear();
    deductions.clear();
    rejectedDeductions.clear();
    notices.clear();
    hubs.put(RECEIVER, DESTINATION);
    hubs.put(SUPPLIER, ORIGIN);
    quantity = 1000;
    productName = "테스트 상품";
    price = new BigDecimal("10000.00");
    productActive = true;
  }

  public synchronized void control(Map<String, Object> values) {
    switch ((String) values.get("action")) {
      case "reset" -> reset();
      case "fault" -> rules.put((String) values.get("operation"), new HashMap<>(values));
      case "stock" -> quantity = ((Number) values.get("quantity")).longValue();
      case "product" -> {
        productName = (String) values.getOrDefault("name", "테스트 상품");
        price = new BigDecimal(values.getOrDefault("price", "10000.00").toString());
        productActive = (boolean) values.getOrDefault("active", true);
      }
      case "company_hub" ->
          hubs.put(
              values.get("company_id").toString(),
              UUID.fromString(values.get("hub_id").toString()));
      case "delivery_status" -> {
        UUID id = UUID.fromString(values.get("order_id").toString());
        Delivery previous = shipping.get(id);
        shipping.put(
            id,
            new Delivery(
                previous.deliveryId(), id, (String) values.get("status"), previous.assigneeId()));
      }
      default -> throw new IllegalArgumentException("Unknown test action");
    }
  }

  public synchronized int depart(UUID id) {
    Delivery previous = byId(id);
    if (!previous.status().equals("HUB_WAITING")) return 409;
    shipping.put(
        previous.orderId(),
        new Delivery(id, previous.orderId(), "HUB_MOVING", previous.assigneeId()));
    return 200;
  }

  public synchronized JsonNode state() {
    return mapper.valueToTree(
        Map.of(
            "stocks",
            List.of(stock()),
            "deliveries",
            shipping.values(),
            "restorations",
            restorations.values(),
            "notifications",
            notices,
            "counts",
            new HashMap<>(counts),
            "actors",
            new HashMap<>(actors),
            "inflight",
            inflight));
  }

  public void timeout(long milliseconds) {
    timeoutMs = milliseconds;
  }

  public static String token(String username, String role) {
    return token(username, role, 900);
  }

  public static String token(String username, String role, long lifetimeSeconds) {
    try {
      Instant now = Instant.now();
      var claims =
          new JWTClaimsSet.Builder()
              .issuer("logistics-users")
              .subject(username)
              .claim("role", role)
              .issueTime(Date.from(now))
              .expirationTime(Date.from(now.plusSeconds(lifetimeSeconds)))
              .build();
      var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
      jwt.sign(new MACSigner(SECRET));
      return jwt.serialize();
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  public synchronized void awaitIdle() {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
    try {
      while (inflight > 0 && System.nanoTime() < deadline) wait(20);
      if (inflight != 0) throw new IllegalStateException("Client answers are still running");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }

  @Override
  public void close() {
    awaitIdle();
    executor.close();
  }
}
