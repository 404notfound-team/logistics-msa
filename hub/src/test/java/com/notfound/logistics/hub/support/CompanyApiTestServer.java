package com.notfound.logistics.hub.support;

import java.io.IOException;
import java.net.InetAddress;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;

/** Company PR #65의 HTTP 계약. Feign을 교체하지 않고 실제 HTTP 통신을 검증한다. */
public final class CompanyApiTestServer {
  private static final MockWebServer SERVER = start();

  private CompanyApiTestServer() {}

  public static String url() {
    return System.getProperty("hub.company.simulation-url", SERVER.url("/").toString());
  }

  private static MockWebServer start() {
    try {
      var server = new MockWebServer();
      server.setDispatcher(
          new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
              var response = new MockResponse().setHeader("Content-Type", "application/json");
              String path = request.getPath();
              if (!"GET".equals(request.getMethod())
                  || path == null
                  || !path.startsWith("/internal/products/")) {
                return response.setResponseCode(501);
              }
              if (!"hub-service-test-token".equals(request.getHeader("X-Service-Token"))
                  || request.getHeader("X-Actor-Username") == null) {
                return response.setResponseCode(401).setBody("{\"code\":\"UNAUTHORIZED\"}");
              }
              String id = path.substring("/internal/products/".length());
              String body =
                  "{\"data\":{\"id\":\""
                      + id
                      + "\",\"company_id\":\"11111111-1111-1111-1111-111111111111\",\"name\":\"상품\",\"price\":100}}";
              if (id.equals(failureId(2)))
                return response
                    .setResponseCode(404)
                    .setBody(
                        "{\"code\":\"PRODUCT_NOT_FOUND\",\"message\":\"상품을 찾을 수 없습니다.\",\"trace_id\":null,\"field_errors\":[]}");
              if (id.equals(failureId(3)))
                return response.setResponseCode(500).setBody("{\"code\":\"INTERNAL_ERROR\"}");
              if (id.equals(failureId(4)))
                return response.setResponseCode(500).setBody("<html>upstream secret</html>");
              if (id.equals(failureId(5)))
                return response.setBody(body).setBodyDelay(1200, TimeUnit.MILLISECONDS);
              if (id.equals(failureId(6)))
                return response.setResponseCode(401).setBody("{\"code\":\"UNAUTHORIZED\"}");
              if (id.equals(failureId(7)))
                return response.setResponseCode(403).setBody("{\"code\":\"FORBIDDEN\"}");
              if (id.equals(failureId(8))) return response.setBody("invalid-json");
              if (id.equals(failureId(9))) return response.setBody("{\"data\":null}");
              if (id.equals(failureId(10)))
                return response.setBody(
                    "{\"data\":{\"id\":\"11111111-1111-1111-1111-111111111111\"}}");
              if (id.equals(failureId(11)))
                return response
                    .setResponseCode(302)
                    .setHeader(
                        "Location", "/internal/products/11111111-1111-1111-1111-111111111111");
              if (id.equals(failureId(12)))
                return response.setResponseCode(404).setBody("<html>proxy not found</html>");
              if (id.equals(failureId(13))) return response.setBody("");
              if (id.equals(failureId(14))) return response.setBody("{\"data\":{\"id\":null}}");
              if (id.equals(failureId(15)))
                return response.setSocketPolicy(SocketPolicy.DISCONNECT_AT_START);
              return response.setBody(body);
            }
          });
      server.start(InetAddress.getByName("127.0.0.1"), 0);
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    try {
                      server.shutdown();
                    } catch (IOException ignored) {
                    }
                  }));
      return server;
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  public static String failureId(int number) {
    return String.format("00000000-0000-0000-0001-%012d", number);
  }
}
