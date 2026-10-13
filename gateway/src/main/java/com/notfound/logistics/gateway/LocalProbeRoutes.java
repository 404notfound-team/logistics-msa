package com.notfound.logistics.gateway;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Profile("local")
@Configuration
class LocalProbeRoutes {
    @Bean
    RouteLocator localProbeRouteLocator(RouteLocatorBuilder builder) {
        var routes = builder.routes();
        for (var service : new String[]{"users", "company", "hub", "orders", "delivery"}) {
            routes.route("local-probe-" + service, route -> route.path("/__infra/" + service)
                    .filters(filters -> filters.setPath("/internal/infra/ping"))
                    .uri("lb://" + service));
        }
        routes.route("local-trace", route -> route.path("/__infra/trace")
                .filters(filters -> filters.setPath("/internal/infra/trace"))
                .uri("lb://orders"));
        return routes.build();
    }
}
