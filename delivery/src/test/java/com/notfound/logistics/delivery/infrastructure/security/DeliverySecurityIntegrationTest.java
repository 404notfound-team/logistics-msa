package com.notfound.logistics.delivery.infrastructure.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.notfound.logistics.delivery.application.dto.DeliveryActor;
import com.notfound.logistics.delivery.application.dto.DeliveryResult;
import com.notfound.logistics.delivery.application.service.DeliveryCancellationService;
import com.notfound.logistics.delivery.application.service.DeliveryCommandService;
import com.notfound.logistics.delivery.application.service.DeliveryCreationService;
import com.notfound.logistics.delivery.application.service.DeliveryQueryService;
import com.notfound.logistics.delivery.domain.model.DeliveryStatus;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 실제 서명한 JWT로 배송 API의 인증과 요청자 클레임 전달을 확인한다. */
@WebMvcTest(properties = "delivery.jwt.secret=" + DeliverySecurityIntegrationTest.SECRET)
@Import(DeliverySecurityConfiguration.class)
class DeliverySecurityIntegrationTest {
  static final String SECRET = "delivery-security-test-secret-0123456789";
  private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000007");
  private static final UUID DELIVERY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

  @Autowired private MockMvc mockMvc;
  @MockitoBean private DeliveryQueryService deliveryQueryService;
  @MockitoBean private DeliveryCommandService deliveryCommandService;
  @MockitoBean private DeliveryCreationService deliveryCreationService;
  @MockitoBean private DeliveryCancellationService deliveryCancellationService;

  @BeforeEach
  void stubModify() {
    given(deliveryCommandService.modify(any(), any(), any(), any()))
        .willReturn(
            new DeliveryResult(
                DELIVERY_ID,
                UUID.randomUUID(),
                DeliveryStatus.HUB_MOVING,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "서울특별시 송파구 송파대로 55",
                "홍길동",
                USER_ID,
                List.of(),
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-02T00:00:00Z")));
  }

  @Test
  void signedTokenReachesTheServiceWithItsUserIdAndRoleIgnoringForgedHeaders() throws Exception {
    mockMvc
        .perform(
            modifyStatus()
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(claims().build()))
                .header("X-User-Role", "MASTER")
                .header("X-User-Id", UUID.randomUUID().toString()))
        .andExpect(status().isOk());

    then(deliveryCommandService)
        .should()
        .modify(DELIVERY_ID, DeliveryStatus.HUB_MOVING, null, new DeliveryActor(USER_ID, false));
  }

  @Test
  void masterTokenIsPassedAsMaster() throws Exception {
    mockMvc
        .perform(
            modifyStatus()
                .header(
                    HttpHeaders.AUTHORIZATION,
                    "Bearer " + token(claims().claim("role", "MASTER").build())))
        .andExpect(status().isOk());

    then(deliveryCommandService)
        .should()
        .modify(DELIVERY_ID, DeliveryStatus.HUB_MOVING, null, new DeliveryActor(USER_ID, true));
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "not-a-uuid"})
  void tokenWithoutAValidUserIdIsUnauthorized(String userId) throws Exception {
    JwtClaimsSet.Builder claims = claims();
    if (userId.equals("missing")) {
      claims.claims(values -> values.remove("user_id"));
    } else {
      claims.claim("user_id", userId);
    }

    mockMvc
        .perform(
            modifyStatus().header(HttpHeaders.AUTHORIZATION, "Bearer " + token(claims.build())))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    then(deliveryCommandService).should(never()).modify(any(), any(), any(), any());
  }

  @Test
  void tokenSignedWithAnotherSecretIsUnauthorized() throws Exception {
    NimbusJwtEncoder other =
        new NimbusJwtEncoder(
            new ImmutableSecret<>(
                "another-secret-another-secret-0123456789".getBytes(StandardCharsets.UTF_8)));
    String forged =
        other
            .encode(
                JwtEncoderParameters.from(
                    JwsHeader.with(MacAlgorithm.HS256).build(), claims().build()))
            .getTokenValue();

    mockMvc
        .perform(modifyStatus().header(HttpHeaders.AUTHORIZATION, "Bearer " + forged))
        .andExpect(status().isUnauthorized());
    then(deliveryCommandService).should(never()).modify(any(), any(), any(), any());
  }

  private static MockHttpServletRequestBuilder modifyStatus() {
    return patch("/api/deliveries/{delivery_id}", DELIVERY_ID)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"status\": \"HUB_MOVING\"}");
  }

  private static JwtClaimsSet.Builder claims() {
    Instant now = Instant.now();
    return JwtClaimsSet.builder()
        .issuer("logistics-users")
        .subject("alice")
        .issuedAt(now)
        .expiresAt(now.plusSeconds(600))
        .claim("user_id", USER_ID.toString())
        .claim("role", "USER")
        .claim("is_delivery_staff", true);
  }

  private static String token(JwtClaimsSet claims) {
    NimbusJwtEncoder encoder =
        new NimbusJwtEncoder(new ImmutableSecret<>(SECRET.getBytes(StandardCharsets.UTF_8)));
    return encoder
        .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
        .getTokenValue();
  }
}
