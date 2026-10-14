package com.notfound.logistics.common.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Zero-based page metadata, independent of Spring Data's internal JSON representation. */
public record PageResponse<T>(
    List<T> content, int page, int size, @JsonProperty("total_elements") long totalElements) {
  public PageResponse {
    content = List.copyOf(content);
    if (page < 0 || size < 1 || size > 100 || totalElements < 0 || content.size() > size) {
      throw new IllegalArgumentException("Invalid page metadata");
    }
  }

  @JsonProperty(value = "total_pages", access = JsonProperty.Access.READ_ONLY)
  public long totalPages() {
    return totalElements / size + (totalElements % size == 0 ? 0 : 1);
  }
}
