package com.projectshop.shop;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.order.OrderFixture;

/**
 * 계정·운영 묶음의 회계 밖 {@code check} 를 하나씩 부순다(`Q426e`). <b>사례마다 그 제약 하나만 어기는 쓰기를 내고 이름으로 거부를 단언한다.</b>
 *
 * <p>{@code CheckCoverageTest} 의 {@code COVERED_ACCOUNT} 가 이 파일을 부수는 시험으로 든다. 정상 행은 사용자·셀러 하나와
 * 웹훅 주소·배달 하나다. 배달이 가리킬 사건은 앱이 못 넣어서({@code outbox_event} 는 트리거만 채운다) 셀러 주문 이력을
 * 한 줄 넣어 트리거가 만들게 한다. 나머지 표는 사례가 직접 넣는다.
 *
 * <p>한 쓰기가 여러 제약을 어기면 Postgres 는 이름 알파벳 순으로 앞의 것을 낸다 — 사례는 다른 칸을 맞춰 그 제약 하나만 어긴다.
 */
@DisplayName("계정·운영 묶음 check 위반")
class CheckViolationAccountTest extends PostgresTestBase {

    /** 토큰 해시 칸에 들어갈 64 글자 값. 원문을 안 저장하므로 아무 해시 꼴이면 된다 */
    private static final String HASH = "a".repeat(64);

    @Autowired
    private JdbcClient jdbc;

    private Map<String, Object> ids;

