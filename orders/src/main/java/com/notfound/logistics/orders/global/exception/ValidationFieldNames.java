package com.notfound.logistics.orders.global.exception;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/** Resolves binding paths with the same Jackson metadata used to read the request. */
final class ValidationFieldNames {
  private final ObjectMapper mapper;

  ValidationFieldNames(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  String bodyField(Object target, String path) {
    JavaType type = target == null ? null : mapper.constructType(target.getClass());
    var result = new StringBuilder();
    for (var segment : path.split("\\.")) {
      int index = segment.indexOf('[');
      var internalName = index < 0 ? segment : segment.substring(0, index);
      var suffix = index < 0 ? "" : segment.substring(index);
      var externalName = snakeCase(internalName);
      JavaType next = null;
      if (type != null) {
        for (var property : mapper.getDeserializationConfig().introspect(type).findProperties()) {
          if (property.getInternalName().equals(internalName)) {
            externalName = property.getName();
            next = property.getPrimaryType();
            break;
          }
        }
      }
      if (!result.isEmpty()) {
        result.append('.');
      }
      result.append(externalName).append(suffix);
      type = next;
      if (type != null && index >= 0) {
        type = type.getContentType();
      }
    }
    return result.toString();
  }

  String parameter(MethodParameter parameter) {
    var query = parameter.getParameterAnnotation(RequestParam.class);
    if (query != null) {
      return named(query.name(), query.value(), parameter);
    }
    var path = parameter.getParameterAnnotation(PathVariable.class);
    if (path != null) {
      return named(path.name(), path.value(), parameter);
    }
    var header = parameter.getParameterAnnotation(RequestHeader.class);
    if (header != null) {
      return named(header.name(), header.value(), parameter);
    }
    return parameter.getParameterName() == null ? "$" : snakeCase(parameter.getParameterName());
  }

  private String named(String name, String value, MethodParameter parameter) {
    if (!name.isEmpty()) {
      return name;
    }
    if (!value.isEmpty()) {
      return value;
    }
    return parameter.getParameterName() == null ? "$" : parameter.getParameterName();
  }

  private String snakeCase(String name) {
    return PropertyNamingStrategies.SnakeCaseStrategy.INSTANCE.translate(name);
  }
}
