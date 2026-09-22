package com.projectshop.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * DB 에 걸린 트리거를 전부 걷어 회계와 대조한다. 하나라도 표에 없으면 실패한다.
 *
 * <p><b>제약에는 회계가 둘인데 트리거에는 없었다</b>(점검 Q, 2026-09-19).
 * {@code EnumConstraintTest} 와 {@code LengthConstraintTest} 가 {@code pg_constraint} 를 전부 걷는데,
 * {@code pg_trigger} 를 읽는 자리는 저장소에 하나도 없었다. 트리거는 전부 강제 지점 2위라
 * <b>새 트리거가 아무 시험도 없이 들어와도 걸릴 자리가 없다.</b>
 * {@code 점검 F} 가 찾은 「걸려 있는데 한 번도 안 돈다」({@code V63} 의 지연 트리거 둘)가 그 구멍에서 나왔다.
 *
 * <p><b>이름으로는 못 센다.</b> 제약은 시험이 이름을 단언하는 관례가 있는데(94개가 그렇다),
 * 트리거는 <b>쉰 개</b>인데 이름이 시험에 나오는 것은 <b>다섯</b>뿐이다 — 효과로 재기 때문이다.
 * 그래서 회계는 「어느 시험이 이 트리거를 지나나」를 <b>사람이 적는 표</b>가 된다
 * ({@code EnumConstraintTest} 의 {@code EXEMPT} 와 같은 틀).
 *
 * <p><b>못 찾은 것은 못 찾았다고 적는다.</b> 빈칸으로 두면 다음 사람이 「본 적 없는 것」과
 * 「봤는데 없는 것」을 못 가른다 — 그 둘을 가르는 것이 이 회계의 값이다.
 */
@DisplayName("트리거와 회계의 대조")
class TriggerCoverageTest extends PostgresTestBase {

    /** {@code set_updated_at} 을 다는 표들의 공통 설명. 열두 자리가 같은 함수를 쓴다. */
    private static final String TOUCH = "`set_updated_at` 공통. 행을 고치면 `updated_at` 을 지금으로 민다";

