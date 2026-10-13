package com.notfound.logistics.users;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@EnableFeignClients
@SpringBootApplication
public class UsersApplication {
  public static void main(String[] args) {
    SpringApplication.run(UsersApplication.class, args);
  }
}
