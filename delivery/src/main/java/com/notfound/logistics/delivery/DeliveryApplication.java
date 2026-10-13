package com.notfound.logistics.delivery;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@EnableFeignClients
@SpringBootApplication
@OpenAPIDefinition(
    info = @Info(title = "배송 API", version = "v1"),
    servers = @Server(url = "/", description = "Gateway"))
public class DeliveryApplication {
  public static void main(String[] args) {
    SpringApplication.run(DeliveryApplication.class, args);
  }
}
