package com.projectshop.shop.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;

/**
 * <b>주문 파기를 막을 수 있는 표가 전부 처분돼 있나</b>(`43a-28b`).
 *
 * <p>파기가 지우는 표를 {@code on delete restrict} 로 가리키는 표가 남아 있으면
 * {@code delete} 가 거부되고 <b>그 회차 전체가 롤백된다</b> — 그날 사라졌어야 할 다른 사람의
 * 개인정보까지 안 사라진다. 실제로 셋이 그 상태였다: {@code settlement_item}·{@code compensation}·
 * {@code inquiry}. 앞의 둘은 파기가 아예 안 다뤘고, {@code inquiry} 는 지우긴 하는데 주문보다 나중이었다.
 *
 * <p><b>세는 방법이 곧 규칙이다.</b> 「정산과 배상을 처분했다」가 아니라
 * <b>「막을 수 있는 자리를 전부 세고 각각이 어느 처분에 속하는지 말할 수 있다」</b>를 잰다 —
 * {@code Q56} 이 판정 안 지나는 입구를 그렇게 잡았고, 문서가 셋이라 적은 자리에 실물이 아홉이었다.
 *
 * <p><b>SQL 텍스트를 안 읽는다.</b> 서비스의 문자열을 뒤져 {@code delete from …} 을 찾는 방법도
 * 있었지만, 그건 동작이 아니라 글자를 보는 것이라 상수로 빼거나 다른 파일로 옮기면 조용히 비켜난다.
 * 대신 <b>목록 둘을 손으로 들고</b>, 그 목록의 표가 실재하는지도 같이 잰다 —
 * 실재하지 않는 이름이 목록에 남으면 그 줄은 <b>아무도 안 막는 죽은 줄</b>이다.
 */
@DisplayName("주문 파기를 막는 자리")
class PurgeBlockerTest extends PostgresTestBase {

    /**
     * 주문 파기가 지우는 표. 이 표들을 {@code restrict} 로 가리키는 자리가 검사 대상이다.
     *
     * <p>{@code TransactionPurgeService.deleteExpiredOrders} 가 지우는 넷과 그 넷이 cascade 로 끌고 가는 것을 담는다 —
     * {@code refund_item} 은 {@code seller_order} 가 지워질 때 {@code refund} 를 거쳐 따라 사라진다.
     */
    private static final Set<String> PURGE_TARGETS = Set.of(
            "shop_order", "seller_order", "order_item", "order_status_history", "refund_item");

    /**
     * 처분한 표. 셋 중 하나에 들어야 한다.
     *
     * <ul>
     *   <li><b>주문보다 먼저 지운다</b> — {@code purge()} 의 순서가 그것을 정한다</li>
     *   <li><b>주문 고르는 조건이 본다</b> — {@code purgeableOrderIds} 의 {@code not exists} 셋</li>
     *   <li><b>주문과 함께 지운다</b> — {@code deleteExpiredOrders} 안에서 자식부터 지우는 것</li>
     * </ul>
     */
    private static final Set<String> HANDLED = Set.of(
            // 주문과 함께, 자식부터
            "order_item", "order_status_history", "seller_order",
            // 주문보다 먼저 + 주문 고르는 조건이 본다
            "settlement_item", "compensation", "inquiry");

    @Autowired
    private JdbcClient jdbc;

    /** {@code restrict} 로 파기 대상을 잡는 표 하나 */
    private record Blocker(String sourceTable, String targetTable, String constraint) {}

    @Test
    @DisplayName("파기 대상을 restrict 로 잡는 표가 전부 처분 목록에 있다")
    void everyBlockerIsHandled() {
        List<Blocker> blockers = blockers();

        assertThat(blockers)
                .as("파기 대상을 restrict 로 잡는 자리가 하나도 없을 리 없다."
                        + " 0 이면 쿼리가 틀린 것이고 그때부터 이 테스트는 아무것도 안 막는다")
                .isNotEmpty();

        List<Blocker> unhandled = blockers.stream()
                .filter(blocker -> !HANDLED.contains(blocker.sourceTable()))
                .toList();

        assertThat(unhandled)
                .as("이 표가 남아 있으면 주문 삭제가 거부되고 파기 회차 전체가 롤백된다."
                        + " 파기가 주문보다 먼저 지우거나, purgeableOrderIds 가 이 표를 보게 하고,"
                        + " 그 처분을 HANDLED 에 적는다")
                .isEmpty();
    }

    @Test
    @DisplayName("처분 목록의 표가 실재한다")
    void handledTablesExist() {
        List<String> existing = jdbc.sql("""
                        select table_name from information_schema.tables
                         where table_schema = 'public' and table_type = 'BASE TABLE'
                        """)
                .query(String.class)
                .list();

        assertThat(existing)
                .as("실재하지 않는 이름이 목록에 남으면 그 줄은 아무도 안 막는 죽은 줄이고,"
                        + " 그것이 다음 표를 몰래 들여보내는 자리가 된다")
                .containsAll(HANDLED)
                .containsAll(PURGE_TARGETS);
    }

    /**
     * <b>실재하는 것만으로는 부족하다</b>(마무리 19차 독립 리뷰).
     *
     * <p>처음 세울 때 {@code order_shipping}·{@code return_pickup}·{@code return_request}·
     * {@code refund} 를 「6개월에 먼저 사라진다」·「cascade 로 딸려 간다」는 설명과 함께 목록에 넣었는데,
     * 그 넷은 참조가 <b>cascade 라 애초에 막는 자리가 아니다</b> — {@link #blockers()} 에 영영 안 나온다.
     * 표가 실재하니 앞 테스트는 초록이었고, <b>막지도 않는 줄 넷이 목록에 살아 있었다.</b>
     *
     * <p>그 줄들이 위험한 이유는 {@link #everyBlockerIsHandled} 가 목록을 <b>면제 목록</b>으로 쓰기
     * 때문이다. 넷 중 하나가 나중에 참조를 {@code restrict} 로 바꾸면 그 순간 진짜 블로커가 되는데,
     * 이미 목록에 있어서 <b>게이트는 초록인 채 파기가 깨진다.</b>
     */
    @Test
    @DisplayName("처분 목록에 막지도 않는 줄이 없다")
    void handledListHasNoDeadRows() {
        Set<String> actualBlockers = blockers().stream()
                .map(Blocker::sourceTable)
                .collect(Collectors.toSet());

        assertThat(HANDLED)
                .as("목록은 면제 목록이라, 지금 막지도 않는 표가 들어 있으면"
                        + " 그 표가 나중에 restrict 로 바뀌는 날 아무도 안 잡는다")
                .allSatisfy(table -> assertThat(actualBlockers).contains(table));
    }

    private List<Blocker> blockers() {
        return jdbc.sql("""
                        select src.table_name   as source_table,
                               tgt.table_name   as target_table,
                               rc.constraint_name
                          from information_schema.referential_constraints rc
                          join information_schema.table_constraints src
                            on src.constraint_name = rc.constraint_name
                           and src.constraint_schema = rc.constraint_schema
                          join information_schema.table_constraints tgt
                            on tgt.constraint_name = rc.unique_constraint_name
                           and tgt.constraint_schema = rc.unique_constraint_schema
                         where rc.delete_rule = 'RESTRICT'
                           and tgt.table_name in (:targets)
                         order by src.table_name, rc.constraint_name
                        """)
                .param("targets", PURGE_TARGETS)
                .query((rs, rowNum) -> new Blocker(
                        rs.getString("source_table"),
                        rs.getString("target_table"),
                        rs.getString("constraint_name")))
                .list();
    }
}
