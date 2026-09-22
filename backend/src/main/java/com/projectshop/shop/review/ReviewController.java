package com.projectshop.shop.review;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;
import com.projectshop.shop.support.ListQuery.Paging;

import org.springdoc.core.annotations.ParameterObject;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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
    private final ReviewQuery query;

    ReviewController(ReviewService reviews, ReviewQuery query) {
        this.reviews = reviews;
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
        long reviewId = reviews.create(user.id(),
                new ReviewService.NewReview(request.orderItemId(), request.rating(), request.body()));

        return ResponseEntity.created(URI.create("/api/reviews/" + reviewId))
                .body(new ReviewCreated(reviewId));
    }

    @PatchMapping("/api/reviews/{reviewId}")
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
}
