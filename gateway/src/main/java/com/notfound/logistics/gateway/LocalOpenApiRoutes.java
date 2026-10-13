package com.notfound.logistics.gateway;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;

@Profile("local")
@Configuration(proxyBeanMethods = false)
class LocalOpenApiRoutes {
  @Bean
  RouteLocator openApiRouteLocator(RouteLocatorBuilder builder) {
    var routes = builder.routes();
    for (var service : new String[] {"users", "company", "hub", "orders", "delivery"}) {
      routes.route(
          "openapi-" + service,
          route ->
              route
                  .path("/v3/api-docs/" + service)
                  .and()
                  .method(HttpMethod.GET)
                  .filters(filters -> filters.setPath("/v3/api-docs"))
                  .uri("lb://" + service));
    }
    return routes.build();
  }
}
