package com.projectshop.shop.product;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.product.ProductQuery.PublicItem;
import com.projectshop.shop.support.ListQuery.Paging;

/**
 * 상품 검색(`60`). 3-gram 이 조사가 붙은 낱말을 찾는지, 와일드카드가 글자로 맞는지, <b>인덱스가 살아 있는지</b>를 잰다.
 *
 * <p><b>계획 단언은 표를 채우고 본다.</b> 시험 표가 몇 줄뿐이면 플래너가 순차 스캔이나 아무 인덱스를 고르고 그것이
 * 플래너의 정답이라 3-gram 인덱스가 있는지를 못 가린다. 플래너 설정({@code enable_seqscan})으로 몰면 부분 인덱스가
 * 대신 서서 단언이 흔들렸다(2026-10-02 실측). 5,000 줄을 깔면 검색어가 고르는 몇 줄을 비용만으로 3-gram 인덱스가 집는다 —
 * 인덱스가 사라지거나 조회 식이 인덱스와 어긋나면(예: {@code coalesce} 로 감싸기) 순차 스캔이 서서 빨갛다.
 * <b>{@code fastupdate} 가 켜지는 것도 잡는다</b> — 그러면 깐 줄이 대기 목록에 남아 순차 스캔이 선다({@code V123} 주석).
 */
@DisplayName("상품 검색")
class ProductSearchQueryTest extends PostgresTestBase {

    @Autowired
    private ProductSearchQuery search;

    @Autowired
    private JdbcClient jdbc;

    private long seller;
    private long owner;

    @BeforeEach
    void setUp() {
        AuthFixture fixture = new AuthFixture(jdbc);
        seller = fixture.insertSeller("search-a", "검색셀러");
        fixture.verifySeller(seller);
        owner = fixture.insertUser("search-owner@test.local", "검색사장");
        fixture.joinSeller(seller, owner);
        fixture.grantOrg(owner, "seller_owner", seller);

        insert("운동화를 위한 깔창", null, "on_sale");
        insert("운동화", null, "on_sale");
        insert("할인 100% 면 양말", null, "on_sale");
        insert("할인 1000 양말", null, "on_sale");
        insert("가벼운 우산", "방수 원단으로 만들었다", "on_sale");
        insert("운동화 초안", null, "draft");
    }

    @Test
    @DisplayName("조사가 붙은 이름도 낱말로 찾는다 — 「운동화」가 「운동화를 위한 깔창」을 잡는다")
    void findsWordWithParticle() {
        assertThat(names("운동화")).contains("운동화를 위한 깔창", "운동화");
    }

    @Test
    @DisplayName("파는 중이 아닌 상품은 검색에도 안 나온다 — 공개 목록과 같은 조건이다")
    void draftStaysHidden() {
        assertThat(names("운동화")).doesNotContain("운동화 초안");
    }

    @Test
    @DisplayName("2자 검색어도 답이 맞다 — 3-gram 이 안 나와 인덱스를 안 탈 뿐이다")
    void twoLetterQueryStillAnswers() {
        assertThat(names("깔창")).containsExactly("운동화를 위한 깔창");
    }

    @Test
    @DisplayName("% 는 글자 그대로 맞는다 — 「100%」가 「1000」을 안 잡는다")
    void wildcardIsLiteral() {
        assertThat(names("100%")).containsExactly("할인 100% 면 양말");
    }

    @Test
    @DisplayName("설명에만 있는 낱말도 찾는다")
    void findsInDescription() {
        assertThat(names("방수 원단")).containsExactly("가벼운 우산");
    }

    @Test
    @DisplayName("기본 정렬은 관련도다 — 이름이 검색어와 같은 상품이 먼저다")
    void relevanceFirst() {
        assertThat(names("운동화")).first().isEqualTo("운동화");
    }

    @Test
    @DisplayName("건수는 걸러진 수다")
    void totalCountsMatches() {
        assertThat(search.find("운동화", null, null, new Paging(0, 1)).total()).isEqualTo(2);
    }

    @Test
    @DisplayName("이름·설명의 3-gram 인덱스를 탄다 — 인덱스가 사라지면 빨갛다")
    void usesTrigramIndexes() {
        // 다른 상품 5,000 을 깔고 통계를 모은다. 같은 트랜잭션의 analyze 는 커밋 안 된 자기 행을 센다.
        jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name, description, status)
                        select :seller, :owner, '채움 상품 ' || n, '채움 설명 ' || n, 'on_sale'
                          from generate_series(1, 5000) as n
                        """)
                .param("seller", seller).param("owner", owner)
                .update();
        jdbc.sql("analyze product").update();

        List<String> plan = jdbc.sql("explain " + ProductSearchQuery.listSql(null, ProductSearchQuery.orderBy(null)))
                .param("sellerId", null)
                .param("pattern", "운동화")
                .param("raw", "운동화")
                .param("size", 20)
                .param("offset", 0L)
                .query(String.class)
                .list();

        assertThat(String.join("\n", plan))
                .contains("Bitmap Index Scan on product_name_trgm_idx")
                .contains("Bitmap Index Scan on product_description_trgm_idx");
    }

    private List<String> names(String q) {
        return search.find(q, null, null, new Paging(0, 20)).items().stream().map(PublicItem::name).toList();
    }

    private void insert(String name, String description, String status) {
        jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name, description, status)
                        values (:seller, :owner, :name, :description, :status)
                        """)
                .param("seller", seller).param("owner", owner).param("name", name)
                .param("description", description).param("status", status)
                .update();
    }
}
