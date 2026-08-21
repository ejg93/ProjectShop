package com.projectshop.shop.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;

/**
 * 재고가 상품 정의에서 갈라져 있고, 가용 재고를 앱이 못 어긋내는가(청크 52).
 *
 * <p>여기서 보는 것이 셋이다. <b>재고 칸이 `sku` 로 다시 돌아오지 않는가</b>,
 * <b>가용 재고를 DB 가 계산하는가</b>, 그리고 <b>재고 행 없는 sku 가 못 남는가</b>다.
 *
 * <p>첫째가 이 청크가 막으려는 재발이다 — 재고 컬럼을 `sku` 에 하나 더하는 것은 언제나 쉬운데,
 * 그 순간 상품 수정 시각과 재고 변동이 다시 섞이고 이동 이력(청크 53)이 가리킬 곳이 둘이 된다.
 */
@DisplayName("재고")
class SkuStockTest extends PostgresTestBase {

    /** 재고를 뜻하는 이름이 `sku` 에 다시 생기면 걸린다 */
    private static final List<String> STOCK_WORDS = List.of("stock", "quantity", "on_hand");

    @Autowired
    private JdbcClient jdbc;

    private long skuId;

    @BeforeEach
    void setUp() {
        AuthFixture fixture = new AuthFixture(jdbc);
        long sellerId = fixture.insertSeller("stock-seller", "재고셀러");
        long ownerId = fixture.insertUser("stock-owner@test.local", "재고주인");
        long productId = jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name)
                        values (:sellerId, :ownerId, '재고 시험 상품')
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

    @Nested
    @DisplayName("상품 정의와")
    class SeparatedFromProduct {

        @Test
        @DisplayName("`sku` 에 재고 칸이 없다")
        void skuHasNoStockColumn() {
            List<String> columns = jdbc.sql("""
                            select column_name from information_schema.columns
                             where table_schema = 'public' and table_name = 'sku'
                            """)
                    .query(String.class)
                    .list();

            assertThat(columns)
                    .describedAs("재고가 `sku` 로 돌아오면 상품 수정 시각과 재고 변동이 다시 섞이고, "
                            + "이동 이력(청크 53)이 가리킬 곳이 둘이 된다")
                    .noneSatisfy(column -> assertThat(STOCK_WORDS)
                            .anySatisfy(word -> assertThat(column).contains(word)));
        }

        @Test
        @DisplayName("재고를 고쳐도 상품 수정 시각이 안 밀린다")
        void doesNotTouchSkuUpdatedAt() {
            var before = jdbc.sql("select updated_at from sku where sku_id = :id")
                    .param("id", skuId)
                    .query(java.time.OffsetDateTime.class)
                    .single();

            jdbc.sql("select move_stock(:id, -7, 'adjustment', null)")
                    .param("id", skuId)
                    .query(Boolean.class)
                    .single();

            assertThat(jdbc.sql("select updated_at from sku where sku_id = :id")
                    .param("id", skuId)
                    .query(java.time.OffsetDateTime.class)
                    .single())
                    .describedAs("갈라 둔 이유의 절반이 이것이다")
                    .isEqualTo(before);
        }
    }

    @Nested
    @DisplayName("가용 재고는")
    class AvailableCount {

        @Test
        @DisplayName("안전 재고를 뺀 값이고 DB 가 계산한다")
        void isDerivedFromSafetyStock() {
            jdbc.sql("update sku_stock set safety_stock = 4 where sku_id = :id")
                    .param("id", skuId)
                    .update();

            assertThat(availableCount())
                    .describedAs("앱이 빼면 화면·장바구니·주문이 각자 빼서 셋이 갈린다")
                    .isEqualTo(6);
        }

        @Test
        @DisplayName("앱이 직접 못 적는다")
        void cannotBeWritten() {
            assertThatThrownBy(() -> jdbc.sql("update sku_stock set available_count = 99 where sku_id = :id")
                    .param("id", skuId)
                    .update())
                    .describedAs("생성 컬럼이라 값을 넣는 경로가 아예 없다")
                    .isInstanceOf(Exception.class);
        }

        @Test
        @DisplayName("안전 재고 아래로는 안 팔린다")
        void stopsAtSafetyStock() {
            jdbc.sql("update sku_stock set safety_stock = 8 where sku_id = :id")
                    .param("id", skuId)
                    .update();

            // 주문이 쓰는 입구와 같은 함수다(`53`).
            assertThat(moveStock(-3))
                    .describedAs("가용이 2 인데 3을 깎으면 아무 일도 안 일어난다. 재고 부족과 같은 신호다")
                    .isFalse();
        }

        @Test
        @DisplayName("재고가 음수로 못 내려간다")
        void neverGoesNegative() {
            assertThat(moveStock(-11))
                    .describedAs("가진 것보다 많이 빼는 요청은 통째로 안 먹는다(`D11`)")
                    .isFalse();
            assertThat(availableCount())
                    .describedAs("실패한 이동은 값을 안 건드린다")
                    .isEqualTo(10);
        }

        private boolean moveStock(int quantity) {
            return Boolean.TRUE.equals(jdbc.sql(
                            "select move_stock(:id, :quantity, 'adjustment', null)")
                    .param("id", skuId)
                    .param("quantity", quantity)
                    .query(Boolean.class)
                    .single());
        }

        private int availableCount() {
            return jdbc.sql("select available_count from sku_stock where sku_id = :id")
                    .param("id", skuId)
                    .query(Integer.class)
                    .single();
        }
    }

    @Nested
    @DisplayName("재고 행 없는 sku 는")
    class SkuWithoutStock {

        /**
         * 지연 제약이라 커밋 때 걸리는데, 테스트는 롤백이라 커밋이 없다.
         * {@code set constraints all immediate} 가 그 시점을 앞으로 당긴다.
         */
        @Test
        @DisplayName("커밋에서 거부된다")
        void isRejectedAtCommit() {
            long productId = jdbc.sql("select product_id from sku where sku_id = :id")
                    .param("id", skuId)
                    .query(Long.class)
                    .single();

            jdbc.sql("insert into sku (product_id, price_incl_vat) values (:productId, 10000)")
                    .param("productId", productId)
                    .update();

            assertThatThrownBy(() -> jdbc.sql("set constraints all immediate").update())
                    .describedAs("재고 행이 없는 sku 는 조회에서 조용히 빠져 살 수 없는 상품이 된다")
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }
}
