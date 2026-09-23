package com.projectshop.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.order.SellerOrderQuery;
import com.projectshop.shop.payment.RefundQuery;
import com.projectshop.shop.product.ProductQuery;
import com.projectshop.shop.settlement.SettlementQuery;
import com.projectshop.shop.stats.SalesStatsQuery;
import com.projectshop.shop.support.ListQuery.Paging;

/**
 * <b>범위 떠보기가 감사를 어떻게 남기나</b>(`Q204`, 마무리 48차 독립 리뷰). {@code PermissionEvaluator.covers} 로 갈래를 고르는
 * 목록 다섯을 두 방향으로 잰다.
 *
 * <ul>
 *   <li><b>제 것을 보는 셀러는 거부를 안 남긴다</b> — 「전체 범위냐」가 거부되는 것은 막힌 시도가 아니다</li>
 *   <li><b>끝내 볼 것이 없는 사람은 거부를 정확히 한 줄 남긴다</b> — {@code covers} 가 안 남기므로 부르는 쪽이
 *       {@code decide} 로 남긴다. 그 줄을 지우면 막힌 시도가 감사에서 빠진다</li>
 * </ul>
 */
@DisplayName("범위 떠보기의 감사")
class ScopeProbeAuditTest extends PostgresTestBase {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private SellerOrderQuery sellerOrders;

    @Autowired
    private SettlementQuery settlements;

    @Autowired
    private RefundQuery refunds;

    @Autowired
    private ProductQuery products;

    @Autowired
    private SalesStatsQuery sales;

    private long seller;
    private long owner;
    private long outsider;

    /** 이름과, 보는 사람을 받아 그 목록을 여는 일 */
    record Probe(String name, Opener opener) {

        @Override
        public String toString() {
            return name;
        }
    }

    interface Opener {
        void open(ScopeProbeAuditTest test, long viewer);
    }

    static List<Probe> probes() {
        return List.of(
                new Probe("셀러 주문 목록", (t, v) -> t.sellerOrders.find(v, null, null, new Paging(0, 20))),
                new Probe("정산서 목록", (t, v) -> t.settlements.find(v, new Paging(0, 20))),
                new Probe("환불 목록", (t, v) -> t.refunds.find(v, null, null, new Paging(0, 20))),
                new Probe("셀러 상품 목록", (t, v) -> t.products.findForSeller(v, null, null, new Paging(0, 20))),
                new Probe("매출 통계", (t, v) -> t.sales.find(v, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 8))));
    }

    @BeforeEach
    void setUp() {
        AuthFixture fixture = new AuthFixture(jdbc);
        seller = fixture.insertSeller("s-probe", "떠보기셀러");
        fixture.verifySeller(seller);
        owner = fixture.insertUser("probe-owner@test.local", "대표");
        fixture.joinSeller(seller, owner);
        fixture.grantOrg(owner, "seller_owner", seller);
        // 역할이 하나도 없는 계정 — 어느 목록도 볼 것이 없다
        outsider = fixture.insertUser("probe-outsider@test.local", "바깥");
    }

    @ParameterizedTest
    @MethodSource("probes")
    @DisplayName("제 것을 보는 셀러는 거부를 안 남긴다")
    void ownViewLeavesNoDenial(Probe probe) {
        long before = denials();

        probe.opener().open(this, owner);

        assertThat(denials()).as("%s — 갈래를 고르는 물음은 막힌 시도가 아니다", probe).isEqualTo(before);
    }

    @ParameterizedTest
    @MethodSource("probes")
    @DisplayName("볼 것이 없는 사람은 거부를 한 줄 남기고 막힌다")
    void blockedViewLeavesOneDenial(Probe probe) {
        long before = denials();

        assertThatThrownBy(() -> probe.opener().open(this, outsider)).isInstanceOf(ShopException.class);

        assertThat(denials()).as("%s — 막힌 시도는 감사에 남는다", probe).isEqualTo(before + 1);
    }

    private long denials() {
        return jdbc.sql("select count(*) from audit_log where event_type = 'permission.denied'")
                .query(Long.class).single();
    }
}