    @BeforeEach
    void setUp() {
        AuthFixture fixture = new AuthFixture(jdbc);
        long userId = fixture.insertUser("q426e-user@test.local", "계정");
        long sellerId = fixture.insertSeller("s-q426e", "운영셀러");

        long orderId = jdbc.sql("""
                        insert into shop_order (order_number, user_id, total_amount,
                                                commission_total, shipping_fee_total, payable_amount)
                        values (:number, :userId, 0, 0, 0, 0)
                        returning order_id
                        """)
                .param("number", OrderFixture.sellerOrderNumber().substring(2))
                .param("userId", userId)
                .query(Long.class)
                .single();
        OrderFixture.attachContractDocuments(jdbc, orderId);

        String bundleNumber = OrderFixture.sellerOrderNumber();
        long bundleId = jdbc.sql("""
                        insert into seller_order (seller_order_number, order_id, seller_id, status)
                        values (:number, :orderId, :sellerId, 'delivered')
                        returning seller_order_id
                        """)
                .param("number", bundleNumber)
                .param("orderId", orderId)
                .param("sellerId", sellerId)
                .query(Long.class)
                .single();

        // 사건은 이력 트리거가 만든다 — 앱의 insert 는 거부된다.
        jdbc.sql("""
                        insert into order_status_history (seller_order_id, from_status, to_status, actor_type)
                        values (:bundleId, 'shipping', 'delivered', 'system')
                        """)
                .param("bundleId", bundleId)
                .update();
        long eventId = jdbc.sql("select max(outbox_event_id) from outbox_event")
                .query(Long.class)
                .single();

        long endpointId = jdbc.sql("""
                        insert into webhook_endpoint (seller_id, url, event_types, secret_ciphertext, secret_key_version)
                        values (:sellerId, 'https://example.test/q426e',
                                cast('{shop.seller_order.status_changed}' as text[]), '\\x00', 1)
                        returning webhook_endpoint_id
                        """)
                .param("sellerId", sellerId)
                .query(Long.class)
                .single();

        long deliveryId = jdbc.sql("""
                        insert into webhook_delivery (webhook_endpoint_id, outbox_event_id)
                        values (:endpointId, :eventId)
                        returning webhook_delivery_id
                        """)
                .param("endpointId", endpointId)
                .param("eventId", eventId)
                .query(Long.class)
                .single();

        ids = Map.of("user", userId, "seller", sellerId, "order", orderId, "bundle", bundleId,
                "endpoint", endpointId, "delivery", deliveryId, "hash", HASH);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("violations")
    @DisplayName("제약 하나만 어기는 쓰기는 그 이름으로 거부된다")
    void rejects(String constraint, String sql) {
        assertThatThrownBy(() -> jdbc.sql(sql).params(ids).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    private static Stream<Arguments> violations() {
        return Stream.of(
                Arguments.of("batch_run_batch_name_check", """
                        insert into batch_run (batch_name, baseline_date, started_at, finished_at, status)
                        values ('Bad-Name', current_date, now(), now(), 'skipped')
                        """),
                Arguments.of("batch_run_count_range_check", """
                        insert into batch_run (batch_name, baseline_date, started_at, finished_at, status,
                                               target_count, processed_count)
                        values ('q426e', current_date, now(), now(), 'skipped', 1, 2)
                        """),
                Arguments.of("batch_run_counts_check", """
                        insert into batch_run (batch_name, baseline_date, started_at, finished_at, status)
                        values ('q426e', current_date, now(), now(), 'succeeded')
                        """),
                Arguments.of("batch_run_failure_kind_check", """
                        insert into batch_run (batch_name, baseline_date, started_at, finished_at, status,
                                               failure_reason)
                        values ('q426e', current_date, now(), now(), 'failed', '실패')
                        """),
                Arguments.of("batch_run_failure_reason_check", """
                        insert into batch_run (batch_name, baseline_date, started_at, finished_at, status,
                                               failure_kind)
                        values ('q426e', current_date, now(), now(), 'failed', 'transient')
                        """),
                Arguments.of("batch_run_finished_after_started_check", """
                        insert into batch_run (batch_name, baseline_date, started_at, finished_at, status)
                        values ('q426e', current_date, now(), now() - interval '1 minute', 'skipped')
                        """),
                Arguments.of("consent_item_content_check",
                        "insert into consent_item (code, title) values ('q426e_item', '위반 항목')"),
                Arguments.of("consent_item_notice_check", """
                        insert into consent_item (code, title, body, purpose)
                        values ('q426e_item', '위반 항목', '본문', '목적')
                        """),
                Arguments.of("email_change_request_expiry_after_issued_check", """
                        insert into email_change_request (user_id, new_email, token_hash, expires_at)
                        values (:user, 'new@test.local', :hash, now() - interval '1 day')
                        """),
                Arguments.of("email_change_request_used_after_issued_check", """
                        insert into email_change_request (user_id, new_email, token_hash, expires_at, used_at)
                        values (:user, 'new@test.local', :hash, now() + interval '1 day', now() - interval '1 day')
                        """),
                Arguments.of("idempotency_key_hash_check",
                        "insert into idempotency_key (user_id, key_value, request_hash) values (:user, 'q426e', 'abc')"),
                Arguments.of("notification_failure_reason_check", """
                        insert into notification (user_id, event_type, kind, notification_template_id, channel, status)
                        select :user, 'order_placed', 'transactional', notification_template_id, 'email', 'failed'
                          from notification_template where kind = 'transactional' limit 1
                        """),
                Arguments.of("notification_sent_at_check", """
                        insert into notification (user_id, event_type, kind, notification_template_id, channel, status)
                        select :user, 'order_placed', 'transactional', notification_template_id, 'email', 'succeeded'
                          from notification_template where kind = 'transactional' limit 1
                        """),
                Arguments.of("notification_target_check", """
                        insert into notification (user_id, event_type, kind, notification_template_id, channel, status,
                                                  order_id, seller_order_id)
                        select :user, 'order_placed', 'transactional', notification_template_id, 'email', 'pending',
                               :order, :bundle
                          from notification_template where kind = 'transactional' limit 1
                        """),
                Arguments.of("password_reset_token_expiry_after_issued_check", """
                        insert into password_reset_token (user_id, token_hash, expires_at)
                        values (:user, :hash, now() - interval '1 day')
                        """),
                Arguments.of("password_reset_token_used_after_issued_check", """
                        insert into password_reset_token (user_id, token_hash, expires_at, used_at)
                        values (:user, :hash, now() + interval '1 day', now() - interval '1 day')
                        """),
                Arguments.of("policy_document_body_check",
                        "insert into policy_document (code, title, body) values ('q426e_doc', '위반 문서', '   ')"),
                Arguments.of("policy_document_version_check",
                        "insert into policy_document (code, title, body, version) values ('q426e_doc', '위반 문서', '본문', 0)"),
                Arguments.of("seller_business_reg_no_check",
                        "update seller set business_reg_no = '123' where seller_id = :seller"),
                Arguments.of("seller_commission_bp_check",
                        "update seller set commission_bp = 10001 where seller_id = :seller"),
                Arguments.of("seller_default_shipping_fee_check",
                        "update seller set default_shipping_fee = -1 where seller_id = :seller"),
                Arguments.of("seller_mail_order_check", """
                        update seller set mail_order_no = '2026-서울-0001', mail_order_exempt_reason = 'simplified_taxpayer'
                         where seller_id = :seller
                        """),
                Arguments.of("seller_invitation_accept_check", """
                        insert into seller_invitation (seller_id, email, role_id, invited_by_user_id, token_hash,
                                                       expires_at, accepted_at)
                        select :seller, 'invitee@test.local', role_id, :user, :hash, now() + interval '1 day', now()
                          from role where is_org_role limit 1
                        """),
                Arguments.of("seller_invitation_accepted_after_issued_check", """
                        insert into seller_invitation (seller_id, email, role_id, invited_by_user_id, token_hash,
                                                       expires_at, accepted_at, accepted_user_id)
                        select :seller, 'invitee@test.local', role_id, :user, :hash, now() + interval '1 day',
                               now() - interval '1 day', :user
                          from role where is_org_role limit 1
                        """),
                Arguments.of("seller_invitation_expiry_after_issued_check", """
                        insert into seller_invitation (seller_id, email, role_id, invited_by_user_id, token_hash,
                                                       expires_at)
                        select :seller, 'invitee@test.local', role_id, :user, :hash, now() - interval '1 day'
                          from role where is_org_role limit 1
                        """),
                Arguments.of("seller_invitation_outcome_check", """
                        insert into seller_invitation (seller_id, email, role_id, invited_by_user_id, token_hash,
                                                       expires_at, accepted_at, accepted_user_id, revoked_at)
                        select :seller, 'invitee@test.local', role_id, :user, :hash, now() + interval '1 day',
                               now(), :user, now()
                          from role where is_org_role limit 1
                        """),
                Arguments.of("seller_invitation_revoked_after_issued_check", """
                        insert into seller_invitation (seller_id, email, role_id, invited_by_user_id, token_hash,
                                                       expires_at, revoked_at)
                        select :seller, 'invitee@test.local', role_id, :user, :hash, now() + interval '1 day',
                               now() - interval '1 day'
                          from role where is_org_role limit 1
                        """),
                Arguments.of("webhook_delivery_attempt_count_check",
                        "update webhook_delivery set attempt_count = -1 where webhook_delivery_id = :delivery"),
                Arguments.of("webhook_delivery_delivered_check",
                        "update webhook_delivery set delivered_at = now() where webhook_delivery_id = :delivery"),
                Arguments.of("webhook_delivery_last_status_code_check",
                        "update webhook_delivery set last_status_code = 99 where webhook_delivery_id = :delivery"),
                Arguments.of("webhook_delivery_next_attempt_check",
                        "update webhook_delivery set next_attempt_at = null where webhook_delivery_id = :delivery"),
                Arguments.of("webhook_endpoint_secret_key_version_check",
                        "update webhook_endpoint set secret_key_version = 0 where webhook_endpoint_id = :endpoint"),
                Arguments.of("webhook_endpoint_url_scheme_check",
                        "update webhook_endpoint set url = 'ftp://example.test/q426e' where webhook_endpoint_id = :endpoint"));
    }
}
