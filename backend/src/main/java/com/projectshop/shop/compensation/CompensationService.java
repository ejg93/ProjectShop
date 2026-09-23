package com.projectshop.shop.compensation;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectshop.shop.audit.AuditLog;
import com.projectshop.shop.auth.PermissionEvaluator;
import com.projectshop.shop.auth.PermissionEvaluator.Target;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.support.EnumValue;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 미인도·지연인도 손해배상을 판정하고 본다(`43a-4c`, `D2` R38·R39).
 *
 * <p><b>금액을 계산하지 않는다.</b> 소비자분쟁해결기준이 액수를 안 정해서(`V69` 가 원문을 적었다) 판정 행 자체가
 * 사람이 내린 결론이다 — 얼마를, 왜, 누가, 언제. <b>사유가 필수인 이유도 그것이다</b>: 분쟁이 오면 그 글이
 * 우리가 자의로 정하지 않았다는 유일한 근거다({@code compensation_note_reason_length_check}).
 *
 * <p><b>정산은 여기서 안 건드린다.</b> 셀러가 무는 판정은 그 달 정산이 스스로 줍는다({@code SettlementService}) —
 * 여기서 정산 줄을 같이 쓰면 마감된 정산서에 줄이 붙는다.
 */
@Service
public class CompensationService {

    /**
     * 판정 한 줄.
     *
     * @param reason 사유. <b>3년이라 판정(5년)보다 먼저 사라진다</b>(`V69`) — 그 뒤에는 비어 있다
     * @param inquiryNumber 어느 문의에서 왔나. 우리가 먼저 알고 문 것이면 비어 있다
     */
    @Schema(name = "Compensation")
    public record Entry(String kind, String bearer, long amount, String reason, String inquiryNumber,
            OffsetDateTime decidedAt) {
    }

    /**
     * 한 묶음의 판정들.
     *
     * @param allowedActions 지금 할 수 있는 것. 판정 권한이 있으면 {@code COMPENSATE} 다 — 화면이 버튼을 이것으로 고른다
     */
    @Schema(name = "CompensationList")
    public record Listing(List<Entry> items, List<String> allowedActions) {
    }

    public record Command(CompensationKind kind, CompensationBearer bearer, long amount, String reason,
            String inquiryNumber) {
    }

    /** 판정에 필요한 것까지 같이 읽은 묶음 */
    private record Bundle(long sellerOrderId, long buyerUserId, long sellerId) {

        Target target() {
            return Target.of(buyerUserId, sellerId);
        }
    }

    private final JdbcClient jdbc;
    private final PermissionEvaluator evaluator;
    private final AuditLog auditLog;

    CompensationService(JdbcClient jdbc, PermissionEvaluator evaluator, AuditLog auditLog) {
        this.jdbc = jdbc;
        this.evaluator = evaluator;
        this.auditLog = auditLog;
    }

    /**
     * 판정을 남긴다. <b>판정 행과 사유가 한 트랜잭션이다</b> — 사유 없는 판정이 커밋되면 그 금액의 근거가 없다.
     *
     * <p>못 부르는 사람에게는 없는 묶음과 같은 404 다(`D5` — 셀러 묶음은 주문과 같은 급이다).
     */
    @Transactional
    public void decide(long userId, String sellerOrderNumber, Command command) {
        Bundle bundle = find(sellerOrderNumber);
        if (!evaluator.decide(userId, "compensation", "decide", bundle.target()).allowed()) {
            throw notFound(sellerOrderNumber);
        }

        Long inquiryId = inquiryOf(command.inquiryNumber(), bundle);

        long compensationId = jdbc.sql("""
                        insert into compensation (seller_order_id, kind, bearer, amount,
                                                  decided_by_user_id, inquiry_id)
                        values (:sellerOrderId, :kind, :bearer, :amount, :userId, :inquiryId)
                        returning compensation_id
                        """)
                .param("sellerOrderId", bundle.sellerOrderId())
                .param("kind", command.kind().code())
                .param("bearer", command.bearer().code())
                .param("amount", command.amount())
                .param("userId", userId)
                .param("inquiryId", inquiryId)
                .query(Long.class)
                .single();

        jdbc.sql("insert into compensation_note (compensation_id, reason) values (:id, :reason)")
                .param("id", compensationId)
                .param("reason", command.reason())
                .update();

        auditLog.record(AuditLog.Kind.OUTCOME, "compensation.decided", userId,
                AuditLog.Target.of("compensation", compensationId),
                Map.of("kind", command.kind().name(), "bearer", command.bearer().name(),
                        "amount", command.amount()));
    }

