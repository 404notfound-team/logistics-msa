package com.notfound.logistics.hub.support;

import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

// 기존 업무 계약 테스트도 필터를 끄지 않고 실제 인증을 통과시킨다.
public final class HubSecurityTestRequests {
  private HubSecurityTestRequests() {}

  public static MockHttpServletRequestBuilder authenticatedGet(String path, Object... variables) {
    return authenticate(MockMvcRequestBuilders.get(path, variables), path);
  }

  public static MockHttpServletRequestBuilder authenticatedPost(String path, Object... variables) {
    return authenticate(MockMvcRequestBuilders.post(path, variables), path);
  }

  private static MockHttpServletRequestBuilder authenticate(
      MockHttpServletRequestBuilder request, String path) {
    if (path.startsWith("/internal/")) {
      String token =
          path.startsWith("/internal/hub-routes")
              ? HubSecurityTestTokens.DELIVERY
              : path.contains("/stocks")
                  ? HubSecurityTestTokens.ORDERS
                  : HubSecurityTestTokens.USERS;
      return request.header("X-Service-Token", token).header("X-Actor-Username", "stock-tester");
    }
    return request.header(
        "Authorization", "Bearer " + HubSecurityTestTokens.token("stock-tester", "MASTER"));
  }
}
