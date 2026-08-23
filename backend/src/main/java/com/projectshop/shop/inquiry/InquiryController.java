package com.projectshop.shop.inquiry;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;

/**
 * 문의를 내고 읽고 답하는 입구(청크 59).
 *
 * <h2>경로가 관객별로 갈린다</h2>
 *
 * <p>목록 셋이 서로 다른 경로다 — 상품의 공개 Q&A, 내가 낸 것, 내 셀러 상품에 달린 것.
 * <b>하나로 두고 로그인 상태에 따라 행을 거르면</b> 응답 record 가 하나가 되고,
 * 그 record 에 <b>비공개 본문을 담을 칸이 상시 존재한다</b> — 거르는 규칙을 한 줄 빠뜨리면
 * 남의 비공개 문의가 그대로 나간다(사용자 선택, `D23` 축 2).
 *
 * <p>갈라 두면 <b>조건이 「숨기는 것」이 아니라 「고르는 것」</b>이 된다. 빠뜨렸을 때
 * 결과가 더 나오는 것이 아니라 빈다.
 *
 * <h2>클래스 레벨 매핑을 안 건다</h2>
 *
 * <p>경로가 {@code /api/products/…}·{@code /api/me/…}·{@code /api/seller/…} 로 흩어져서
 * 공통 접두어가 {@code /api} 뿐인데, 거기에 걸면 <b>앞으로 이 클래스에 붙는 모든 경로가
 * {@code /api/*} 를 조용히 차지한다</b> — `마무리`(2026-08-22)가 {@code SellerController} 에서
 * 되돌린 자리다. 패키지는 자원이고 경로는 관객이다(`D23`).
 */
@RestController
public class InquiryController {

    private final InquiryService inquiries;
    private final InquiryQuery query;

    InquiryController(InquiryService inquiries, InquiryQuery query) {
        this.inquiries = inquiries;
        this.query = query;
    }

    /**
     * 낼 문의.
     *
     * @param kind      {@code PRODUCT}·{@code PROCESSING_STOP}·{@code ACCESS_OBJECTION}·{@code DISPUTE}
     * @param productId 상품 문의에만 보낸다. 그 밖에는 보내면 422 다
     * @param isPublic  안 보내면 공개다. 계정에 붙는 요구에는 뜻이 없다
     */
    public record NewInquiryRequest(
            @NotBlank @Pattern(regexp = "PRODUCT|ORDER|PROCESSING_STOP|ACCESS_OBJECTION|DISPUTE")
            String kind,
            @Positive Long productId,
            String sellerOrderNumber,
            @NotBlank @Size(max = 2000) String question,
            Boolean isPublic) {}

    /** 문의 하나가 섰다는 것만 알린다. 내용은 「내 문의」로 읽는다 */
    public record InquiryCreated(String inquiryNumber) {}

    /**
     * 문의를 낸다. <b>법정 요구도 이 입구로 온다</b>(`R25`·`R28`).
     *
     * <p>201 과 {@code Location} 을 준다(`D5`). 가리키는 곳이 「내 문의」인 이유는
     * <b>단건 조회가 관객별로 갈려 있어서</b>다 — 낸 사람에게 맞는 경로가 그쪽이다.
     */
    @PostMapping("/api/inquiries")
    public ResponseEntity<InquiryCreated> create(@AuthenticationPrincipal ShopUser user,
            @Valid @RequestBody NewInquiryRequest request) {
        String number = inquiries.create(user.id(), new InquiryService.NewInquiry(
                storedEnum(request.kind()),
                request.productId(),
                request.sellerOrderNumber(),
                request.question(),
                request.isPublic() == null || request.isPublic()));

        return ResponseEntity.created(URI.create("/api/me/inquiries"))
                .body(new InquiryCreated(number));
    }

    /** 답할 내용 */
    public record AnswerRequest(@NotBlank @Size(max = 2000) String answer) {}

