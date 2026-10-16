package com.notfound.logistics.hub.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Sort;

class QuerySortTest {
  @Test
  void defaultsAndExplicitSortRemainStable() {
    assertThat(QuerySort.resolve(null, "desc").toList())
        .containsExactly(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    assertThat(QuerySort.resolve(null, "asc").toList())
        .containsExactly(Sort.Order.asc("createdAt"), Sort.Order.desc("id"));
    assertThat(QuerySort.resolve("created_at,ASC", "desc").toList())
        .containsExactly(Sort.Order.asc("createdAt"), Sort.Order.desc("id"));
    assertThat(QuerySort.resolve("id,asc", "desc").toList()).containsExactly(Sort.Order.asc("id"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "name,asc",
        "createdAt,desc",
        "quantity,asc",
        "created_at",
        "created_at,desc,id",
        "created_at,bad",
        "",
        "id,",
        "id desc",
        "id,asc;drop table"
      })
  void arbitraryEntityPropertiesAndMalformedSortAreRejected(String sort) {
    assertThatThrownBy(() -> QuerySort.resolve(sort, "desc"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
