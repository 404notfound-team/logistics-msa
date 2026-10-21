package com.notfound.logistics.hub.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notfound.logistics.common.api.ApiErrorResponse;
import com.notfound.logistics.common.api.CommonErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

final class HubSecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {
  private final ObjectMapper mapper;

  HubSecurityErrorHandler(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public void commence(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
      throws IOException {
    if (request.getRequestURI().startsWith("/api/")) {
      response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    }
    write(response, CommonErrorCode.UNAUTHORIZED);
  }

  @Override
  public void handle(
      HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
      throws IOException {
    write(response, CommonErrorCode.FORBIDDEN);
  }

  private void write(HttpServletResponse response, CommonErrorCode error) throws IOException {
    response.setStatus(error.httpStatus());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    mapper.writeValue(response.getOutputStream(), ApiErrorResponse.of(error, MDC.get("traceId")));
  }
}
