package com.notfound.logistics.company.presentation.controller;

import com.notfound.logistics.common.api.ApiErrorResponse;
import com.notfound.logistics.common.api.ApiResponse;
import com.notfound.logistics.common.api.PageResponse;
import com.notfound.logistics.company.application.service.CompanyService;
import com.notfound.logistics.company.domain.model.Product;
import com.notfound.logistics.company.presentation.dto.ProductCreateRequest;
import com.notfound.logistics.company.presentation.dto.ProductResponse;
import com.notfound.logistics.company.presentation.dto.ProductUpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 상품은 Company Aggregate의 내부 엔티티라서 별도 서비스 없이 CompanyService를 통해 다룬다.
// Swagger의 ApiResponse는 공통 응답 타입 ApiResponse와 이름이 같아 전체 패키지 이름으로 쓴다.
@Tag(name = "Product", description = "업체 상품 API")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/companies/{company_id}/products")
@RequiredArgsConstructor
public class ProductController {
  // 공통 계약: 기본 정렬은 created_at 내림차순, 같은 시각이면 PK로 순서를 고정한다.
  private static final Sort DEFAULT_SORT =
      Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.ASC, "id"));

  private final CompanyService companyService;

  @Operation(
      summary = "상품 생성",
      description = "업체에 상품을 추가합니다. 성공 시 Location 헤더로 생성된 상품의 상세 조회 경로를 반환합니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "201",
        description = "생성 성공"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "요청 값 검증 실패 (필수 값 누락, 길이 초과, 음수 가격)",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = @ExampleObject(value = ErrorExamples.PRODUCT_CREATE_INVALID_EXAMPLE))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "업체를 찾을 수 없음 (COMPANY_NOT_FOUND)",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = @ExampleObject(value = ErrorExamples.COMPANY_NOT_FOUND_EXAMPLE)))
  })
  @PostMapping
  public ResponseEntity<ApiResponse<ProductResponse>> createProduct(
      @Parameter(description = "업체 ID", example = "7d752e7e-788b-485b-808e-fd438dfe8bae")
          @PathVariable("company_id")
          UUID companyId,
      @Valid @RequestBody ProductCreateRequest request) {
    Product product =
        companyService.createProduct(
            companyId, request.name(), request.description(), request.price());
    ProductResponse response = ProductResponse.from(product);
    // 이 서비스는 항상 Gateway를 거쳐 호출되므로 Location은 Gateway 기준 상대 경로로 준다.
    URI location = URI.create("/api/companies/" + companyId + "/products/" + response.productId());
    return ResponseEntity.created(location).body(new ApiResponse<>(response));
  }

  @Operation(
      summary = "상품 목록·검색",
      description = "업체의 상품을 상품명(부분 일치, 대소문자 무시)으로 검색합니다. 최신 생성순으로 정렬합니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "조회 성공 (결과가 없거나 마지막 페이지를 넘기면 빈 content)"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "page·size 범위 오류 또는 업체 ID 형식 오류",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = @ExampleObject(value = ErrorExamples.LIST_INVALID_EXAMPLE))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "업체를 찾을 수 없음 (COMPANY_NOT_FOUND)",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = @ExampleObject(value = ErrorExamples.COMPANY_NOT_FOUND_EXAMPLE)))
  })
  @GetMapping
  public ResponseEntity<ApiResponse<PageResponse<ProductResponse>>> getProducts(
      @Parameter(description = "업체 ID", example = "7d752e7e-788b-485b-808e-fd438dfe8bae")
          @PathVariable("company_id")
          UUID companyId,
      @Parameter(description = "상품명 (부분 일치)", example = "키보드")
          @RequestParam(name = "name", required = false)
          String name,
      @Parameter(description = "페이지 번호 (0부터 시작)")
          @RequestParam(name = "page", defaultValue = "0")
          @Min(0)
          int page,
      @Parameter(description = "페이지 크기 (1~100)")
          @RequestParam(name = "size", defaultValue = "20")
          @Min(1)
          @Max(100)
          int size) {
    Pageable pageable = PageRequest.of(page, size, DEFAULT_SORT);
    Page<Product> products = companyService.searchProducts(companyId, name, pageable);
    PageResponse<ProductResponse> response =
        new PageResponse<>(
            products.getContent().stream().map(ProductResponse::from).toList(),
            products.getNumber(),
            products.getSize(),
            products.getTotalElements());
    return ResponseEntity.ok(new ApiResponse<>(response));
  }

  @Operation(summary = "상품 상세 조회", description = "업체에 속한 상품을 조회합니다. 다른 업체의 상품이나 삭제된 상품은 조회되지 않습니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "조회 성공"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "잘못된 업체·상품 ID 형식",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = @ExampleObject(value = ErrorExamples.INVALID_ID_EXAMPLE))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "업체 또는 상품을 찾을 수 없음",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = {
                  @ExampleObject(name = "상품 없음", value = ErrorExamples.PRODUCT_NOT_FOUND_EXAMPLE),
                  @ExampleObject(name = "업체 없음", value = ErrorExamples.COMPANY_NOT_FOUND_EXAMPLE)
                }))
  })
  @GetMapping("/{product_id}")
  public ResponseEntity<ApiResponse<ProductResponse>> getProduct(
      @Parameter(description = "업체 ID", example = "7d752e7e-788b-485b-808e-fd438dfe8bae")
          @PathVariable("company_id")
          UUID companyId,
      @Parameter(description = "상품 ID", example = "5b7a9c1e-3f2d-4e8a-9b6c-1d2e3f4a5b6c")
          @PathVariable("product_id")
          UUID productId) {
    Product product = companyService.findProduct(companyId, productId);
    return ResponseEntity.ok(new ApiResponse<>(ProductResponse.from(product)));
  }

  @Operation(summary = "상품 수정", description = "보낸 필드만 변경합니다. 생략하거나 null인 필드는 기존 값을 유지합니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "수정 성공"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "요청 값 검증 실패 (공백 상품명, 길이 초과, 음수 가격, ID 형식 오류)",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = @ExampleObject(value = ErrorExamples.UPDATE_INVALID_EXAMPLE))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "업체 또는 상품을 찾을 수 없음",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = {
                  @ExampleObject(name = "상품 없음", value = ErrorExamples.PRODUCT_NOT_FOUND_EXAMPLE),
                  @ExampleObject(name = "업체 없음", value = ErrorExamples.COMPANY_NOT_FOUND_EXAMPLE)
                }))
  })
  @PatchMapping("/{product_id}")
  public ResponseEntity<ApiResponse<ProductResponse>> updateProduct(
      @Parameter(description = "업체 ID", example = "7d752e7e-788b-485b-808e-fd438dfe8bae")
          @PathVariable("company_id")
          UUID companyId,
      @Parameter(description = "상품 ID", example = "5b7a9c1e-3f2d-4e8a-9b6c-1d2e3f4a5b6c")
          @PathVariable("product_id")
          UUID productId,
      @Valid @RequestBody ProductUpdateRequest request) {
    Product product =
        companyService.updateProduct(
            companyId, productId, request.name(), request.description(), request.price());
    return ResponseEntity.ok(new ApiResponse<>(ProductResponse.from(product)));
  }

  @Operation(summary = "상품 논리 삭제", description = "상품을 논리 삭제하고 삭제 시각·작업자를 기록합니다. 삭제된 상품은 조회되지 않습니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "204",
        description = "삭제 성공"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "잘못된 업체·상품 ID 형식",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = @ExampleObject(value = ErrorExamples.INVALID_ID_EXAMPLE))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "업체 또는 상품을 찾을 수 없음 (이미 삭제된 상품 포함)",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = {
                  @ExampleObject(name = "상품 없음", value = ErrorExamples.PRODUCT_NOT_FOUND_EXAMPLE),
                  @ExampleObject(name = "업체 없음", value = ErrorExamples.COMPANY_NOT_FOUND_EXAMPLE)
                }))
  })
  @DeleteMapping("/{product_id}")
  public ResponseEntity<Void> deleteProduct(
      @Parameter(description = "업체 ID", example = "7d752e7e-788b-485b-808e-fd438dfe8bae")
          @PathVariable("company_id")
          UUID companyId,
      @Parameter(description = "상품 ID", example = "5b7a9c1e-3f2d-4e8a-9b6c-1d2e3f4a5b6c")
          @PathVariable("product_id")
          UUID productId) {
    companyService.deleteProduct(companyId, productId);
    return ResponseEntity.noContent().build();
  }
}
