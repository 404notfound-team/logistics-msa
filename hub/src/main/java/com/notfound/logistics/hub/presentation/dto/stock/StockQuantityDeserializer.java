package com.notfound.logistics.hub.presentation.dto.stock;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;

/** Rejects coercion so fractional or textual quantities cannot bypass validation. */
public class StockQuantityDeserializer extends JsonDeserializer<Long> {
  @Override
  public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
    if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
      return context.reportInputMismatch(Long.class, "quantity must be a JSON integer");
    }
    return parser.getLongValue();
  }
}
