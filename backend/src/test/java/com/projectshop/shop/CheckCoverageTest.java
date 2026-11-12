package com.projectshop.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 길이·값 목록 회계 밖의 {@code check} 를 전부 센다(`Q426a`). <b>새 {@code check} 는 「어느 시험이 부수나」를 적어야 초록이다.</b>
 *
 * <p>풀은 도는 스키마의 {@code check} 중 길이 회계({@code LengthConstraintTest}, {@code %length_check})와
 * 값 목록 회계({@link EnumConstraintTest#accountedChecks()}) 밖 전부다. 풀의 이름마다 둘 중 하나다 —
 * 도메인 묶음 맵 넷에 {@link Cover} 로 적혔거나, 아직 메우기 전이라 {@link #BASELINE} 에 있다.
 *
 * <p>「부수는 시험이 있다」는 <b>그 이름 글자가 {@link Cover#test()} 의 소스에 있다</b>는 뜻이다(2026-11-12 사용자 결정).
 * 효과만 보는 시험은 제약을 지워도 다른 자리가 막아 초록일 수 있어서 이름을 단언하는 시험만 친다.
 *
 * <p>{@link #BASELINE} 은 줄기만 한다 — {@link #BASELINE_CEILING} 이 그 수를 박는다. 메우기 행(`Q426b`~`Q426e`)이
 * 이름을 맵으로 옮길 때 상한을 같이 내린다. 트리거 회계({@code TriggerCoverageTest})와 같은 꼴이다.
 */
@DisplayName("회계 밖 check")
class CheckCoverageTest extends PostgresTestBase {

    private static final Path TEST_SOURCES = Path.of("src", "test", "java");

    /** 주문 묶음 — 주문·셀러 주문·반품·장바구니 표(`Q426b`) */
    private static final Map<String, Cover> COVERED_ORDER = Map.ofEntries(
            Map.entry("shop_order_number_format_check", new Cover("OutboxPublisherTest", "주문 번호가 날짜-여섯 글자 꼴이 아니다")),
            Map.entry("seller_order_return_reason_required_check", new Cover("ReturnRequestSchemaTest", "반품 요청 묶음에 사유가 없다")),
            Map.entry("seller_order_tracking_after_ship_check", new Cover("ShipmentTrackingTest", "발송 전에 송장 번호가 있다")),
            Map.entry("seller_order_tracking_no_check", new Cover("ShipmentTrackingTest", "송장 번호가 숫자 10~14 자리가 아니다")),
            Map.entry("seller_order_tracking_pair_check", new Cover("ShipmentTrackingTest", "택배사와 송장 번호 중 하나만 있다")),
            Map.entry("order_item_commission_amount_check", new Cover("SettlementSchemaTest", "수수료 금액이 줄 금액 × 만분율과 다르다")),
            Map.entry("order_item_discount_amount_check", new Cover("CouponApplicationTest", "할인이 음수거나 줄 금액을 넘는다")),
            Map.entry("order_shipping_postal_code_check", new Cover("ShippingFormatTest", "우편번호가 다섯 자리 숫자가 아니다")),
            Map.entry("order_status_history_actor_user_check", new Cover("RefundSweeperTest", "시스템 행위자에 사용자가 있거나 사람 행위자에 없다")),
            Map.entry("order_status_history_status_check", new Cover("OrderStatusListTest", "층(주문·셀러 주문)에 없는 상태")),
            Map.entry("return_request_damaged_inspected_check", new Cover("ReturnDecisionTest", "훼손 거절인데 검수 시각이 없다")),
            Map.entry("shop_order_commission_total_check", new Cover("CheckViolationOrderTest", "수수료 합계가 음수다")),
            Map.entry("shop_order_discount_total_check", new Cover("CheckViolationOrderTest", "할인 합계가 음수다")),
            Map.entry("shop_order_payable_amount_check", new Cover("CheckViolationOrderTest", "결제 금액이 상품 + 배송비 − 할인과 다르다")),
            Map.entry("shop_order_shipping_fee_total_check", new Cover("CheckViolationOrderTest", "배송비 합계가 음수다")),
            Map.entry("shop_order_total_amount_check", new Cover("CheckViolationOrderTest", "상품 합계가 음수다")),
            Map.entry("seller_order_agreed_lead_days_check", new Cover("CheckViolationOrderTest", "동의한 공급 기한이 0~60 밖이다")),
            Map.entry("seller_order_delivered_dates_check", new Cover("CheckViolationOrderTest", "배송 완료 전에 철회 기한·자동 확정 시각이 있다")),
            Map.entry("seller_order_lead_days_consistent_check", new Cover("CheckViolationOrderTest", "공급 기한이 동의한 기한보다 짧다")),
            Map.entry("seller_order_number_format_check", new Cover("CheckViolationOrderTest", "셀러 주문 번호가 S-날짜-여섯 글자 꼴이 아니다")),
            Map.entry("seller_order_shipping_fee_check", new Cover("CheckViolationOrderTest", "배송비가 음수다")),
            Map.entry("seller_order_supply_lead_days_check", new Cover("CheckViolationOrderTest", "공급 기한이 0~60 밖이다")),
            Map.entry("order_item_agreement_without_reason_check", new Cover("CheckViolationOrderTest", "철회 제한 사유 없이 고지 동의 시각이 있다")),
            Map.entry("order_item_commission_bp_check", new Cover("CheckViolationOrderTest", "수수료 만분율이 0~10000 밖이다")),
            Map.entry("order_item_line_amount_check", new Cover("CheckViolationOrderTest", "줄 금액이 단가 × 수량과 다르다")),
            Map.entry("order_item_made_to_order_agreement_check", new Cover("CheckViolationOrderTest", "주문 제작인데 고지 동의가 없다")),
            Map.entry("order_item_quantity_check", new Cover("CheckViolationOrderTest", "수량이 1 미만이다")),
            Map.entry("order_shipping_receiver_phone_check", new Cover("CheckViolationOrderTest", "받는 사람 전화가 숫자·하이픈 9~13 자리가 아니다")),
            Map.entry("order_status_history_moved_check", new Cover("CheckViolationOrderTest", "이전 상태와 다음 상태가 같다")),
            Map.entry("order_status_history_target_check", new Cover("CheckViolationOrderTest", "주문과 셀러 주문을 둘 다 또는 하나도 안 가리킨다")),
            Map.entry("order_contract_document_target_check", new Cover("CheckViolationOrderTest", "정책 문서와 동의 항목을 둘 다 또는 하나도 안 가리킨다")),
            Map.entry("return_request_bearer_decided_check", new Cover("CheckViolationOrderTest", "판정 전에 배송비 부담 주체가 있다")),
            Map.entry("return_request_decided_by_check", new Cover("CheckViolationOrderTest", "판정 시각과 판정자가 짝이 아니다")),
            Map.entry("return_request_decision_check", new Cover("CheckViolationOrderTest", "판정 상태가 아닌데 판정 시각이 있다")),
            Map.entry("return_request_defect_bearer_check", new Cover("CheckViolationOrderTest", "하자 승인인데 배송비를 셀러가 안 문다")),
            Map.entry("return_request_future_timestamps_check", new Cover("CheckViolationOrderTest", "상태보다 앞선 단계의 시각이 있다")),
            Map.entry("return_request_inspected_by_check", new Cover("CheckViolationOrderTest", "검수 시각과 검수자가 짝이 아니다")),
            Map.entry("return_request_rejected_bearer_check", new Cover("CheckViolationOrderTest", "거절인데 배송비를 소비자가 안 문다")),
            Map.entry("return_request_rejection_code_presence_check", new Cover("CheckViolationOrderTest", "거절 상태와 거절 사유 코드가 짝이 아니다")),
            Map.entry("return_request_restock_check", new Cover("CheckViolationOrderTest", "승인 상태와 재입고 여부가 짝이 아니다")),
            Map.entry("return_request_timeline_check", new Cover("CheckViolationOrderTest", "상태에 필요한 단계 시각이 없다")),
            Map.entry("return_request_item_quantity_check", new Cover("CheckViolationOrderTest", "반품 수량이 0 이하다")),
            Map.entry("return_note_any_check", new Cover("CheckViolationOrderTest", "사유 글 셋이 다 비었다")),
            Map.entry("return_pickup_postal_code_check", new Cover("CheckViolationOrderTest", "수거지 우편번호가 다섯 자리 숫자가 아니다")),
            Map.entry("return_pickup_sender_phone_check", new Cover("CheckViolationOrderTest", "보내는 사람 전화가 숫자·하이픈 9~13 자리가 아니다")),
            Map.entry("cart_owner_check", new Cover("CheckViolationOrderTest", "장바구니 주인이 회원·비회원 토큰 중 정확히 하나가 아니다")),
            Map.entry("cart_item_quantity_check", new Cover("CheckViolationOrderTest", "장바구니 수량이 0 이하다")));

    /** 돈 묶음 — 결제·환불·정산·보상·쿠폰 표(`Q426c`) */
    private static final Map<String, Cover> COVERED_MONEY = Map.of();

    /** 상품 묶음 — 상품·SKU·재고·후기·문의·저작권 신고 표(`Q426d`) */
    private static final Map<String, Cover> COVERED_CATALOG = Map.of();

    /** 계정·운영 묶음 — 앞 셋에 없는 표 전부(`Q426e`) */
    private static final Map<String, Cover> COVERED_ACCOUNT = Map.of();

    /** 게이트를 세운 날(2026-11-12) 아직 부수는 시험을 안 댄 이름. <b>줄기만 한다</b> */
    private static final Set<String> BASELINE = Set.of(
            "app_user_alive_fields_check", "batch_run_batch_name_check", "batch_run_count_range_check",
            "batch_run_counts_check", "batch_run_failure_kind_check", "batch_run_failure_reason_check",
            "batch_run_finished_after_started_check", "compensation_amount_check", "consent_item_content_check",
            "consent_item_notice_check", "copyright_report_decision_complete_check",
            "copyright_report_target_reference_check", "coupon_bearer_seller_check", "coupon_discount_value_check",
            "coupon_issue_expiry_after_issued_check", "coupon_issue_used_check", "coupon_issue_window_check",
            "coupon_max_discount_check", "coupon_min_order_amount_check", "coupon_valid_days_check",
            "email_change_request_expiry_after_issued_check", "email_change_request_used_after_issued_check",
            "idempotency_key_hash_check", "inquiry_answer_check", "inquiry_block_check", "inquiry_due_at_check",
            "inquiry_number_format_check", "inquiry_target_check", "inquiry_visibility_check",
            "notification_failure_reason_check", "notification_sent_at_check", "notification_target_check",
            "order_item_unit_price_incl_vat_check", "password_reset_token_expiry_after_issued_check",
            "password_reset_token_used_after_issued_check", "payment_amount_check", "payment_approval_number_check",
            "payment_card_last4_format_check", "payment_decline_reason_check", "policy_document_body_check",
            "policy_document_version_check", "product_commission_bp_check", "product_image_byte_size_check",
            "product_image_original_name_chars_check", "product_supply_lead_days_check", "refund_amount_check",
            "refund_approved_by_user_check", "refund_decision_check", "refund_delay_interest_check",
            "refund_delay_interest_timing_check", "refund_gateway_refund_number_check", "refund_item_amount_check",
            "refund_item_commission_refund_check", "refund_item_discount_refund_check",
            "refund_item_quantity_check", "refund_note_any_check", "refund_number_format_check",
            "refund_requested_by_user_check", "refund_self_approval_check", "refund_shipping_fee_refund_check",
            "refund_system_approval_scope_check", "review_block_check", "review_image_byte_size_check",
            "review_image_original_name_chars_check", "review_rating_check", "review_report_resolution_check",
            "seller_business_reg_no_check", "seller_commission_bp_check", "seller_daily_sales_amounts_check",
            "seller_daily_sales_counts_check", "seller_daily_sales_mall_discount_check",
            "seller_daily_sales_not_empty_check", "seller_default_shipping_fee_check",
            "seller_invitation_accept_check", "seller_invitation_accepted_after_issued_check",
            "seller_invitation_expiry_after_issued_check", "seller_invitation_outcome_check",
            "seller_invitation_revoked_after_issued_check", "seller_mail_order_check",
            "seller_verified_fields_check", "settlement_carried_over_check", "settlement_cycle_payout_date_check",
            "settlement_cycle_period_check", "settlement_item_amount_sign_check",
            "settlement_item_commission_amount_check", "settlement_item_commission_base_amount_check",
            "settlement_item_commission_basis_check", "settlement_item_commission_bp_range_check",
            "settlement_item_compensation_bearer_check", "settlement_item_source_check",
            "settlement_item_vat_platform_check", "settlement_item_vat_rate_range_check",
            "settlement_item_vat_supply_check", "settlement_number_format_check", "settlement_payout_amount_check",
            "settlement_payout_decision_check", "settlement_payout_request_check",
            "settlement_payout_self_approval_check", "sku_price_incl_vat_check",
            "sku_stock_movement_quantity_check", "sku_stock_on_hand_check", "sku_stock_safety_stock_check",
            "webhook_delivery_attempt_count_check", "webhook_delivery_delivered_check",
            "webhook_delivery_last_status_code_check", "webhook_delivery_next_attempt_check",
            "webhook_endpoint_secret_key_version_check", "webhook_endpoint_url_scheme_check");


    /** {@link #BASELINE} 크기의 상한. 이름을 맵으로 옮기면 같이 내린다 */
    private static final int BASELINE_CEILING = 108;

    @Autowired
    private JdbcClient jdbc;

    @Test
    @DisplayName("회계 밖 check 가 전부 맵이나 기준선에 있다")
    void everyCheckIsAccounted() {
        Set<String> accounted = new HashSet<>(covered().keySet());
        accounted.addAll(BASELINE);

        assertThat(pool())
                .as("새 check 는 묶음 맵에 Cover(부수는 시험, 막는 것) 로 적는다 — 기준선은 줄기만 한다")
                .filteredOn(name -> !accounted.contains(name))
                .isEmpty();
    }

    @Test
    @DisplayName("맵과 기준선의 이름이 전부 실물에 있다")
    void accountedNamesExist() {
        List<String> pool = pool();

        assertThat(pool).as("지워졌거나 이름이 바뀐 check 가 회계에 남으면 낡는다").containsAll(covered().keySet());
        assertThat(pool).containsAll(BASELINE);
    }

    @Test
    @DisplayName("맵끼리, 맵과 기준선이 안 겹친다")
    void accountsDoNotOverlap() {
        int total = COVERED_ORDER.size() + COVERED_MONEY.size() + COVERED_CATALOG.size() + COVERED_ACCOUNT.size();
        Set<String> union = new HashSet<>(covered().keySet());
        union.addAll(BASELINE);

        assertThat(union).as("한 이름은 한 자리에만 있다").hasSize(total + BASELINE.size());
    }

    @Test
    @DisplayName("Cover 의 시험 소스가 있고 그 check 이름을 글자로 든다")
    void coverTestNamesTheCheck() {
        assertThat(covered().entrySet())
                .allSatisfy(entry -> assertThat(sourceOf(entry.getValue().test()))
                        .as("%s 의 시험 %s 가 없거나 그 이름을 안 부른다", entry.getKey(), entry.getValue().test())
                        .hasValueSatisfying(source -> assertThat(source).contains(entry.getKey())));
    }

    @Test
    @DisplayName("기준선은 상한을 안 넘고, 풀은 헛돌지 않으며 이름이 유일하다")
    void baselineOnlyShrinks() {
        List<String> pool = pool();

        assertThat(BASELINE).as("기준선은 줄기만 한다 — 늘리지 말고 맵에 적는다").hasSizeLessThanOrEqualTo(BASELINE_CEILING);
        assertThat(pool).as("헛돌지 않는다 — 스키마의 check 를 실제로 걷었다").hasSizeGreaterThan(100);
        assertThat(pool).as("회계 키가 이름 하나라 풀 안에서 유일해야 한다").doesNotHaveDuplicates();
    }

    private List<String> pool() {
        Set<String> enumAccounted = EnumConstraintTest.accountedChecks();
        return jdbc.sql("""
                        select c.conname
                          from pg_constraint c
                          join pg_namespace n on n.oid = c.connamespace
                         where c.contype = 'c' and n.nspname = 'public'
                           and c.conname not like '%length!_check' escape '!'
                        """)
                .query(String.class)
                .list()
                .stream()
                .filter(name -> !enumAccounted.contains(name))
                .toList();
    }

    private static Map<String, Cover> covered() {
        Map<String, Cover> all = new java.util.HashMap<>();
        Stream.of(COVERED_ORDER, COVERED_MONEY, COVERED_CATALOG, COVERED_ACCOUNT).forEach(all::putAll);
        return all;
    }

    private static Optional<String> sourceOf(String testClass) {
        try (Stream<Path> files = Files.walk(TEST_SOURCES)) {
            return files.filter(file -> file.getFileName().toString().equals(testClass + ".java"))
                    .findFirst()
                    .map(file -> {
                        try {
                            return Files.readString(file, StandardCharsets.UTF_8);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 풀의 {@code check} 하나를 부수는 시험.
     *
     * @param test   시험 클래스의 단순 이름. 그 소스가 {@code check} 이름을 글자로 든다
     * @param blocks 그 {@code check} 가 막는 것 한 구절
     */
    record Cover(String test, String blocks) {
    }
}
