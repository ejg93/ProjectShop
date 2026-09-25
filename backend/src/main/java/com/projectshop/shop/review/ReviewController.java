package com.projectshop.shop.review;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;
import com.projectshop.shop.support.ImagePipeline;
import com.projectshop.shop.support.ListQuery.Paging;

import org.springdoc.core.annotations.ParameterObject;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 후기를 쓰고 읽는다(`Q160`).
 *
 * <p><b>읽기는 로그인이 필요 없다.</b> 후기는 공개 글이고, 그것이 후기의 목적이다 —
 * 산 사람만 <b>쓴다</b>는 것과 누구나 <b>읽는다</b>는 것은 다른 축이다(`47`).
 *
 * <p><b>본문 하한이 10자다</b>(`Q160` 이 정했다). `46` 이 「몇 글자부터 후기인가는 입구가
 * 정할 일」로 남긴 칸이고, 한 글자 후기는 다른 소비자에게 정보가 아니다.
 * 상한 2000 은 DB 제약과 짝이고 {@code LengthConstraintTest} 가 그 둘을 대조한다.
 */
@RestController
public class ReviewController {

    private final ReviewService reviews;
    private final ReviewModerationService moderation;
    private final ReviewImageService images;
    private final ReviewQuery query;

    ReviewController(ReviewService reviews, ReviewModerationService moderation, ReviewImageService images,
            ReviewQuery query) {
        this.reviews = reviews;
        this.moderation = moderation;
        this.images = images;
        this.query = query;
    }

    /**
     * 쓸 것.
     *
     * @param orderItemId 어느 주문 줄에 대한 후기인가. <b>상품 번호가 아니다</b> —
     *        「이 상품을 샀다」가 아니라 「이 주문의 이 줄을 샀다」라야 자격을 센다(`46`)
     */
    public record NewReviewRequest(
            @Min(1) long orderItemId,
            @Min(1) @Max(5) int rating,
            @NotBlank @Size(min = 10, max = 2000) String body) {}

    /**
     * 고칠 것. 별점도 같이 온다 — 글만 고치면 별과 말이 갈린다.
     *
     * <p><b>이 record 와 위 것이 {@code public} 인 근거는 대조다</b> —
     * {@code LengthConstraintTest} 가 {@code @Size} 의 수를 DB 제약과 맞춰 보고,
     * 그 시험이 다른 패키지에 있다(`D23` 「{@code public} 에는 근거가 있어야 한다」).
     */
    public record EditReviewRequest(
            @Min(1) @Max(5) int rating,
            @NotBlank @Size(min = 10, max = 2000) String body) {}

    record ReviewCreated(long reviewId) {}

    /**
     * <b>201 과 {@code Location} 을 준다</b>(`D5`). 가리키는 곳은 그 상품의 후기 목록이다 —
     * 후기 단건 조회는 없고, 쓴 사람이 자기 글을 보는 자리가 거기다.
     */
    @PostMapping("/api/reviews")
    ResponseEntity<ReviewCreated> create(@AuthenticationPrincipal ShopUser user,
            @Valid @RequestBody NewReviewRequest request) {
        ReviewService.Created written = reviews.create(user.id(),
                new ReviewService.NewReview(request.orderItemId(), request.rating(), request.body()));

        return ResponseEntity
                .created(URI.create("/api/products/" + written.productId() + "/reviews"))
                .body(new ReviewCreated(written.reviewId()));
    }

