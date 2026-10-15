package com.notfound.logistics.company.infrastructure.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// 논리 삭제 시각 등 업무 시각을 한 곳에서 주입해 테스트에서 고정할 수 있게 한다. 시각은 UTC 기준이다.
@Configuration(proxyBeanMethods = false)
class ClockConfig {
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