    /**
     * {@code 표.트리거} → 무엇을 막나, 어느 시험이 지나나.
     *
     * <p>키를 표 이름까지 붙여서 잡는다 — 트리거 이름은 표마다 따로 노는 이름공간이라
     * {@code pg_trigger} 만으로는 같은 이름이 둘일 수 있다.
     */
    private static final Map<String, String> COVERED = Map.ofEntries(
            // 1. 갱신 시각 — 열둘
            Map.entry("app_user.app_user_set_updated_at", TOUCH + ". `AccountPurgeServiceTest` 가 지난다"),
            Map.entry("product.product_set_updated_at", TOUCH + ". `ProductQueryTest` 가 지난다"),
            Map.entry("shop_order.shop_order_set_updated_at", TOUCH + ". `OrderServiceTest`·`OrderConcurrencyTest` 가 지난다"),
            Map.entry("seller_order.seller_order_set_updated_at", TOUCH + ". `SellerOrderVisibilityTest` 가 지난다"),
            Map.entry("sku.sku_set_updated_at", TOUCH + ". `SkuStockTest` 가 지난다"),
            Map.entry("sku_stock.sku_stock_set_updated_at", TOUCH + ". `SkuStockTest` 가 지난다"),
            Map.entry("return_request.return_request_set_updated_at", TOUCH + ". `ReturnDecisionTest` 가 지난다"),
            Map.entry("cart.cart_set_updated_at", TOUCH + ". **갱신을 보는 시험을 못 찾았다**"),
            Map.entry("cart_item.cart_item_set_updated_at", TOUCH + ". **갱신을 보는 시험을 못 찾았다**"),
            Map.entry("return_note.return_note_set_updated_at", TOUCH + ". **갱신을 보는 시험을 못 찾았다**"),
            Map.entry("role.role_set_updated_at", TOUCH + ". **갱신을 보는 시험을 못 찾았다**"),
            Map.entry("seller.seller_set_updated_at", TOUCH + ". **갱신을 보는 시험을 못 찾았다**"),

            // 2. 아웃박스로 사건을 내보낸다 — 여섯
            Map.entry("order_status_history.order_status_history_emits_event",
                    "상태가 바뀌면 `outbox_event` 에 행을 적는다. `OutboxPublisherTest`·`OutboxEventSchemaTest` 가 지난다"),
            Map.entry("refund.refund_emits_status_event",
                    "환불 상태 변화를 아웃박스로 낸다. `RefundServiceTest`·`OutboxEventSchemaTest` 가 지난다"),
            Map.entry("return_request.return_request_emits_status_event",
                    "반품 상태 변화를 아웃박스로 낸다. `ReturnDecisionTest`·`OutboxEventSchemaTest` 가 지난다"),
            Map.entry("settlement.settlement_emits_payout_event",
                    "정산 지급을 아웃박스로 낸다. `SettlementPayoutTest` 가 지난다"),
            Map.entry("sku_stock_movement.sku_stock_movement_emits_event",
                    "재고 이동을 아웃박스로 낸다. `SkuStockMovementTest`·`SeedOutboxTest` 가 지난다"),
            Map.entry("batch_run.batch_run_emits_event",
                    "배치 회차를 아웃박스로 낸다. `BatchRunsTest`·`OutboxEventSchemaTest` 가 지난다"),

            // 3. 고칠 수 없게 박제한다 — 다섯
            Map.entry("outbox_event.outbox_event_immutable",
                    "발행된 사건의 본문을 못 고치게 막는다. `OutboxEventSchemaTest` 가 지난다"),
            Map.entry("outbox_event.outbox_event_requires_trigger",
                    "아웃박스에 앱이 직접 못 쓰게 막는다 — 쓰는 자리는 트리거뿐이다. `OutboxEventSchemaTest` 가 지난다"),
            Map.entry("order_status_history.order_status_history_no_update",
                    "상태 이력을 고칠 수 없게 막는다(거래기록 보존). `OrderStatusHistorySchemaTest` 가 지난다"),
            Map.entry("policy_document.policy_document_immutable",
                    "효력이 시작된 약관을 못 고치게 막는다. `PolicyQueryTest` 가 지난다"),
            Map.entry("consent_item.consent_item_immutable",
                    "효력이 시작된 동의 항목을 못 고치게 막는다. `ConsentSchemaTest` 가 지난다"),

            // 4. 자격·상태를 판정한다 — 아홉
            Map.entry("app_user.app_user_email_needs_confirmation",
                    "확인 안 된 메일로 못 바꾸게 막는다. `EmailChangeTest` 가 지난다"),
            Map.entry("email_change_request.email_change_request_used_once",
                    "쓴 토큰을 다시 못 쓰게 막는다. `TokenGuardTest`·`EmailChangeTest` 가 지난다"),
            Map.entry("password_reset_token.password_reset_token_used_once",
                    "쓴 토큰을 다시 못 쓰게 막는다. `TokenGuardTest`·`PasswordResetTest` 가 지난다"),
            Map.entry("permission.permission_denies_read_only_roles",
                    "읽기 전용 역할에 쓰기 권한이 붙는 것을 막는다. `PermissionEvaluatorTest` 가 지난다"),
            Map.entry("permission.permission_kind_is_immutable",
                    "권한의 종류를 나중에 못 바꾸게 막는다. `PermissionSchemaTest` 가 지난다"),
            Map.entry("role.role_denies_writes_when_read_only",
                    "역할을 읽기 전용으로 돌릴 때 쓰기 권한이 남아 있으면 막는다. `PermissionEvaluatorTest` 가 지난다"),
            Map.entry("user_role.user_role_check_target",
                    "역할의 스코프와 붙이는 대상이 어긋나면 막는다. `AuthSignupTest`·`HttpFlowTest` 가 지난다"),
            Map.entry("seller_invitation.seller_invitation_role_check",
                    "초대가 전역 역할을 가리키는 것을 막는다. `SellerMemberServiceTest` 가 지난다"),
            Map.entry("review.review_check_target",
                    "후기의 상품·작성자가 주문 줄과 어긋나는 것을 막는다. `ReviewSchemaTest` 가 지난다"),
            Map.entry("review.review_set_updated_at", TOUCH + ". `ReviewSchemaTest` 가 지난다"),
            Map.entry("review_reply.review_reply_check_seller",
                    "남의 셀러 사람이 답글을 다는 것을 막는다. `ReviewModerationTest` 가 지난다"),
            Map.entry("review_reply.review_reply_set_updated_at",
                    TOUCH + ". `ReviewModerationTest` 가 지난다"),
            Map.entry("review_report.review_report_transition_check",
                    "처리한 신고를 다시 여는 것을 막는다. `ReviewModerationTest` 가 지난다"),
            Map.entry("product.product_check_sale_allowed",
                    "팔 수 없는 상태의 상품을 판매중으로 못 돌리게 막는다. `ProductQueryTest` 가 지난다"),
            Map.entry("product_image.product_image_limit",
                    "상품당 사진 수 상한을 넘기지 못하게 막는다. `ProductImageServiceTest` 가 지난다"),

            // 5. 재고 — 둘
            Map.entry("sku_stock.sku_stock_records_initial",
                    "재고 행이 생길 때 첫 이동을 같이 적는다. `SkuStockTest` 가 지난다"),
            Map.entry("sku_stock.sku_stock_requires_move",
                    "이동 기록 없이 재고 수량만 고치는 것을 막는다. `SkuStockMovementTest` 가 지난다"),

            // 6. 지연 트리거 — 열여섯.
            //
            // **갈래가 따로다.** `create constraint trigger` 로 달려서 `create trigger` 만 세면 안 잡힌다 —
            // 이 회계를 세우면서 실제로 그렇게 서른넷을 세었고, DB 를 읽으니 쉰이었다.
            // 트랜잭션 끝에 도는 것이라 **문장 하나만 보면 깨진 상태를 지나간다** — 그것이 이 갈래를 쓰는 이유다.
            //
            // 대부분 이름이 시험에 안 나온다. 다만 **삽입마다 도는 것**이라
            // 그 표에 행을 넣는 시험이면 전부 지난다 — 아래 이름은 그중 하나다.
            Map.entry("shop_order.shop_order_amounts_check",
                    "주문 금액 등식(합계 = 항목 합). 행을 넣는 시험이면 다 지난다 — `OrderServiceTest`"),
            Map.entry("seller_order.seller_order_amounts_check",
                    "셀러 주문 금액 등식. 행을 넣는 시험이면 다 지난다 — `OrderServiceTest`"),
            Map.entry("order_item.order_item_amounts_check",
                    "주문 항목 금액 등식. `HttpTestBase` 가 이름까지 단언한다"),
            Map.entry("settlement.settlement_amounts_check",
                    "정산 금액 등식. 행을 넣는 시험이면 다 지난다 — `SettlementCloseBatchTest`"),
            Map.entry("settlement_item.settlement_item_amounts_check",
                    "정산 항목 금액 등식. 행을 넣는 시험이면 다 지난다 — `SettlementCloseBatchTest`"),
            Map.entry("refund.refund_amounts_check",
                    "환불 금액 등식. 행을 넣는 시험이면 다 지난다 — `RefundServiceTest`"),
            Map.entry("refund_item.refund_item_amounts_check",
                    "환불 항목 금액 등식. 행을 넣는 시험이면 다 지난다 — `RefundServiceTest`"),
            Map.entry("idempotency_key.idempotency_key_response_check",
                    "멱등 키에 응답이 같이 박히는 것을 본다. `OrderIdempotencyTest` 가 지난다"),
            Map.entry("order_status_history.order_status_history_requires_admin_reason",
                    "관리자 강제 전이는 사유가 있어야 한다. `OrderActionTest` 가 지난다"),
            Map.entry("refund.refund_requires_rejection_reason",
                    "환불 거절에는 사유가 있어야 한다. `RefundServiceTest` 가 지난다"),
            Map.entry("return_request.return_requires_rejection_reason",
                    "반품 거절에는 사유가 있어야 한다. `ReturnDecisionTest` 가 이름까지 단언한다"),
            Map.entry("return_request.return_request_bundle_status_check",
                    "반품 묶음과 낱개 상태가 어긋나면 막는다. `ReturnDecisionTest` 가 지난다"),
            Map.entry("seller_order.seller_order_return_status_check",
                    "셀러 주문의 반품 상태가 묶음과 어긋나면 막는다. `ReturnDecisionTest` 가 지난다"),
            Map.entry("shop_order.shop_order_ship_deadline_frozen",
                    "박제된 배송 기한을 나중에 못 바꾸게 막는다. `ShipDeadlineTest` 가 지난다"),
            Map.entry("shop_order.shop_order_contract_documents_complete",
                    "주문에 계약 문서가 다 붙었나 본다. `OrderContractTest` 가 이름까지 단언한다"),
            Map.entry("sku.sku_requires_stock",
                    "재고 행 없는 SKU 를 막는다. `SkuStockTest` 가 지난다"));

