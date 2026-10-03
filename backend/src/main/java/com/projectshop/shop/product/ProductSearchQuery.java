package com.projectshop.shop.product;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.projectshop.shop.product.ProductQuery.PublicItem;
import com.projectshop.shop.product.ProductQuery.PublicPage;
import com.projectshop.shop.support.ListQuery;
import com.projectshop.shop.support.ListQuery.OrderBy;
import com.projectshop.shop.support.ListQuery.Paging;

/**
 * 상품 검색(`60`). 공개 목록과 같은 조각·같은 껍데기에 <b>이름·설명이 검색어를 품는다</b>는 조건 하나를 더한다.
 *
 * <p><b>조회기를 따로 둔다.</b> {@link ProductQuery#findPublic} 에 「검색어가 null 이거나 맞거나」를 섞으면
 * 플래너가 일반 계획을 고르는 날 3-gram 인덱스({@code V123})를 못 탄다 — 검색이 있을 때만 그 조건이 SQL 에 선다.
 * 조건 말고는 공개 목록의 조각({@code PUBLIC_*})을 그대로 쓴다. 사본이면 「파는 중이고 살아 있는 것」이 갈린다.
 *
 * <p><b>{@code ilike} 와 {@code pg_trgm} 이다</b>(2026-09-25 사용자 결정). 한국어 형태소 사전이 없어
 * 전문검색({@code to_tsvector})은 「운동화를」을 「운동화」로 못 찾는다. 3자 미만 검색어는 인덱스를 안 타지만
 * 답은 같다(`stack.md`).
 */
@Service
public class ProductSearchQuery {

    /** 공개 목록의 정렬 표에 관련도 하나를 더한다. 이름이 검색어와 얼마나 닮았나({@code pg_trgm}) */
    private static final Map<String, String> SORTABLE = withRelevance();

    /** 검색어가 있으면 관련도가 먼저다 */
    private static final String DEFAULT_SORT = "relevance,desc";

    /** 이름이나 설명이 검색어를 품는다. 와일드카드는 이스케이프한 값({@code :pattern})이라 글자 그대로 맞는다 */
    private static final String MATCHES = """
               and (p.name ilike '%' || :pattern || '%' escape '\\'
                    or p.description ilike '%' || :pattern || '%' escape '\\')
            """;

    private final JdbcClient jdbc;
    private final ProductQuery products;

    ProductSearchQuery(JdbcClient jdbc, ProductQuery products) {
        this.jdbc = jdbc;
        this.products = products;
    }

    /**
     * 검색어로 거른 공개 목록. 껍데기·쪽·셀러 거르기는 {@link ProductQuery#findPublic} 과 같다.
     *
     * @param q 검색어. 앞뒤 공백을 떼고 쓴다. 빈 값은 입구(`61`)가 막는다
     */
    public PublicPage find(String q, Long sellerId, String sort, Paging paging) {
        String raw = q.strip();
        OrderBy orderBy = ListQuery.orderBy(sort, DEFAULT_SORT, SORTABLE);

        List<PublicItem> items = jdbc.sql(listSql(sellerId, orderBy))
                .param("sellerId", sellerId)
                .param("pattern", escapeLike(raw))
                .param("raw", raw)
                .param("size", paging.size())
                .param("offset", paging.offset())
                .query((rs, rowNum) -> products.publicItem(rs))
                .list();

        Long total = jdbc.sql("select count(*) from product p" + ProductQuery.PUBLIC_WHERE
                        + ProductQuery.bySeller(sellerId) + MATCHES)
                .param("sellerId", sellerId)
                .param("pattern", escapeLike(raw))
                .query(Long.class)
                .single();

        return new PublicPage(items, paging.page(), paging.size(), total);
    }

    /**
     * 목록 SQL 전체. 바인딩은 {@code :sellerId}·{@code :pattern}·{@code :raw}·{@code :size}·{@code :offset} 이다.
     * 시험이 같은 문장의 실행 계획을 본다({@code ProductSearchQueryTest}).
     */
    static String listSql(Long sellerId, OrderBy orderBy) {
        return ProductQuery.PUBLIC_SELECT + ProductQuery.PUBLIC_WHERE + ProductQuery.bySeller(sellerId) + MATCHES
                + " order by " + orderBy.clause() + ", p.product_id desc"
                + " limit :size offset :offset";
    }

    /** 정렬 표. 시험이 관련도 정렬의 계획을 볼 때 같은 표를 쓴다 */
    static OrderBy orderBy(String sort) {
        return ListQuery.orderBy(sort, DEFAULT_SORT, SORTABLE);
    }

    /** {@code like} 의 와일드카드(%·_)와 이스케이프 문자를 글자로 바꾼다. 「100%」가 전부를 맞추지 않게 한다 */
    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static Map<String, String> withRelevance() {
        Map<String, String> sortable = new HashMap<>(ProductQuery.SORTABLE);
        sortable.put("relevance", "similarity(p.name, :raw)");
        return Map.copyOf(sortable);
    }
}
