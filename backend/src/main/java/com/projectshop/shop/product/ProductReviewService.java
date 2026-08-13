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
 * 상품의 상태를 옮긴다 — 검수(`7c`)와 판매 중지(`7d`).
 *
 * <p>거짓·과장 광고는 시스템이 판단할 수 없다(`D2` R15). 그래서 요건이
 * <b>"사람이 검수하는 경로가 있느냐"</b> 로 바뀌고, 승인 뒤에 문제가 드러나는 경우까지 포함한다 —
 * 중개자가 알고도 방치하면 연대책임을 진다(제20조의2).
 *
 * <p><b>전이 규칙은 여기 없다.</b> {@link ProductTransitions} 의 표가 갖고 있고
 * 이 클래스는 그 표를 거칠 뿐이다. 규칙이 메서드마다 흩어지면 표를 봐도 진실을 모른다(`ADR 0009`).
 *
 * <p>승인·반려·제재를 {@code product:update} 가 아니라 <b>{@code product:review} 로 가른다.</b>
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

    private record Product(long sellerId, ProductStatus status) {
    }

    /** 검수를 신청한다. {@code draft} → {@code pending_review} */
    @Transactional
    public void submit(long actorUserId, long productId) {
        Product product = authorize(actorUserId, productId, ProductStatus.PENDING_REVIEW);

        move(productId, ProductStatus.PENDING_REVIEW, null, null);
        record("product.review_submitted", actorUserId, productId, product, Map.of());
    }

    /**
     * 승인한다. {@code pending_review} → {@code on_sale}.
     *
     * <p><b>셀러가 {@code active} 여야 한다</b>(제20조②) — {@code on_sale} 이 청약이 가능해지는 지점이다.
     * DB 트리거도 같은 것을 막지만 여기서 먼저 보는 이유는 <b>이유를 알려주려는 것</b>이다.
     * 트리거에 걸리면 500 으로 뭉개진다.
     */
    @Transactional
    public void approve(long actorUserId, long productId) {
        Product product = authorize(actorUserId, productId, ProductStatus.ON_SALE);
        requireVerifiedSeller(product.sellerId());

        // 승인하면 지난 사유를 비운다. 남기면 지난 반려·제재가 현재 상태처럼 보인다.
        move(productId, ProductStatus.ON_SALE, null, null);
        record("product.review_approved", actorUserId, productId, product, Map.of());
    }

    /**
     * 반려한다. {@code pending_review} → {@code draft}.
     *
     * <p>사유가 두 군데 간다. 컬럼은 <b>"지금 무엇을 고쳐야 하나"</b> 를 셀러에게 답하고,
     * 감사 로그는 <b>"누가 언제 왜"</b> 를 남긴다. 감사 로그에만 두면 셀러가 못 읽는다 —
     * {@code audit:read} 를 주면 남의 기록까지 보인다.
     */
    @Transactional
    public void reject(long actorUserId, long productId, String note) {
        Product product = authorize(actorUserId, productId, ProductStatus.DRAFT);

        move(productId, ProductStatus.DRAFT, note, null);
        record("product.review_rejected", actorUserId, productId, product, Map.of("note", note));
    }

    /**
     * 셀러가 스스로 내린다. {@code on_sale} → {@code suspended}.
     *
     * <p>품절·단종처럼 자기 사정으로 쉬는 것이라 <b>자기가 다시 올릴 수 있다.</b>
     * 관리자가 막은 것({@code blocked})과 상태를 가른 이유가 이것이다.
     */
    @Transactional
    public void suspend(long actorUserId, long productId) {
        Product product = authorize(actorUserId, productId, ProductStatus.SUSPENDED);

        move(productId, ProductStatus.SUSPENDED, null, null);
        record("product.suspended", actorUserId, productId, product, Map.of());
    }

    /** 셀러가 다시 판다. {@code suspended} → {@code on_sale} */
    @Transactional
    public void resume(long actorUserId, long productId) {
        Product product = authorize(actorUserId, productId, ProductStatus.ON_SALE);
        requireVerifiedSeller(product.sellerId());

        move(productId, ProductStatus.ON_SALE, null, null);
        record("product.resumed", actorUserId, productId, product, Map.of());
    }

    /**
     * 관리자가 막는다. {@code on_sale}·{@code suspended} → {@code blocked}.
     *
     * <p><b>승인이 끝이 아니다.</b> 위법 표시·위조품 신고·리콜은 팔기 시작한 뒤에 드러나고,
     * 중개자가 알고도 방치하면 연대책임을 진다(제20조의2).
     *
     * <p>푸는 것도 관리자만이다 — 표가 그렇게 적혀 있어서 셀러는 권한이 없어 자동으로 막힌다.
     */
    @Transactional
    public void block(long actorUserId, long productId, String reason) {
        Product product = authorize(actorUserId, productId, ProductStatus.BLOCKED);

        move(productId, ProductStatus.BLOCKED, null, reason);
        record("product.blocked", actorUserId, productId, product, Map.of("reason", reason));
    }

    /** 제재를 푼다. 오인이었으면 {@code on_sale}, 실제로 문제가 있었으면 {@code draft} 다 */
    @Transactional
    public void unblock(long actorUserId, long productId, boolean backToSale) {
        ProductStatus to = backToSale ? ProductStatus.ON_SALE : ProductStatus.DRAFT;
        Product product = authorize(actorUserId, productId, to);

        if (backToSale) {
            requireVerifiedSeller(product.sellerId());
        }

        move(productId, to, null, null);
        record("product.unblocked", actorUserId, productId, product, Map.of("to", to.code()));
    }

    /**
     * 지금 상태에서 그 상태로 갈 수 있나, 그리고 이 사람이 그걸 할 수 있나.
     *
     * <p>표가 두 질문에 한 번에 답한다. 전이가 없으면 422, 권한이 없으면 403 이다.
     */
    private Product authorize(long actorUserId, long productId, ProductStatus to) {
        Product product = load(productId);

        String action = ProductTransitions.actionFor(product.status(), to)
                .orElseThrow(() -> new ShopException(ErrorCode.PRODUCT_TRANSITION_NOT_ALLOWED,
                        product.status().code() + " 에서 " + to.code() + " 로 갈 수 없다"));

        if (!evaluator.decide(actorUserId, "product", action, Target.ofSeller(product.sellerId()))
                .allowed()) {
            throw new ShopException(ErrorCode.PRODUCT_FORBIDDEN);
        }
        return product;
    }

    /**
     * 상태를 옮기고 사유 칸을 정리한다.
     *
     * <p>넘긴 것만 남기고 <b>나머지는 비운다.</b> 반려 사유와 제재 사유가 같이 남으면
     * 화면이 무엇을 보여줄지 갈리고, 지난 것이 현재 상태처럼 보인다.
     */
    private void move(long productId, ProductStatus to, String reviewNote, String blockReason) {
        jdbc.sql("""
                        update product
                           set status = :status, review_note = :reviewNote, block_reason = :blockReason
                         where product_id = :id
                        """)
                .param("status", to.code())
                .param("reviewNote", reviewNote)
                .param("blockReason", blockReason)
                .param("id", productId)
                .update();
    }

    private void record(String eventType, long actorUserId, long productId,
            Product product, Map<String, Object> extra) {

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("seller_id", product.sellerId());
        detail.put("from", product.status().code());
        detail.putAll(extra);

        auditLog.record(AuditLog.Kind.OUTCOME, eventType, actorUserId,
                AuditLog.Target.of("product", productId), detail);
    }

    private Product load(long productId) {
        return jdbc.sql("""
                        select seller_id, status from product
                         where product_id = :id and deleted_at is null
                        """)
                .param("id", productId)
                .query((rs, rowNum) -> new Product(rs.getLong("seller_id"),
                        ProductStatus.of(rs.getString("status"))))
                .optional()
                .orElseThrow(() -> new ShopException(ErrorCode.PRODUCT_NOT_FOUND));
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
}
