package com.notfound.logistics.orders.presentation.exception;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.notfound.logistics.orders.domain.exception.OrderBusinessException;
import com.notfound.logistics.orders.domain.exception.OrderErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

class OrderExceptionHandlerTest {
  private MockMvc mvc;
  private LocalValidatorFactoryBean validator;

  @BeforeEach
  void setUp() {
    var mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();
    mvc =
        MockMvcBuilders.standaloneSetup(new FixtureController())
            .setControllerAdvice(new OrderExceptionHandler(mapper))
            .setValidator(validator)
            .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
            .build();
  }

  @AfterEach
  void tearDown() {
    MDC.clear();
    validator.close();
  }

  @Test
  void bodyValidationUsesExternalNamesIncludingExplicitNamesAndNestedIndexes() throws Exception {
    mvc.perform(
            post("/fixture/body")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                {"supplier_company_id":"", "delivery_note":"", "order_items":[{"product_id":null}]}
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(
            jsonPath("$.field_errors[*].field")
                .value(
                    containsInAnyOrder(
                        "supplier_company_id", "delivery_note", "order_items[0].product_id")))
        .andExpect(jsonPath("$.error").doesNotExist())
        .andExpect(content().string(not(containsString("supplierCompanyId"))))
        .andExpect(content().string(not(containsString("secret-validation-detail"))));
  }

  @Test
  void objectValidationUsesTheRootPathAndDoesNotExposeTheRejectedInput() throws Exception {
    mvc.perform(
            post("/fixture/body")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                {"supplier_company_id":"secret-input", "delivery_note":"note", "order_items":[]}
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field_errors[0].field").value("$"))
        .andExpect(content().string(not(containsString("secret-input"))));
  }

  @Test
  void requestParameterValidationUsesTheDeclaredQueryName() throws Exception {
    mvc.perform(get("/fixture/query").param("page_size", "0"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field_errors[0].field").value("page_size"));
  }

  @Test
  void queryObjectBindingKeepsTheActualBoundFieldName() throws Exception {
    mvc.perform(get("/fixture/query-object").param("pageSize", "bad-secret"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field_errors[0].field").value("pageSize"))
        .andExpect(content().string(not(containsString("bad-secret"))));
  }

  @Test
  void malformedJsonTypeMismatchAndMissingParametersAreSafeBadRequests() throws Exception {
    mvc.perform(
            post("/fixture/body").contentType(MediaType.APPLICATION_JSON).content("{secret-input"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(content().string(not(containsString("secret-input"))));
    mvc.perform(get("/fixture/id/secret-invalid-uuid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(content().string(not(containsString("secret-invalid-uuid"))));
    mvc.perform(get("/fixture/query"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  void standardHttpErrorsPreserveTheirStatusAndHeaders() throws Exception {
    mvc.perform(get("/fixture/body"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(header().string("Allow", containsString("POST")))
        .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    mvc.perform(post("/fixture/body").contentType(MediaType.TEXT_PLAIN).content("input"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
  }

  @Test
  void businessErrorsKeepTheirStatusAndTraceId() throws Exception {
    MDC.put("traceId", "fixture-trace");
    mvc.perform(get("/fixture/business").param("conflict", "false"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"))
        .andExpect(jsonPath("$.message").value("주문을 찾을 수 없습니다."))
        .andExpect(jsonPath("$.trace_id").value("fixture-trace"))
        .andExpect(jsonPath("$.field_errors").isEmpty());
    mvc.perform(get("/fixture/business").param("conflict", "true"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT"))
        .andExpect(jsonPath("$.message").value("현재 주문 상태에서는 요청을 처리할 수 없습니다."));
  }

  @Test
  void unexpectedAndReturnValueValidationFailuresRemainServerErrors() throws Exception {
    mvc.perform(get("/fixture/unexpected"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
        .andExpect(content().string(not(containsString("database-secret"))));
    mvc.perform(get("/fixture/return-value"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
        .andExpect(jsonPath("$.field_errors").isEmpty());
  }

  record Body(
      @NotBlank(message = "secret-validation-detail") String supplierCompanyId,
      @JsonProperty("delivery_note") @NotBlank String requestNote,
      @Valid @NotNull List<Item> orderItems) {}

  record Item(@NotNull UUID productId) {}

  static class Query {
    private int pageSize;

    public int getPageSize() {
      return pageSize;
    }

    public void setPageSize(int pageSize) {
      this.pageSize = pageSize;
    }
  }

  @RestController
  static class FixtureController {
    @InitBinder
    void bind(WebDataBinder binder) {
      if (binder.getTarget() instanceof Body) {
        binder.addValidators(
            new Validator() {
              @Override
              public boolean supports(Class<?> type) {
                return type == Body.class;
              }

              @Override
              public void validate(Object target, Errors errors) {
                if (((Body) target).orderItems() != null
                    && ((Body) target).orderItems().isEmpty()) {
                  errors.reject("invalid_order", "secret-validation-detail");
                }
              }
            });
      }
    }

    @PostMapping("/fixture/body")
    void body(@Valid @RequestBody Body body) {}

    @GetMapping("/fixture/query")
    void query(@RequestParam("page_size") @Min(1) int pageSize) {}

    @GetMapping("/fixture/query-object")
    void queryObject(Query query) {}

    @GetMapping("/fixture/id/{id}")
    void id(@PathVariable("id") UUID id) {}

    @GetMapping("/fixture/business")
    void business(@RequestParam("conflict") boolean conflict) {
      throw new OrderBusinessException(
          conflict ? OrderErrorCode.ORDER_STATE_CONFLICT : OrderErrorCode.ORDER_NOT_FOUND);
    }

    @GetMapping("/fixture/unexpected")
    void unexpected() {
      throw new IllegalStateException("database-secret");
    }

    @GetMapping("/fixture/return-value")
    @NotNull
    String returnValue() {
      return null;
    }
  }
}
