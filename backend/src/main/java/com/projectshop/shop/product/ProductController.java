package com.projectshop.shop.product;

import java.net.URI;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;

/**
 * 상품을 등록하고 고치는 입구.
 *
 * <p>조회는 여기 없다. 공개 조회는 청크 8 이 만들고 <b>스코프를 목록 쿼리에 섞는 일</b>이 거기 있다.
 */
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;
    private final ProductQuery productQuery;
    private final ProductReviewService reviewService;

    ProductController(ProductService productService, ProductQuery productQuery,
            ProductReviewService reviewService) {

        this.productService = productService;
        this.productQuery = productQuery;
        this.reviewService = reviewService;
    }

    /**
     * 공개 목록. <b>로그인 없이 부르고 판정이 없다.</b>
     *
     * <p>파는 중인 상품만 나온다. 셀러가 자기 {@code draft} 를 보는 것은
     * {@code /api/seller/products} 다 — 조건의 성격이 달라서 경로를 갈랐다.
     */
    @GetMapping
    public ProductQuery.PublicPage list(
            @RequestParam(name = "seller_id", required = false) Long sellerId,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return productQuery.findPublic(sellerId, sort, page, size);
    }

    /**
     * 공개 상세. 목록과 같은 조건이라 <b>목록에 없는 상품은 여기서도 안 열린다.</b>
     *
     * <p>없는 상품과 아직 안 파는 상품이 같은 404 다. 가르면 주소를 하나씩 두드려서
     * 남의 {@code draft} 가 존재한다는 것을 알아낼 수 있다(`D5` 「403 이냐 404 냐」).
     */
    @GetMapping("/{productId}")
    public ProductQuery.PublicDetail detail(@PathVariable long productId) {
        return productQuery.findPublicDetail(productId);
    }

    /**
     * 상품·옵션·SKU 를 한 번에 받는다(`D4` — 상품은 한 덩어리로 산다).
     *
     * <p>{@code Location} 을 붙인다. 조회 경로가 청크 8 에서 생기지만 <b>경로 규칙은 지금 정해져 있고</b>,
     * 가입(`5-2`)이 헤더를 못 붙인 것은 그때 id 로 가리킬 경로 자체가 없어서였다.
     */
    @PostMapping
    public ResponseEntity<ProductService.Created> create(
            @AuthenticationPrincipal ShopUser user, @Valid @RequestBody ProductRequest request) {

        ProductService.Created created = productService.create(user.id(), request.toCommand());

        return ResponseEntity.created(URI.create("/api/products/" + created.productId()))
                .body(created);
    }

    /**
     * 통째로 바꾼다. <b>{@code PATCH} 가 아니라 {@code PUT} 인 이유</b>가 있다 —
     * 옵션과 SKU 가 전체 교체라 요청 본문이 곧 새 상태다. 부분 갱신의 뜻이 없다.
     */
    @PutMapping("/{productId}")
    public ProductService.Created replace(
            @AuthenticationPrincipal ShopUser user,
            @PathVariable long productId,
            @Valid @RequestBody ProductRequest request) {

        return productService.replace(user.id(), productId, request.toCommand());
    }

    @DeleteMapping("/{productId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal ShopUser user, @PathVariable long productId) {
        productService.delete(user.id(), productId);
    }

    /**
     * 검수 신청·승인·반려.
     *
     * <p>상태를 바꾸는 요청은 자원에 {@code PATCH} 를 쏘는 대신 <b>무슨 일이 일어나는지를
     * 경로에 적는다</b>(`D5`). {@code status: "on_sale"} 을 받으면 클라이언트가 전이표를 알아야 한다.
     */
    @PostMapping("/{productId}/submit-review")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void submitReview(@AuthenticationPrincipal ShopUser user, @PathVariable long productId) {
        reviewService.submit(user.id(), productId);
    }

    @PostMapping("/{productId}/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approve(@AuthenticationPrincipal ShopUser user, @PathVariable long productId) {
        reviewService.approve(user.id(), productId);
    }

    @PostMapping("/{productId}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(@AuthenticationPrincipal ShopUser user, @PathVariable long productId,
            @Valid @RequestBody RejectRequest request) {

        reviewService.reject(user.id(), productId, request.note());
    }

    /**
     * 셀러가 스스로 내린다. 품절·단종처럼 자기 사정이라 자기가 다시 올릴 수 있다.
     *
     * <p>관리자가 막는 것은 {@code /block} 이고 <b>그건 셀러가 못 푼다</b> —
     * 상태를 갈라 둔 이유가 그것이다.
     */
    @PostMapping("/{productId}/suspend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void suspend(@AuthenticationPrincipal ShopUser user, @PathVariable long productId) {
        reviewService.suspend(user.id(), productId);
    }

    @PostMapping("/{productId}/resume")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resume(@AuthenticationPrincipal ShopUser user, @PathVariable long productId) {
        reviewService.resume(user.id(), productId);
    }

    /**
     * 관리자가 판매를 막는다. <b>승인이 끝이 아니다</b> —
     * 위법 표시·위조품 신고·리콜은 팔기 시작한 뒤에 드러나고, 알고도 방치하면 연대책임을 진다.
     */
    @PostMapping("/{productId}/block")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void block(@AuthenticationPrincipal ShopUser user, @PathVariable long productId,
            @Valid @RequestBody BlockRequest request) {

        reviewService.block(user.id(), productId, request.reason());
    }

    /**
     * 제재를 푼다.
     *
     * @param request {@code back_to_sale} 이 참이면 오인이었다는 뜻이라 바로 판매로,
     *                거짓이면 고쳐서 다시 검수받으라는 뜻이라 {@code draft} 로 간다
     */
    @PostMapping("/{productId}/unblock")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unblock(@AuthenticationPrincipal ShopUser user, @PathVariable long productId,
            @Valid @RequestBody UnblockRequest request) {

        reviewService.unblock(user.id(), productId, Boolean.TRUE.equals(request.backToSale()));
    }

    /** @param note 셀러가 무엇을 고쳐야 하는지. 비우면 같은 것을 다시 올린다 */
    public record RejectRequest(@NotBlank @Size(max = 500) String note) {
    }

    /** @param reason 셀러가 왜 막혔는지 본다. 안 알려주면 고칠 수가 없다 */
    public record BlockRequest(@NotBlank @Size(max = 500) String reason) {
    }

    /** @param backToSale 비우면 거짓으로 본다 — 되돌리는 쪽이 아니라 다시 검수받는 쪽이 기본이다 */
    public record UnblockRequest(Boolean backToSale) {
    }

    /**
     * <b>기본형(`boolean`·`long`)을 안 쓴다.</b> Jackson 3 은 빠진 필드를 기본형에 넣을 때
     * 요청 전체를 깨뜨린다({@code FAIL_ON_NULL_FOR_PRIMITIVES} 가 기본으로 켜져 있다).
     * 그러면 "이 필드가 없다" 가 <b>"요청 형식이 맞지 않는다" 로 뭉개져서</b> 어느 칸인지 안 드러난다.
     *
     * <p>래퍼로 받고 {@code @NotNull} 을 걸면 Bean Validation 이 필드 이름을 짚어 준다.
     * 전역으로 그 기능을 끄는 방법도 있지만 그건 {@code null} 이 조용히 0·false 가 되는 길이다.
     *
     * @param commissionBp 비우면 셀러 기본 요율을 쓴다(`D3`)
     * @param withdrawalRestricted 비우면 제한 없음으로 본다. 대부분의 상품이 그렇다
     * @param supplyLeadDays 공급시기 약정 날수(영업일). <b>비우면 법정 3영업일이 걸린다</b> —
     *                       비운 것이 「빠르게 보낸다」가 아니라 「약정이 없다」다
     *                       (`D2` R21, 전자상거래법 제15조제1항 단서)
     * @param options      옵션이 없는 상품은 빈 배열이고 SKU 가 하나다
     */
    public record ProductRequest(
            @NotNull Long sellerId,
            @NotBlank @Size(max = 200) String name,
            String description,
            Integer commissionBp,
            Boolean withdrawalRestricted,
            String withdrawalRestrictionReason,
            @Min(0) @Max(60) Integer supplyLeadDays,
            @NotNull List<@Valid OptionRequest> options,
            @NotEmpty List<@Valid SkuRequest> skus) {

        ProductService.Command toCommand() {
            return new ProductService.Command(
                    sellerId, name, description, commissionBp,
                    Boolean.TRUE.equals(withdrawalRestricted), withdrawalRestrictionReason,
                    supplyLeadDays,
                    options.stream()
                            .map(o -> new ProductService.OptionCommand(o.name(), o.values()))
                            .toList(),
                    skus.stream()
                            .map(s -> new ProductService.SkuCommand(
                                    s.optionValues(), s.priceInclVat(), s.stockCount()))
                            .toList());
        }
    }

    public record OptionRequest(
            @NotBlank @Size(max = 50) String name,
            @NotEmpty List<@NotBlank String> values) {
    }

    /** @param priceInclVat 부가세를 포함한 판매가다(`D8`). 원 단위 정수라 소수가 없다 */
    public record SkuRequest(
            @NotNull List<@NotBlank String> optionValues,
            @NotNull @PositiveOrZero Long priceInclVat,
            @NotNull @PositiveOrZero Integer stockCount) {
    }
}