    @PatchMapping(value = "/api/reviews/{reviewId}", consumes = "application/merge-patch+json")
    ResponseEntity<Void> update(@AuthenticationPrincipal ShopUser user,
            @PathVariable long reviewId, @Valid @RequestBody EditReviewRequest request) {
        reviews.update(user.id(), reviewId, request.rating(), request.body());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/reviews/{reviewId}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal ShopUser user,
            @PathVariable long reviewId) {
        reviews.delete(user.id(), reviewId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 그 상품의 후기.
     *
     * <p><b>{@code user} 가 {@code null} 일 수 있다</b> — 로그인 안 한 사람도 읽는다.
     * 그 값은 {@code mine} 을 가르는 데만 쓴다.
     */
    @GetMapping("/api/products/{productId}/reviews")
    ReviewQuery.Result find(@AuthenticationPrincipal ShopUser user,
            @PathVariable long productId, @ParameterObject Paging paging) {
        return query.findByProduct(user == null ? null : user.id(), productId, paging);
    }

    /**
     * 답글(`Q167`). 상한 2000 은 {@code review_reply_body_length_check} 와 짝이고
     * {@code LengthConstraintTest} 가 대조한다 — 그래서 {@code public} 이다.
     */
    public record ReplyRequest(@NotBlank @Size(max = 2000) String body) {}

    /** 신고 사유. 바깥은 대문자다(`D5`) — 안쪽 값은 {@link ReviewReason} 이 든다 */
    record ReportRequest(
            @NotBlank @Pattern(regexp = "ADVERTISEMENT|ABUSE|UNRELATED|PRIVACY") String reason) {}

    /** 왜 되살리나. 이의제기 문의 번호나 판단 근거를 적는다 — 감사에 남는다 */
    record RestoreRequest(@NotBlank @Size(max = 500) String note) {}

    /** 답글을 달거나 고친다. 후기 하나에 답글 하나라 자리가 정해져 있어 {@code PUT} 이다 */
    @PutMapping("/api/reviews/{reviewId}/reply")
    ResponseEntity<Void> reply(@AuthenticationPrincipal ShopUser user,
            @PathVariable long reviewId, @Valid @RequestBody ReplyRequest request) {
        moderation.reply(user.id(), reviewId, request.body());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/reviews/{reviewId}/reply")
    ResponseEntity<Void> deleteReply(@AuthenticationPrincipal ShopUser user, @PathVariable long reviewId) {
        moderation.deleteReply(user.id(), reviewId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 신고한다. <b>동작이라 {@code 204} 다</b> — 신고 단건을 읽는 자리가 사는 사람에게 없어서
     * {@code Location} 이 가리킬 곳이 없다({@code InquiryController} 의 게시 중단과 같은 모양).
     */
    @PostMapping("/api/reviews/{reviewId}/report")
    ResponseEntity<Void> report(@AuthenticationPrincipal ShopUser user,
            @PathVariable long reviewId, @Valid @RequestBody ReportRequest request) {
        moderation.report(user.id(), reviewId, ReviewReason.ofRequest(request.reason()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/review-reports/{reportId}/accept")
    ResponseEntity<Void> acceptReport(@AuthenticationPrincipal ShopUser user, @PathVariable long reportId) {
        moderation.acceptReport(user.id(), reportId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/review-reports/{reportId}/reject")
    ResponseEntity<Void> rejectReport(@AuthenticationPrincipal ShopUser user, @PathVariable long reportId) {
        moderation.rejectReport(user.id(), reportId);
        return ResponseEntity.noContent().build();
    }

    /** 내린 후기를 되살린다. 이 입구 말고는 되살리는 길이 없다 — 감사가 여기서 남는다 */
    @PostMapping("/api/reviews/{reviewId}/restore")
    ResponseEntity<Void> restore(@AuthenticationPrincipal ShopUser user,
            @PathVariable long reviewId, @Valid @RequestBody RestoreRequest request) {
        moderation.restore(user.id(), reviewId, request.note());
        return ResponseEntity.noContent().build();
    }

    /**
     * 내가 쓴 후기(`Q167`). <b>세션을 싣는 입구다</b> — 상품 상세는 {@code apiPublic} 이라
     * {@code mine} 이 언제나 거짓이고, 고치기·지우기는 여기서 연다.
     */
    @GetMapping("/api/me/reviews")
    ReviewQuery.MyResult mine(@AuthenticationPrincipal ShopUser user, @ParameterObject Paging paging) {
        return query.findMine(user.id(), paging);
    }

    /** 내가 답할 수 있는 셀러들의 상품 후기 */
    @GetMapping("/api/seller/reviews")
    ReviewQuery.SellerResult forSeller(@AuthenticationPrincipal ShopUser user,
            @ParameterObject Paging paging) {
        return query.findForSeller(user.id(), paging);
    }

    /**
     * 처리할 신고. <b>기본은 접수된 것이다</b> — 관리자가 여는 이유가 그것이다.
     *
     * @param status {@code PENDING}·{@code ACCEPTED}·{@code REJECTED}
     */
    @GetMapping("/api/admin/review-reports")
    ReviewQuery.ReportResult reports(@AuthenticationPrincipal ShopUser user,
            @RequestParam(defaultValue = "PENDING") String status, @ParameterObject Paging paging) {
        return query.findReports(user.id(), ReviewReportStatus.ofRequest(status), paging);
    }

    /**
     * 후기에 사진을 붙인다(`Q159`). 경로가 후기 아래다 — 사진은 후기 없이 존재하지 않는다.
     * 상품 사진과 같이 {@code 201} 이고 본문에 번호가 간다.
     */
    @PostMapping("/api/reviews/{reviewId}/images")
    ResponseEntity<ReviewImageService.Uploaded> uploadImage(@AuthenticationPrincipal ShopUser user,
            @PathVariable long reviewId, @RequestPart("file") MultipartFile file) {
        ReviewImageService.Uploaded uploaded = images.upload(user.id(), reviewId, incoming(file));
        // `Location` 은 그 사진을 드는 GET — 그 상품의 후기 목록이다(`Q227`, `D5` 「상태 코드」). 본문은 그대로 번호 하나다.
        return ResponseEntity.created(URI.create("/api/products/" + query.productIdOf(reviewId) + "/reviews"))
                .body(uploaded);
    }

    /** 사진 한 장을 뗀다. 경로가 사진 번호 하나다 — 어느 후기의 것인지는 행이 든다 */
    @DeleteMapping("/api/review-images/{reviewImageId}")
    ResponseEntity<Void> deleteImage(@AuthenticationPrincipal ShopUser user, @PathVariable long reviewImageId) {
        images.delete(user.id(), reviewImageId);
        return ResponseEntity.noContent().build();
    }

    /** <b>웹 타입이 여기서 끝난다</b>({@code D23} 「계층」). 서비스는 이름과 바이트만 받는다 */
    private static ImagePipeline.Incoming incoming(MultipartFile file) {
        try {
            return new ImagePipeline.Incoming(file.getOriginalFilename(), file.getBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