    /** 한 묶음의 판정을 판정 순으로 본다. 조회 권한이 없으면 404 다 */
    public Listing list(long userId, String sellerOrderNumber) {
        Bundle bundle = find(sellerOrderNumber);
        if (!evaluator.decide(userId, "compensation", "read", bundle.target()).allowed()) {
            throw notFound(sellerOrderNumber);
        }

        List<Entry> items = jdbc.sql("""
                        select c.kind, c.bearer, c.amount, n.reason, i.inquiry_number, c.decided_at
                          from compensation c
                          left join compensation_note n on n.compensation_id = c.compensation_id
                          left join inquiry i on i.inquiry_id = c.inquiry_id
                         where c.seller_order_id = :sellerOrderId
                         order by c.decided_at, c.compensation_id
                        """)
                .param("sellerOrderId", bundle.sellerOrderId())
                .query((rs, rowNum) -> new Entry(
                        EnumValue.of(rs.getString("kind"), CompensationKind::of),
                        EnumValue.of(rs.getString("bearer"), CompensationBearer::of),
                        rs.getLong("amount"),
                        rs.getString("reason"),
                        rs.getString("inquiry_number"),
                        rs.getObject("decided_at", OffsetDateTime.class)))
                .list();

        // 판정을 부르는 판정은 평가기로 묻는다 — `decide` 는 거부를 감사 로그에 남겨서 목록을 열 때마다 쌓인다.
        List<String> allowed = evaluator.allowedActions(userId, "compensation", java.util.Set.of("decide"),
                bundle.target()).contains("decide") ? List.of("COMPENSATE") : List.of();

        return new Listing(items, allowed);
    }

    /**
     * 문의 번호를 판정에 잇는다. <b>그 묶음의 문의여야 한다</b> — 남의 문의를 이으면 분쟁 기록이 다른 거래를 가리킨다.
     * 묶음을 안 가리킨 문의는 산 사람의 것이면 받는다(주문과 무관하게 연 문의에서 배상이 나올 수 있다).
     */
    private Long inquiryOf(String inquiryNumber, Bundle bundle) {
        if (inquiryNumber == null || inquiryNumber.isBlank()) {
            return null;
        }
        return jdbc.sql("""
                        select inquiry_id from inquiry
                         where inquiry_number = :number
                           and (seller_order_id = :sellerOrderId
                                or (seller_order_id is null and user_id = :buyerUserId))
                        """)
                .param("number", inquiryNumber)
                .param("sellerOrderId", bundle.sellerOrderId())
                .param("buyerUserId", bundle.buyerUserId())
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new ShopException(ErrorCode.COMPENSATION_INQUIRY_MISMATCH,
                        "그 문의는 이 묶음의 것이 아니다: " + inquiryNumber));
    }

    /**
     * <b>결제된 묶음만 찾는다</b>({@code seller_order_visible}) — 같은 경로의 다른 쓰기(발송·취소·강제 전이)가 지나는
     * 조건과 같다(마무리 46차 독립 리뷰). 안 보면 결제 대기·만료 주문에 셀러 부담 배상이 서고 정산이 그것을 셀러 몫에서 뺀다.
     */
    private Bundle find(String sellerOrderNumber) {
        return jdbc.sql("""
                        select so.seller_order_id, o.user_id as buyer_user_id, so.seller_id
                          from seller_order_visible so
                          join shop_order o on o.order_id = so.order_id
                         where so.seller_order_number = :number
                        """)
                .param("number", sellerOrderNumber)
                .query((rs, rowNum) -> new Bundle(
                        rs.getLong("seller_order_id"),
                        rs.getLong("buyer_user_id"),
                        rs.getLong("seller_id")))
                .optional()
                .orElseThrow(() -> notFound(sellerOrderNumber));
    }

    private static ShopException notFound(String sellerOrderNumber) {
        return new ShopException(ErrorCode.SELLER_ORDER_NOT_FOUND, "그런 셀러 주문이 없다: " + sellerOrderNumber);
    }
}
