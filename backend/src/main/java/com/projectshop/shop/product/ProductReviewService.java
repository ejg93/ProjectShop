package com.projectshop.shop.product;

import java.util.LinkedHashMap;
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
 * 상품 검수(`D2` R15).
 *
 * <p>거짓·과장 광고는 시스템이 판단할 수 없다. 그래서 요건이 <b>"사람이 검수하는 경로가 있느냐"</b> 로
 * 바뀐다. 이 클래스가 그 경로다.
 *
 * <p>승인·반려를 {@code product:update} 가 아니라 <b>{@code product:review} 로 가른다.</b>
 * 나중에 검수만 하는 역할을 만들 때 {@code update} 를 주면 그 사람이 상품 이름과 가격까지 고칠 수 있다 —
 * 표시·광고 책임은 셀러에게 있는데 우리가 고치면 그 책임이 흐려진다.
 */
@Service
public class ProductReviewService {

    private final JdbcClient jdbc;
    private final PermissionEvaluator evaluator;
    private final AuditLog auditLog;

    ProductReviewService(JdbcClient jdbc, PermissionEvaluator evaluator, AuditLog auditLog) {
        this.jdbc = jdbc;
        this.evaluator = evaluator;
        this.auditLog = auditLog;
    }

    private record Product(long sellerId, String status) {
    }

    /**
     * 검수를 신청한다. {@code draft} → {@code pending_review}.
     *
     * <p>셀러가 부른다. 자기 상품을 고칠 수 있으면 검수도 신청할 수 있다 —
     * 신청 자체는 남에게 영향이 없어서 권한을 더 가르지 않는다.
     */
    @Transactional
    public void submit(long actorUserId, long productId) {
        Product product = load(productId);
        requireUpdatePermission(actorUserId, product.sellerId());
        requireStatus(product, "draft");

        jdbc.sql("""
                        update product set status = 'pending_review', review_note = null
                         where product_id = :id
                        """)
                .param("id", productId)
                .update();

        auditLog.record("product.review_submitted", actorUserId,
                AuditLog.Target.of("product", productId), Map.of("seller_id", product.sellerId()));
    }

    /**
     * 승인한다. {@code pending_review} → {@code on_sale}.
     *
     * <p><b>셀러가 {@code active} 여야 한다.</b> 전자상거래법 제20조② 가 신원정보를 청약 전까지
     * 확인하라고 하고 {@code on_sale} 이 청약이 가능해지는 지점이다(`D2` R1).
     *
     * <p>DB 트리거도 같은 것을 막는다. 여기서 먼저 보는 이유는 <b>이유를 알려주려는 것</b>이다 —
     * 트리거에 걸리면 {@code DataIntegrityViolationException} 이 되어 500 으로 뭉개진다.
     */
    @Transactional
    public void approve(long actorUserId, long productId) {
        Product product = load(productId);
        requireReviewPermission(actorUserId, product.sellerId());
        requireStatus(product, "pending_review");
        requireVerifiedSeller(product.sellerId());

        jdbc.sql("update product set status = 'on_sale', review_note = null where product_id = :id")
                .param("id", productId)
                .update();

        auditLog.record("product.review_approved", actorUserId,
                AuditLog.Target.of("product", productId), Map.of("seller_id", product.sellerId()));
    }

    /**
     * 반려한다. {@code pending_review} → {@code draft}.
     *
     * <p>사유가 두 군데 간다. 컬럼은 <b>"지금 무엇을 고쳐야 하나"</b> 를 셀러에게 답하고,
     * 감사 로그는 <b>"누가 언제 왜 반려했나"</b> 를 남긴다.
     * 감사 로그에만 두면 셀러가 못 읽는다 — {@code audit:read} 를 주면 남의 기록까지 보인다.
     */
    @Transactional
    public void reject(long actorUserId, long productId, String note) {
        Product product = load(productId);
        requireReviewPermission(actorUserId, product.sellerId());
        requireStatus(product, "pending_review");

        jdbc.sql("update product set status = 'draft', review_note = :note where product_id = :id")
                .param("note", note)
                .param("id", productId)
                .update();

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("seller_id", product.sellerId());
        detail.put("note", note);

        auditLog.record("product.review_rejected", actorUserId,
                AuditLog.Target.of("product", productId), detail);
    }

    private Product load(long productId) {
        return jdbc.sql("""
                        select seller_id, status from product
                         where product_id = :id and deleted_at is null
                        """)
                .param("id", productId)
                .query((rs, rowNum) -> new Product(rs.getLong("seller_id"), rs.getString("status")))
                .optional()
                .orElseThrow(() -> new ShopException(ErrorCode.PRODUCT_NOT_FOUND));
    }

    /** 전이의 출발 상태가 맞나. 아니면 이 요청은 지금 할 수 없는 것이다(`D7`) */
    private void requireStatus(Product product, String expected) {
        if (!expected.equals(product.status())) {
            throw new ShopException(ErrorCode.PRODUCT_TRANSITION_NOT_ALLOWED,
                    "지금 상태가 " + product.status() + " 라 " + expected + " 에서만 되는 일을 할 수 없다");
        }
    }

    private void requireVerifiedSeller(long sellerId) {
        boolean verified = Boolean.TRUE.equals(jdbc.sql("""
                        select exists(select 1 from seller
                                       where seller_id = :id and status = 'active'
                                         and deleted_at is null)
                        """)
                .param("id", sellerId)
                .query(Boolean.class)
                .single());

        if (!verified) {
            throw new ShopException(ErrorCode.SELLER_NOT_VERIFIED);
        }
    }

    private void requireUpdatePermission(long actorUserId, long sellerId) {
        if (!evaluator.decide(actorUserId, "product", "update", Target.ofSeller(sellerId)).allowed()) {
            throw new ShopException(ErrorCode.PRODUCT_FORBIDDEN);
        }
    }

    /**
     * 검수 권한. <b>대상은 그 상품의 셀러</b>지만 실제로는 {@code all} 만 걸린다 —
     * 셀러 자신에게는 이 권한이 없어서 자기 상품을 승인할 수 없다.
     */
    private void requireReviewPermission(long actorUserId, long sellerId) {
        if (!evaluator.decide(actorUserId, "product", "review", Target.ofSeller(sellerId)).allowed()) {
            throw new ShopException(ErrorCode.PRODUCT_FORBIDDEN);
        }
    }
}