    /**
     * 문의에 답한다.
     *
     * <p>셀러는 자기 상품에 달린 것만, 관리자는 전부 답한다(`V54`).
     * <b>법정 요구에는 셀러가 없어서</b> 셀러 스코프가 자연히 안 걸린다 —
     * 그 요구는 개인정보처리자인 우리에게 온 것이다.
     */
    @PostMapping("/api/inquiries/{inquiryNumber}/answer")
    public ResponseEntity<Void> answer(@AuthenticationPrincipal ShopUser user,
            @PathVariable String inquiryNumber, @Valid @RequestBody AnswerRequest request) {
        inquiries.answer(user.id(), inquiryNumber, request.answer());
        return ResponseEntity.noContent().build();
    }

    /**
     * 내릴 사유.
     *
     * @param reason {@code ADVERTISEMENT}(정보통신망법 제50조의7) 또는 {@code ABUSE}(약관)
     */
    public record BlockRequest(
            @NotBlank @Pattern(regexp = "ADVERTISEMENT|ABUSE") String reason) {}

    /**
     * 게시를 중단한다(청크 59-2). <b>관리자만이다</b>(`V58`).
     *
     * <p>제50조의7 의 의무자가 운영자라 그 판단도 운영자가 한다 — 셀러에게 열면
     * <b>불리한 질문을 광고로 몰아 내리는 자리</b>가 같이 생기고, 구매 전 문의라
     * 그 질문을 못 보게 되는 사람은 살까 말까 하는 사람이다.
     */
    @PostMapping("/api/inquiries/{inquiryNumber}/block")
    public ResponseEntity<Void> block(@AuthenticationPrincipal ShopUser user,
            @PathVariable String inquiryNumber, @Valid @RequestBody BlockRequest request) {
        inquiries.block(user.id(), inquiryNumber, storedEnum(request.reason()));
        return ResponseEntity.noContent().build();
    }

    /**
     * 낸 문의를 거둔다(청크 59-1). <b>답이 나간 것은 못 거둔다</b> —
     * 셀러가 이미 답을 썼는데 질문이 사라지면 그 답이 무엇에 대한 것인지가 없어진다.
     */
    @PostMapping("/api/inquiries/{inquiryNumber}/withdrawal")
    public ResponseEntity<Void> withdraw(@AuthenticationPrincipal ShopUser user,
            @PathVariable String inquiryNumber) {
        inquiries.withdraw(user.id(), inquiryNumber);
        return ResponseEntity.noContent().build();
    }

    /**
     * 상품 하나의 공개 문의. <b>로그인이 필요 없다.</b>
     *
     * <p>구매 전 문의라 살까 말까 하는 사람이 읽는 자리다 — 로그인을 요구하면
     * 그 자리가 닫힌다. 비공개와 내려간 게시물은 애초에 안 뽑힌다.
     */
    @GetMapping("/api/products/{productId}/inquiries")
    public InquiryQuery.Page<InquiryQuery.PublicEntry> ofProduct(@PathVariable long productId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return query.findPublic(productId, page, size);
    }

    /** 내가 낸 문의 전부. 비공개도 내려간 것도 보인다 */
    @GetMapping("/api/me/inquiries")
    public InquiryQuery.Page<InquiryQuery.Entry> mine(@AuthenticationPrincipal ShopUser user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return query.findMine(user.id(), page, size);
    }

    /** 내 셀러 상품에 달린 문의. 계정에 붙는 요구는 여기 안 나온다 */
    @GetMapping("/api/seller/inquiries")
    public InquiryQuery.Page<InquiryQuery.Entry> forSeller(@AuthenticationPrincipal ShopUser user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return query.findForSeller(user.id(), page, size);
    }

    /** 열거값은 API 가 대문자고 저장은 소문자다(`D5`). 종류와 사유가 같은 규칙을 쓴다 */
    private static String storedEnum(String kind) {
        return kind.toLowerCase(java.util.Locale.ROOT);
    }
}