    @Autowired
    private JdbcClient jdbc;

    /**
     * DB 에 걸린 트리거가 전부 회계에 있는지 본다.
     *
     * <p>{@code tgisinternal} 은 뺀다 — 외래키가 제 손으로 다는 트리거라 우리가 쓴 것이 아니고,
     * 그것들은 {@code pg_constraint} 쪽 회계 둘이 이미 본다.
     */
    @Test
    @DisplayName("DB 의 모든 트리거가 회계에 적혀 있다")
    void everyTriggerIsAccountedFor() {
        List<String> missing = liveTriggers().stream()
                .filter(name -> !COVERED.containsKey(name))
                .toList();

        assertThat(missing)
                .describedAs("DB 에 걸려 있는데 TriggerCoverageTest 의 COVERED 에 없는 트리거. "
                        + "무엇을 막는지와 어느 시험이 지나는지를 적는다 — 지나는 시험이 없으면 그 사실을 적는다")
                .isEmpty();
    }

    /**
     * 회계에 있는데 DB 에 없는 줄을 찾는다.
     *
     * <p><b>한 방향만 보면 회계가 늙는다.</b> 트리거를 지운 청크가 표를 안 지우면
     * 그 줄은 <b>영영 초록</b>이고, 다음 사람은 없는 방벽을 있다고 읽는다.
     */
    @Test
    @DisplayName("회계에 적힌 트리거가 전부 DB 에 있다")
    void accountedTriggersStillExist() {
        TreeSet<String> live = new TreeSet<>(liveTriggers());
        List<String> stale = COVERED.keySet().stream()
                .filter(name -> !live.contains(name))
                .sorted()
                .toList();

        assertThat(stale)
                .describedAs("COVERED 에 있는데 DB 에 없는 트리거. 지운 트리거면 그 줄도 지운다")
                .isEmpty();
    }

    /** 우리가 쓴 트리거를 {@code 표.트리거} 꼴로 걷는다. */
    private List<String> liveTriggers() {
        return jdbc.sql("""
                select c.relname || '.' || t.tgname
                from pg_trigger t
                join pg_class c on c.oid = t.tgrelid
                join pg_namespace n on n.oid = c.relnamespace
                where not t.tgisinternal and n.nspname = 'public'
                """)
                .query(String.class)
                .list();
    }
}
