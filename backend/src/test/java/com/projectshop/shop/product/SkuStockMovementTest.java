package com.projectshop.shop.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;

/**
 * 재고가 왜 움직였는지가 빠짐없이 남는가(청크 53).
 *
 * <p><b>이력은 진실이 아니라 설명이다.</b> 팔 수 있나는 여전히 `sku_stock` 이 답하고(`D11`),
 * 이 표는 그 값이 왜 그런지를 말한다. 그래서 <b>둘이 어긋나면 설명이 거짓</b>이 되고,
 * 어긋나는 유일한 길이 「이력 없이 재고를 고치는 경로」다 — 그 길을 트리거가 막는다.
 */
@DisplayName("재고 이동 이력")
class SkuStockMovementTest extends PostgresTestBase {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager txManager;

    private long skuId;

    @BeforeEach
    void setUp() {
        AuthFixture fixture = new AuthFixture(jdbc);
        long sellerId = fixture.insertSeller("move-seller", "이동셀러");
        long ownerId = fixture.insertUser("move-owner@test.local", "이동주인");

        long productId = jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name)
                        values (:sellerId, :ownerId, '이동 시험 상품')
                        returning product_id
                        """)
                .param("sellerId", sellerId)
                .param("ownerId", ownerId)
                .query(Long.class)
                .single();

        skuId = jdbc.sql("""
                        with new_sku as (
                            insert into sku (product_id, price_incl_vat)
                            values (:productId, 10000)
                            returning sku_id
                        )
                        insert into sku_stock (sku_id, on_hand)
                        select sku_id, 10 from new_sku
                        returning sku_id
                        """)
                .param("productId", productId)
                .query(Long.class)
                .single();
    }

    private boolean move(int quantity, String reason) {
        return Boolean.TRUE.equals(jdbc.sql("select move_stock(:id, :quantity, :reason, null)")
                .param("id", skuId)
                .param("quantity", quantity)
                .param("reason", reason)
                .query(Boolean.class)
                .single());
    }

    private List<Map<String, Object>> movements() {
        return jdbc.sql("""
                        select quantity, reason from sku_stock_movement
                         where sku_id = :id order by sku_stock_movement_id
                        """)
                .param("id", skuId)
                .query()
                .listOfRows();
    }

    private int onHand() {
        return jdbc.sql("select on_hand from sku_stock where sku_id = :id")
                .param("id", skuId)
                .query(Integer.class)
                .single();
    }

    @Nested
    @DisplayName("남는 것")
    class Recording {

        @Test
        @DisplayName("재고를 만들면 시작점이 남는다")
        void recordsInitialStock() {
            assertThat(movements())
                    .describedAs("시작점이 없으면 합계가 처음부터 어긋나서 「얼마부터 셌나」를 따로 알아야 한다")
                    .singleElement()
                    .satisfies(row -> {
                        assertThat(row).containsEntry("quantity", 10);
                        assertThat(row).containsEntry("reason", "initial");
                    });
        }

        @Test
        @DisplayName("나간 것과 돌아온 것이 두 줄로 남는다")
        void keepsBothDirections() {
            move(-3, "order_placed");
            move(3, "order_cancelled");

            assertThat(movements())
                    .describedAs("차감을 지우면 그 사이에 재고가 잡혀 있었다는 사실이 사라진다")
                    .hasSize(3)
                    .extracting(row -> row.get("reason"))
                    .containsExactly("initial", "order_placed", "order_cancelled");
        }

        @Test
        @DisplayName("이력의 합이 재고와 같다")
        void sumsToOnHand() {
            move(-3, "order_placed");
            move(3, "order_cancelled");
            move(-4, "adjustment");

            int sum = jdbc.sql("select coalesce(sum(quantity), 0) from sku_stock_movement where sku_id = :id")
                    .param("id", skuId)
                    .query(Integer.class)
                    .single();

            assertThat(sum)
                    .describedAs("둘이 갈리면 설명이 거짓이 된다")
                    .isEqualTo(onHand())
                    .isEqualTo(6);
        }

        @Test
        @DisplayName("실패한 이동은 이력에 안 남는다")
        void skipsFailedMoves() {
            assertThat(move(-99, "order_placed")).isFalse();

            assertThat(movements())
                    .describedAs("안 일어난 일이 남으면 합계가 재고와 갈린다")
                    .hasSize(1);
        }
    }

    @Nested
    @DisplayName("막는 것")
    class Guards {

        @Test
        @DisplayName("함수를 안 지나온 재고 변경은 거부된다")
        void rejectsDirectUpdate() {
            assertThatThrownBy(() -> jdbc.sql("update sku_stock set on_hand = 3 where sku_id = :id")
                    .param("id", skuId)
                    .update())
                    .describedAs("관례로 두면 새 경로가 이력을 빼먹고, 구멍은 숫자가 안 맞는 날에야 드러난다")
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("안전 재고는 함수 없이 고칠 수 있다")
        void allowsSafetyStockUpdate() {
            int changed = jdbc.sql("update sku_stock set safety_stock = 2 where sku_id = :id")
                    .param("id", skuId)
                    .update();

            assertThat(changed)
                    .describedAs("파는 선을 정하는 일이지 물건이 움직인 것이 아니다")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("모르는 사유는 못 남긴다")
        void rejectsUnknownReason() {
            assertThatThrownBy(() -> move(-1, "왜인지 모름"))
                    .describedAs("자유 텍스트로 두면 사유별 집계를 못 한다(`D23` 「열거값」)")
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("0 은 이동이 아니다")
        void rejectsZeroMove() {
            assertThatThrownBy(() -> move(0, "adjustment"))
                    .describedAs("아무것도 안 움직인 줄이 쌓이면 이력을 읽는 값이 떨어진다")
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("예외를 삼켜도 차단이 살아 있다")
        void guardSurvivesSwallowedFailure() {
            swallowOverflowingMove();

            assertThatThrownBy(() -> jdbc.sql("update sku_stock set on_hand = 3 where sku_id = :id")
                    .param("id", skuId)
                    .update())
                    .describedAs("삼킨 예외가 플래그를 켠 채로 남기면 차단이 뚫린다. "
                            + "안 뚫리는 근거는 플랫폼 사실이라 `stack.md` 에 있다")
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("삼킨 뒤에도 정상 이동이 된다")
        void movesStillWorkAfterSwallowedFailure() {
            int before = onHand();

            swallowOverflowingMove();

            assertThat(move(-1, "order_placed"))
                    .describedAs("되돌아간 플래그가 꺼진 채로 굳으면 이번엔 정상 이동이 막힌다")
                    .isTrue();
            assertThat(onHand()).isEqualTo(before - 1);
        }

        /**
         * 플래그를 켠 채로 터지는 이동을 중첩 트랜잭션에서 돌리고 예외를 삼킨다.
         *
         * <p>{@code on_hand + Integer.MAX_VALUE} 가 int 를 넘어서 <b>UPDATE 문 자체가 터진다</b> —
         * {@code move_stock} 이 플래그를 끄기 전이라, 켜 둔 것이 남는지 보는 자리가 여기다.
         *
         * <p>삼키는 방식이 {@code PROPAGATION_NESTED} 다. Spring 이 이것을 세이브포인트로 구현하고,
         * 세이브포인트로 되돌리면 {@code set_config(..., true)} 로 켠 값도 같이 되돌아간다.
         */
        private void swallowOverflowingMove() {
            TransactionTemplate nested = new TransactionTemplate(txManager);
            nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);

            assertThatThrownBy(() -> nested.executeWithoutResult(
                    status -> move(Integer.MAX_VALUE, "adjustment")))
                    .describedAs("int 를 넘기는 이동은 UPDATE 에서 터져야 한다. 안 터지면 이 시험이 성립 안 한다")
                    .isInstanceOf(DataAccessException.class);
        }
    }
}
