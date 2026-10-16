package com.notfound.logistics.hub.support;

import com.notfound.logistics.hub.infrastructure.security.HubMethodSecurityConfiguration;
import com.notfound.logistics.hub.infrastructure.security.HubSecurityConfiguration;
import com.notfound.logistics.hub.infrastructure.security.HubSecurityExceptionHandler;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import({
  HubSecurityConfiguration.class,
  HubMethodSecurityConfiguration.class,
  HubSecurityExceptionHandler.class
})
@TestPropertySource(
    properties = {
      "hub.jwt.secret=" + HubSecurityTestTokens.SECRET,
      "hub.security.internal.orders-token=" + HubSecurityTestTokens.ORDERS,
      "hub.security.internal.users-token=" + HubSecurityTestTokens.USERS,
      "hub.security.internal.company-token=" + HubSecurityTestTokens.COMPANY,
      "hub.security.internal.delivery-token=" + HubSecurityTestTokens.DELIVERY
    })
public @interface HubSecurityTest {}
