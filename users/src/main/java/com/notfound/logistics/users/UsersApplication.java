package com.notfound.logistics.users;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@EnableFeignClients
@SpringBootApplication
@OpenAPIDefinition(
    info = @Info(title = "사용자 API", version = "v1"),
    servers = @Server(url = "/", description = "Gateway"))
public class UsersApplication {
  public static void main(String[] args) {
    SpringApplication.run(UsersApplication.class, args);
  }
}
