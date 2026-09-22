package com.projectshop.shop.review;

import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectshop.shop.audit.AuditLog;
import com.projectshop.shop.auth.PermissionEvaluator;
import com.projectshop.shop.auth.PermissionEvaluator.Target;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;

/**
 * 후기를 쓰고 고치고 지운다(`Q160`).
 *
 * <h2>표가 먼저 막고 판정이 그다음이다</h2>
 *
 * <p><b>산 사람만·받아 본 뒤에만·한 줄에 하나</b>는 전부 DB 가 든다(`46`·`47`) —
 * 이 클래스는 <b>그 앞에서 고칠 수 있는 오류로 답하는 자리</b>다. 제약에 맡기면 500 이 나가고,
 * 부르는 쪽은 무엇을 고쳐야 하는지 모른다.
 *
 * <h2>대상을 판정에 실어 보낸다</h2>
 *
 * <p>후기의 주인은 쓴 사람이고 상태는 <b>그 주문 줄의 배송 상태</b>다 — 후기에는 상태가 없다
 * ({@link ReviewStatusPolicy}). 그 둘을 안 실으면 {@code own} 스코프와 상태 축이
 * 아무것도 안 덮는다(`Q161` 이 상품에서 겪은 것과 같다).
 */
@Service
public class ReviewService {

    private final JdbcClient jdbc;
    private final PermissionEvaluator evaluator;
    private final AuditLog auditLog;

    ReviewService(JdbcClient jdbc, PermissionEvaluator evaluator, AuditLog auditLog) {
        this.jdbc = jdbc;
        this.evaluator = evaluator;
        this.auditLog = auditLog;
    }

    /** 쓸 것 */
    public record NewReview(long orderItemId, int rating, String body) {}

    /**
     * 후기를 쓴다.
     *
     * <p><b>상품과 작성자를 여기서 안 받는다.</b> 주문 줄이 이미 그 둘을 안다 —
     * 받으면 어긋난 값이 들어올 수 있고, 그것을 막는 트리거가 500 으로 답한다(`46`).
     */
    @Transactional
    public long create(long actorUserId, NewReview command) {
        Line line = lineOf(command.orderItemId());

        if (line.buyerUserId() != actorUserId) {
            // 「산 사람이 아니다」를 따로 안 말한다 — 주문 줄 번호를 두드려 남의 구매를 셀 수 있다.
            throw new ShopException(ErrorCode.REVIEW_NOT_ALLOWED, "이 주문 줄에 후기를 쓸 수 없다");
        }
        requirePermission(actorUserId, "create", actorUserId, line.shipmentStatus());

        if (exists(command.orderItemId())) {
            throw new ShopException(ErrorCode.REVIEW_ALREADY_WRITTEN);
        }

        long reviewId = jdbc.sql("""
                        insert into review (order_item_id, product_id, user_id, rating, body)
                        values (:orderItem, :product, :user, :rating, :body)
                        returning review_id
                        """)
                .param("orderItem", command.orderItemId())
                .param("product", line.productId())
                .param("user", actorUserId)
                .param("rating", command.rating())
                .param("body", command.body())
                .query(Long.class)
                .single();

        auditLog.record(AuditLog.Kind.OUTCOME, "review.written", actorUserId,
                AuditLog.Target.of("review", reviewId),
                Map.of("product_id", line.productId()));

        return reviewId;
    }

    /**
     * 자기 후기를 고친다.
     *
     * <p><b>별점도 같이 고친다.</b> 글만 고치게 두면 「별은 그대로인데 말이 달라진」 후기가
     * 남고, 그것을 읽는 사람은 어느 쪽이 지금 생각인지 모른다.
     */
    @Transactional
    public void update(long actorUserId, long reviewId, int rating, String body) {
        Owned owned = ownedOf(reviewId);
        requirePermission(actorUserId, "update", owned.userId(), owned.shipmentStatus());

        jdbc.sql("""
                        update review set rating = :rating, body = :body
                         where review_id = :id and deleted_at is null
                        """)
                .param("rating", rating)
                .param("body", body)
                .param("id", reviewId)
                .update();

        auditLog.record(AuditLog.Kind.OUTCOME, "review.updated", actorUserId,
                AuditLog.Target.of("review", reviewId), Map.of());
    }

    /**
     * 자기 후기를 내린다. <b>행은 남는다</b> — 공개한 운영정책이 삭제 기준을 알리고 있어서
     * 「무엇이 왜 사라졌나」를 답할 수 있어야 한다(`D2` `R27`).
     */
    @Transactional
    public void delete(long actorUserId, long reviewId) {
        Owned owned = ownedOf(reviewId);
        requirePermission(actorUserId, "delete", owned.userId(), owned.shipmentStatus());

        jdbc.sql("update review set deleted_at = now() where review_id = :id and deleted_at is null")
                .param("id", reviewId)
                .update();

        auditLog.record(AuditLog.Kind.OUTCOME, "review.deleted", actorUserId,
                AuditLog.Target.of("review", reviewId), Map.of());
    }

    /** 주문 줄 하나가 아는 것. 상품과 산 사람과 배송 상태다 */
    private record Line(long productId, long buyerUserId, String shipmentStatus) {}

    private Line lineOf(long orderItemId) {
        return jdbc.sql("""
                        select s.product_id, o.user_id, so.status
                          from order_item oi
                          join sku s on s.sku_id = oi.sku_id
                          join seller_order so on so.seller_order_id = oi.seller_order_id
                          join shop_order o on o.order_id = so.order_id
                         where oi.order_item_id = :id
                        """)
                .param("id", orderItemId)
                .query((rs, rowNum) -> new Line(
                        rs.getLong("product_id"), rs.getLong("user_id"), rs.getString("status")))
                .optional()
                .orElseThrow(() -> new ShopException(ErrorCode.REVIEW_NOT_ALLOWED,
                        "이 주문 줄에 후기를 쓸 수 없다"));
    }

    /** 후기 하나의 주인과 그 주문 줄의 상태 */
    private record Owned(long userId, String shipmentStatus) {}

    private Owned ownedOf(long reviewId) {
        return jdbc.sql("""
                        select r.user_id, so.status
                          from review r
                          join order_item oi on oi.order_item_id = r.order_item_id
                          join seller_order so on so.seller_order_id = oi.seller_order_id
                         where r.review_id = :id and r.deleted_at is null
                        """)
                .param("id", reviewId)
                .query((rs, rowNum) -> new Owned(rs.getLong("user_id"), rs.getString("status")))
                .optional()
                .orElseThrow(() -> new ShopException(ErrorCode.REVIEW_NOT_FOUND));
    }

    private boolean exists(long orderItemId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        select exists(select 1 from review
                                       where order_item_id = :id and deleted_at is null)
                        """)
                .param("id", orderItemId)
                .query(Boolean.class)
                .single());
    }

    /**
     * <b>주인과 상태를 둘 다 싣는다.</b> 주인을 빼면 {@code own} 이 아무것도 안 덮고,
     * 상태를 빼면 {@link ReviewStatusPolicy} 가 안 걸린다 — 실어야 판정이 돈다.
     */
    private void requirePermission(long actorUserId, String action, long ownerUserId,
            String shipmentStatus) {
        Target target = new Target(ownerUserId, null, shipmentStatus);
        if (!evaluator.decide(actorUserId, "review", action, target).allowed()) {
            throw new ShopException(ErrorCode.REVIEW_NOT_ALLOWED);
        }
    }
}
