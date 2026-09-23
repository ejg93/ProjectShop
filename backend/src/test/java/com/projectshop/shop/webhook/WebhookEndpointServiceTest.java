package com.projectshop.shop.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;

/**
 * 웹훅 엔드포인트 등록(`29`).
 *
 * <p>여기서 지키는 것 넷이다 — <b>시크릿의 평문이 표에 없고</b>, <b>안쪽 주소를 못 걸고</b>, <b>셀러당 다섯을 못 넘고</b>,
 * <b>대표만 자기 셀러에 건다</b>. 넷 다 틀려도 등록 화면은 정상으로 보인다.
 */
@DisplayName("웹훅 엔드포인트 등록")
class WebhookEndpointServiceTest extends PostgresTestBase {

    private static final Set<WebhookEventType> ORDERS = Set.of(WebhookEventType.SELLER_ORDER_STATUS_CHANGED);

    @Autowired
    private WebhookEndpointService endpoints;

    @Autowired
    private WebhookSecretCipher cipher;

    @Autowired
    private JdbcClient jdbc;

    private AuthFixture fixture;
    private long sellerId;
    private long owner;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);
        sellerId = fixture.insertSeller("s-hook", "웹훅셀러");
        fixture.verifySeller(sellerId);
        owner = fixture.insertUser("hook-owner@test.local", "대표");
        fixture.joinSeller(sellerId, owner);
        fixture.grantOrg(owner, "seller_owner", sellerId);
    }

    @Test
    @DisplayName("시크릿은 등록 응답에만 있고 표에는 풀 수 있는 암호문뿐이다")
    void secretIsOnlyInTheResponse() {
        WebhookEndpointService.Created created = endpoints.register(owner, sellerId, "http://127.0.0.1:9/hook",
                Set.of(WebhookEventType.SELLER_ORDER_STATUS_CHANGED, WebhookEventType.REFUND_STATUS_CHANGED));

        assertThat(created.secret()).startsWith("whsec_");
        byte[] stored = jdbc.sql("select secret_ciphertext from webhook_endpoint where webhook_endpoint_id = :id")
                .param("id", created.webhookEndpointId()).query(byte[].class).single();
        assertThat(cipher.decrypt(stored, cipher.keyVersion(), cipher.bindingOf(sellerId, "http://127.0.0.1:9/hook")))
                .isEqualTo(Base64.getDecoder().decode(created.secret().substring("whsec_".length())));

        assertThat(endpoints.list(owner, sellerId).items()).singleElement().satisfies(endpoint -> {
            assertThat(endpoint.url()).isEqualTo("http://127.0.0.1:9/hook");
            assertThat(endpoint.eventTypes()).containsExactlyInAnyOrder(
                    "SELLER_ORDER_STATUS_CHANGED", "REFUND_STATUS_CHANGED");
        });
    }

    /** 평문 칸이 없는 것이 강제 지점이다. 누가 「편하게」 평문 칸을 더하면 여기서 빨개진다 */
    @Test
    @DisplayName("시크릿 칸은 암호문과 키 판뿐이다")
    void noPlaintextColumn() {
        List<String> columns = jdbc.sql("""
                        select column_name || ':' || data_type from information_schema.columns
                         where table_name = 'webhook_endpoint' and column_name like '%secret%'
                         order by 1
                        """)
                .query(String.class).list();

        assertThat(columns).containsExactly("secret_ciphertext:bytea", "secret_key_version:integer");
    }

    @Test
    @DisplayName("안쪽 주소는 못 건다")
    void rejectsInwardUrl() {
        assertThatThrownBy(() -> endpoints.register(owner, sellerId, "https://169.254.169.254/latest", ORDERS))
                .isInstanceOfSatisfying(ShopException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.WEBHOOK_URL_NOT_ALLOWED));
    }

    @Test
    @DisplayName("셀러당 다섯을 못 넘고 같은 주소는 두 번 못 건다")
    void limitAndDuplicate() {
        for (int port = 1; port <= WebhookEndpointService.MAX_ENDPOINTS_PER_SELLER; port++) {
            endpoints.register(owner, sellerId, "http://127.0.0.1:" + port + "/hook", ORDERS);
        }

        assertThatThrownBy(() -> endpoints.register(owner, sellerId, "http://127.0.0.1:99/hook", ORDERS))
                .isInstanceOfSatisfying(ShopException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.WEBHOOK_ENDPOINT_LIMIT));

        jdbc.sql("delete from webhook_endpoint where url = 'http://127.0.0.1:5/hook'").update();
        assertThatThrownBy(() -> endpoints.register(owner, sellerId, "http://127.0.0.1:1/hook", ORDERS))
                .isInstanceOfSatisfying(ShopException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.WEBHOOK_ENDPOINT_DUPLICATE));
    }

    @Test
    @DisplayName("직원과 남의 셀러 대표는 못 건다 — 남의 엔드포인트는 없는 것과 같다")
    void onlyTheOwnerOfThatSeller() {
        long staff = fixture.insertUser("hook-staff@test.local", "직원");
        fixture.joinSeller(sellerId, staff);
        fixture.grantOrg(staff, "seller_staff", sellerId);
        long other = fixture.insertSeller("s-hook-other", "남의셀러");
        long otherOwner = fixture.insertUser("hook-other@test.local", "남의대표");
        fixture.joinSeller(other, otherOwner);
        fixture.grantOrg(otherOwner, "seller_owner", other);

        for (long user : new long[] {staff, otherOwner}) {
            assertThatThrownBy(() -> endpoints.register(user, sellerId, "http://127.0.0.1:7/hook", ORDERS))
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.WEBHOOK_FORBIDDEN));
        }
        long mine = endpoints.register(owner, sellerId, "http://127.0.0.1:8/hook", ORDERS).webhookEndpointId();
        assertThatThrownBy(() -> endpoints.find(otherOwner, mine))
                .isInstanceOfSatisfying(ShopException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.WEBHOOK_ENDPOINT_NOT_FOUND));
    }

    /**
     * 재고 원장·배치·주문 전체 사건은 셀러 경계를 넘는다 — 입구를 안 거쳐도 표가 막는다.
     * 경우마다 따로 돈다 — 한 트랜잭션에서 첫 실패 뒤의 문장은 25P02 로 떨어져 제약을 못 잰다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"{shop.sku.stock_moved}", "{shop.order.status_changed}", "{}"})
    @DisplayName("구독 못 하는 사건과 빈 목록은 표가 막는다")
    void tableRejectsForeignEvents(String types) {
        assertThatThrownBy(() -> jdbc.sql("""
                        insert into webhook_endpoint (seller_id, url, event_types, secret_ciphertext, secret_key_version)
                        values (:sellerId, 'https://example.test/x', cast(:types as text[]), '\\x00', 1)
                        """)
                .param("sellerId", sellerId).param("types", types).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * 범위는 `V110` 의 검사 블록이 적용 때 한 번 본다. 뒤의 마이그레이션이 넓혀도 그 블록은 다시 안 돈다 —
     * 그래서 지금 DB 의 부여를 매번 잰다(마무리 45차 독립 리뷰).
     */
    @Test
    @DisplayName("웹훅 권한은 대표에게 셀러 범위로, 관리자에게 전부로만 열려 있다")
    void grantsStayWithinTheDecidedScopes() {
        List<String> grants = jdbc.sql("""
                        select r.code || ':' || rp.scope || ':' || rp.effect
                          from role_permission rp
                          join role r on r.role_id = rp.role_id
                          join permission p on p.permission_id = rp.permission_id
                         where p.resource = 'webhook'
                         order by 1
                        """)
                .query(String.class).list();

        assertThat(grants).containsExactly("admin:all:allow", "auditor:all:deny", "seller_owner:seller:allow");
    }
}
