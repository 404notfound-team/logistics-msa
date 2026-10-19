package com.notfound.logistics.delivery.presentation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 실제 서명 JWT·컨트롤러·서비스·PostgreSQL을 거쳐 목록과 개인정보의 조회 범위를 검사한다. */
@SpringBootTest(
    properties = {
      "eureka.client.enabled=false",
      "management.tracing.enabled=false",
      "delivery.internal-auth.service-token=read-authorization-test-service-token",
      "delivery.jwt.secret=" + DeliveryReadAuthorizationIntegrationTest.SECRET
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DeliveryReadAuthorizationIntegrationTest {
  static final String SECRET = "delivery-read-test-signing-secret-32bytes-minimum";
  private static final UUID STAFF = UUID.fromString("00000000-0000-0000-0000-000000000007");
  private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000008");
  private static final UUID DEPARTURE = UUID.randomUUID();
  private static final UUID DESTINATION = UUID.randomUUID();
  private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

  @Container
  static final PostgreSQLContainer<?> DATABASE =
      new PostgreSQLContainer<>("postgres:18-alpine").withInitScript("db/test-schema.sql");

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper mapper;
  private UUID ownWaiting;
  private UUID ownMoving;
  private UUID foreign;
  private UUID unassigned;
  private UUID deleted;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> DATABASE.getJdbcUrl() + "&currentSchema=delivery");
    registry.add("spring.datasource.username", DATABASE::getUsername);
    registry.add("spring.datasource.password", DATABASE::getPassword);
  }

  @BeforeEach
  void prepare() {
    jdbc.update("DELETE FROM p_delivery_routes");
    jdbc.update("DELETE FROM p_deliveries");
    ownWaiting = insert(STAFF, OTHER, "HUB_WAITING", false);
    ownMoving = insert(STAFF, STAFF, "HUB_MOVING", false);
    // A route assigned to STAFF must not grant access to someone else's delivery.
    foreign = insert(OTHER, STAFF, "HUB_WAITING", false);
    unassigned = insert(null, null, "HUB_WAITING", false);
    deleted = insert(STAFF, STAFF, "HUB_WAITING", true);
  }

  @ParameterizedTest
  @CsvSource({"MASTER,false", "MASTER,true", "USER,false", "USER,true"})
  void roleAndStaffFlagApplyConsistentlyToListsDetailsAndRoutes(String role, boolean staff)
      throws Exception {
    boolean restricted = role.equals("USER") && staff;
    String token = token(STAFF, role, staff);
    var response =
        request(get("/api/deliveries"), token)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total_elements").value(restricted ? 2 : 4))
            .andExpect(jsonPath("$.data.content.length()").value(restricted ? 2 : 4))
            .andReturn()
            .getResponse();
    if (restricted) {
      for (var delivery : mapper.readTree(response.getContentAsString()).at("/data/content")) {
        assertThat(delivery.path("assignee_id").asText()).isEqualTo(STAFF.toString());
      }
    }
    for (UUID id : List.of(ownWaiting, ownMoving, foreign, unassigned)) {
      boolean forbidden = restricted && (id.equals(foreign) || id.equals(unassigned));
      for (String suffix : List.of("", "/routes")) {
        var result = request(get("/api/deliveries/" + id + suffix), token);
        if (forbidden) {
          result
              .andExpect(status().isForbidden())
              .andExpect(jsonPath("$.code").value("DELIVERY_READ_FORBIDDEN"))
              .andExpect(jsonPath("$.data").doesNotExist());
        } else result.andExpect(status().isOk());
      }
    }
    for (UUID id : List.of(deleted, UUID.randomUUID())) {
      request(get("/api/deliveries/" + id), token).andExpect(status().isNotFound());
      request(get("/api/deliveries/" + id + "/routes"), token).andExpect(status().isNotFound());
    }
  }

  @Test
  void staffPaginationAndExplicitAssigneeFiltersCannotExposeOtherDeliveriesOrCounts()
      throws Exception {
    String token = token(STAFF, "USER", true);
    var seen = new java.util.HashSet<String>();
    for (int page = 0; page <= 2; page++) {
      var result =
          request(
                  get("/api/deliveries").param("page", Integer.toString(page)).param("size", "1"),
                  token)
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data.total_elements").value(2))
              .andExpect(jsonPath("$.data.total_pages").value(2))
              .andExpect(jsonPath("$.data.content.length()").value(page == 2 ? 0 : 1))
              .andReturn()
              .getResponse();
      for (var item : mapper.readTree(result.getContentAsString()).at("/data/content"))
        seen.add(item.path("delivery_id").asText());
    }
    assertThat(seen).containsExactlyInAnyOrder(ownWaiting.toString(), ownMoving.toString());
    request(get("/api/deliveries").param("assignee_id", OTHER.toString()), token)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total_elements").value(0))
        .andExpect(jsonPath("$.data.content").isEmpty());
    request(get("/api/deliveries").param("assignee_id", STAFF.toString()), token)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total_elements").value(2));
    request(
            get("/api/deliveries").param("assignee_id", OTHER.toString()),
            token(STAFF, "USER", false))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total_elements").value(1));
  }

  @Test
  void staffScopeIsCombinedWithAllExistingSearchFilters() throws Exception {
    UUID orderId =
        jdbc.queryForObject(
            "SELECT order_id FROM p_deliveries WHERE delivery_id=?", UUID.class, ownWaiting);
    String token = token(STAFF, "USER", true);
    request(
            get("/api/deliveries")
                .param("order_id", orderId.toString())
                .param("status", "HUB_WAITING")
                .param("departure_hub_id", DEPARTURE.toString())
                .param("destination_hub_id", DESTINATION.toString())
                .param("address", ownWaiting.toString())
                .param("recipient_name", ownWaiting.toString())
                .param("assignee_id", STAFF.toString()),
            token)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total_elements").value(1))
        .andExpect(jsonPath("$.data.content[0].delivery_id").value(ownWaiting.toString()));
    request(get("/api/deliveries").param("status", "DELIVERED"), token)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total_elements").value(0));
    request(get("/api/deliveries").param("address", foreign.toString()), token)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total_elements").value(0));
  }

  @Test
  void forgedIdentityHeadersCannotChangeTheSignedStaffScope() throws Exception {
    request(
            get("/api/deliveries/" + foreign)
                .header("X-User-Role", "MASTER")
                .header("X-User-Id", OTHER.toString())
                .header("X-Is-Delivery-Staff", "false"),
            token(STAFF, "USER", true))
        .andExpect(status().isForbidden());
  }

  @Test
  void reassignmentImmediatelyChangesWhoCanReadTheDelivery() throws Exception {
    request(
            patch("/api/deliveries/" + ownWaiting)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"assignee_id\":\"" + OTHER + "\"}"),
            token(STAFF, "MASTER", false))
        .andExpect(status().isOk());
    request(get("/api/deliveries/" + ownWaiting), token(STAFF, "USER", true))
        .andExpect(status().isForbidden());
    request(get("/api/deliveries/" + ownWaiting + "/routes"), token(OTHER, "USER", true))
        .andExpect(status().isOk());
    request(get("/api/deliveries"), token(STAFF, "USER", true))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total_elements").value(1));
  }

  private ResultActions request(MockHttpServletRequestBuilder request, String token)
      throws Exception {
    return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
  }

  private static String token(UUID userId, String role, boolean staff) {
    Instant now = Instant.now();
    var claims =
        JwtClaimsSet.builder()
            .issuer("logistics-users")
            .subject("viewer01")
            .issuedAt(now)
            .expiresAt(now.plusSeconds(600))
            .claim("user_id", userId.toString())
            .claim("role", role)
            .claim("is_delivery_staff", staff)
            .build();
    var encoder =
        new NimbusJwtEncoder(new ImmutableSecret<>(SECRET.getBytes(StandardCharsets.UTF_8)));
    return encoder
        .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
        .getTokenValue();
  }

  private UUID insert(UUID assignee, UUID routeAssignee, String status, boolean deleted) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO p_deliveries (delivery_id, order_id, status, departure_hub_id, destination_hub_id,
            address, recipient_name, delivery_manager_id, created_at, created_by, updated_at, updated_by, deleted_at, deleted_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'tester', ?, 'tester', ?, ?)
        """,
        id,
        UUID.randomUUID(),
        status,
        DEPARTURE,
        DESTINATION,
        "주소 " + id,
        "수령인 " + id,
        assignee,
        Timestamp.from(BASE),
        Timestamp.from(BASE),
        deleted ? Timestamp.from(BASE) : null,
        deleted ? "tester" : null);
    jdbc.update(
        """
        INSERT INTO p_delivery_routes (delivery_route_id, delivery_id, sequence, departure_hub_id, arrival_hub_id,
            expected_distance_km, expected_duration_seconds, status, assignee_id, created_at, created_by, updated_at, updated_by)
        VALUES (?, ?, 1, ?, ?, 10, 600, 'WAITING', ?, ?, 'tester', ?, 'tester')
        """,
        UUID.randomUUID(),
        id,
        DEPARTURE,
        DESTINATION,
        routeAssignee,
        Timestamp.from(BASE),
        Timestamp.from(BASE));
    return id;
  }
}
