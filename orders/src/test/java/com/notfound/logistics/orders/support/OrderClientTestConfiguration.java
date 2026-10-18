package com.notfound.logistics.orders.support;

import com.notfound.logistics.orders.infrastructure.client.CompanyClient;
import com.notfound.logistics.orders.infrastructure.client.DeliveryClient;
import com.notfound.logistics.orders.infrastructure.client.StockClient;
import com.notfound.logistics.orders.infrastructure.notification.SlackNotifier;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class OrderClientTestConfiguration {
  @Bean
  static BeanFactoryPostProcessor selectTestClients() {
    return factory -> {
      for (Class<?> type :
          new Class<?>[] {CompanyClient.class, StockClient.class, DeliveryClient.class}) {
        factory.getBeanDefinition(type.getName()).setPrimary(false);
      }
    };
  }

  @Bean
  @ConditionalOnMissingBean(OrderClientFixture.class)
  OrderClientFixture orderClientFixture() {
    return new OrderClientFixture();
  }

  @Bean
  @Primary
  CompanyClient testCompanies(OrderClientFixture fixture) {
    return fixture.companies;
  }

  @Bean
  @Primary
  StockClient testStocks(OrderClientFixture fixture) {
    return fixture.stocks;
  }

  @Bean
  @Primary
  DeliveryClient testDeliveries(OrderClientFixture fixture) {
    return fixture.deliveries;
  }

  @Bean
  @Primary
  SlackNotifier testNotifications(OrderClientFixture fixture) {
    return fixture.notifications;
  }
}
