package com.projectshop.shop.product;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.projectshop.shop.auth.PermissionEvaluator;
import com.projectshop.shop.auth.PermissionEvaluator.Target;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.support.ListQuery;
import com.projectshop.shop.support.ListQuery.Paging;

/**
 * 상품을 찾아 본다. <b>공개 목록과 셀러 목록을 가른다.</b>
 *
 * <p>가르는 것은 경로지 테이블이 아니다. 같은 {@code product} 를 읽지만 조건의 성격이 다르다.
 *
 * <pre>
 * 공개  where status = 'on_sale'          사용자가 조건에 안 들어간다
 * 셀러  where seller_id = any(:sellers)   사용자에 따라 달라진다
 * </pre>
 *
 * <p>한 쿼리로 합치면 {@code or} 하나가 틀렸을 때 <b>비로그인에게 draft 가 샌다.</b>
 * 그 실수는 조용하다 — 목록이 더 많이 나올 뿐 오류가 안 난다.
 * 가르면 <b>누출 위험이 셀러 쪽 한 쿼리에만</b> 있다. 공개 쿼리는 사용자를 안 받아서 샐 것이 없다.
 *
 * <p>응답도 다르다. 수수료율과 재고는 공개로 나가면 안 된다 —
 * 한 record 로 만들면 필드 마스킹을 또 붙여야 하는데 애초에 둘이면 그럴 일이 없다.
 */
@Service
public class ProductQuery {

    /**
     * 정렬 가능한 필드. 요청은 API 이름으로 오고 값은 실제 컬럼식이다(`D14`).
     *
     * <p>{@code price} 를 안 연다. 가격은 {@code sku} 에 있어서 상품당 여럿이고,
     * "최저가 기준인가"·"품절 조합도 세나" 를 먼저 정해야 한다 — 재고 축(52)이 오기 전에 정하면 감이다.
     */
    private static final Map<String, String> SORTABLE = Map.of(
            "created_at", "p.created_at",
            "name", "p.name");

    private static final String DEFAULT_SORT = "created_at,desc";

    private final JdbcClient jdbc;
    private final PermissionEvaluator evaluator;

    ProductQuery(JdbcClient jdbc, PermissionEvaluator evaluator) {
        this.jdbc = jdbc;
        this.evaluator = evaluator;
    }

    /** 누구에게나 같은 값. 수수료율·재고·업무 상태가 없다 */
    public record PublicItem(long productId, long sellerId, String sellerName, String name,
            long minPrice, OffsetDateTime createdAt) {
    }

    /** 셀러가 자기 상품을 볼 때. 팔기 전 상태와 재고가 보인다 */
    public record SellerItem(long productId, long sellerId, String name, String status,
            Integer commissionBp, long minPrice, long totalStock, OffsetDateTime createdAt) {
    }

    public record PublicPage(List<PublicItem> items, int page, int size, long total) {
    }

    public record SellerPage(List<SellerItem> items, int page, int size, long total) {
    }

    /**
     * 공개 목록. <b>로그인 없이 부르고 판정이 없다.</b>
     *
     * <p>판정이 없으면 판정 버그도 없다. 조건은 두 개뿐이고 둘 다 상수다 —
     * 파는 중이고({@code on_sale}) 살아 있는 것({@code deleted_at is null}).
     *
     * @param sellerId 이 셀러 것만. null 이면 전체
     */
    public PublicPage findPublic(Long sellerId, String sort, int page, int size) {
        Paging paging = Paging.of(page, size);
        String orderBy = ListQuery.orderBy(sort, DEFAULT_SORT, SORTABLE);

        List<PublicItem> items = jdbc.sql("""
                        select p.product_id, p.seller_id, s.name as seller_name, p.name,
                               coalesce(min(sk.price), 0) as min_price, p.created_at
                          from product p
                          join seller s on s.seller_id = p.seller_id
                          left join sku sk on sk.product_id = p.product_id
                                          and sk.deleted_at is null
                                          and sk.status = 'on_sale'
                         where p.status = 'on_sale' and p.deleted_at is null
                           and (cast(:sellerId as bigint) is null
                                or p.seller_id = cast(:sellerId as bigint))
                         group by p.product_id, s.name
                        """
                // 텍스트 블록은 줄 끝 공백을 지운다. 블록 안에서 이으면 "order by" 와
                // 컬럼이 붙어 버려서, 공백을 이 문자열에 직접 넣는다.
                + " order by " + orderBy + ", p.product_id desc"
                + " limit :size offset :offset")
                .param("sellerId", sellerId)
                .param("size", paging.size())
                .param("offset", paging.offset())
                .query((rs, rowNum) -> new PublicItem(
                        rs.getLong("product_id"),
                        rs.getLong("seller_id"),
                        rs.getString("seller_name"),
                        rs.getString("name"),
                        rs.getLong("min_price"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();

        Long total = jdbc.sql("""
                        select count(*) from product p
                         where p.status = 'on_sale' and p.deleted_at is null
                           and (cast(:sellerId as bigint) is null
                                or p.seller_id = cast(:sellerId as bigint))
                        """)
                .param("sellerId", sellerId)
                .query(Long.class)
                .single();

        return new PublicPage(items, paging.page(), paging.size(), total);
    }

    /**
     * 셀러가 보는 목록. <b>스코프를 조회 조건에 섞는 유일한 자리다</b>(「알려진 구멍 3」).
     *
     * <p>행 하나 판정과 다르다. 목록은 대상이 없어서 {@code decide} 를 부를 수 없고,
     * <b>어느 행이 대상인지를 조건이 정한다</b> — 조건이 곧 판정이라 틀리면 남의 상품이 섞인다.
     *
     * <p>그래서 <b>판정 결과에서 범위를 읽어 조건으로 옮긴다.</b> 판정 로직을 다시 쓰지 않는다.
     * {@code all} 이 열리면 전체, {@code seller} 면 소속 셀러, 둘 다 아니면 거부다.
     */
    public SellerPage findForSeller(long viewerId, Long sellerId, String sort, int page, int size) {
        Set<Long> visibleSellers = visibleSellersFor(viewerId);
        boolean seesEverything = visibleSellers.isEmpty();

        Paging paging = Paging.of(page, size);
        String orderBy = ListQuery.orderBy(sort, DEFAULT_SORT, SORTABLE);

        List<SellerItem> items = jdbc.sql("""
                        select p.product_id, p.seller_id, p.name, p.status, p.commission_bp,
                               coalesce(min(sk.price), 0) as min_price,
                               coalesce(sum(sk.stock_count), 0) as total_stock,
                               p.created_at
                          from product p
                          left join sku sk on sk.product_id = p.product_id and sk.deleted_at is null
                         where p.deleted_at is null
                           and (:seesEverything or p.seller_id = any(:sellers))
                           and (cast(:sellerId as bigint) is null
                                or p.seller_id = cast(:sellerId as bigint))
                         group by p.product_id
                        """
                + " order by " + orderBy + ", p.product_id desc"
                + " limit :size offset :offset")
                .param("seesEverything", seesEverything)
                .param("sellers", visibleSellers.toArray(Long[]::new))
                .param("sellerId", sellerId)
                .param("size", paging.size())
                .param("offset", paging.offset())
                .query((rs, rowNum) -> new SellerItem(
                        rs.getLong("product_id"),
                        rs.getLong("seller_id"),
                        rs.getString("name"),
                        rs.getString("status"),
                        rs.getObject("commission_bp", Integer.class),
                        rs.getLong("min_price"),
                        rs.getLong("total_stock"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();

        Long total = jdbc.sql("""
                        select count(*) from product p
                         where p.deleted_at is null
                           and (:seesEverything or p.seller_id = any(:sellers))
                           and (cast(:sellerId as bigint) is null
                                or p.seller_id = cast(:sellerId as bigint))
                        """)
                .param("seesEverything", seesEverything)
                .param("sellers", visibleSellers.toArray(Long[]::new))
                .param("sellerId", sellerId)
                .query(Long.class)
                .single();

        return new SellerPage(items, paging.page(), paging.size(), total);
    }

    /**
     * 이 사람이 목록에서 볼 수 있는 셀러들.
     *
     * <p><b>빈 집합은 "아무것도 못 본다" 가 아니라 "전부 본다" 는 뜻이다.</b>
     * 관리자는 셀러 소속이 없어서 목록이 비는데, 그걸 그대로 조건에 넣으면 아무것도 안 나온다.
     * 그래서 호출자가 {@code seesEverything} 으로 갈라 쓴다.
     *
     * <p>여기서 {@code evaluate} 를 다시 구현하지 않는다. 대표 대상 둘로 실제 판정을 돌려서
     * <b>어느 범위가 열리는지를 답에서 읽는다</b> — `8a` 의 권한 목록이 쓰는 방법과 같다.
     */
    private Set<Long> visibleSellersFor(long viewerId) {
        // 남의 셀러 하나. all 스코프에서만 덮인다.
        if (evaluator.decide(viewerId, "product", "update", Target.of(-1L, -1L)).allowed()) {
            return Set.of();
        }

        Set<Long> memberOf = jdbc.sql("select seller_id from seller_member where user_id = :id")
                .param("id", viewerId)
                .query(Long.class)
                .set();

        Set<Long> visible = memberOf.stream()
                .filter(sellerId -> evaluator
                        .decide(viewerId, "product", "update", Target.ofSeller(sellerId)).allowed())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        if (visible.isEmpty()) {
            // 소속이 있어도 상품 권한이 없으면 볼 목록이 없다. 0건이 아니라 거부다 —
            // 0건과 못 봄이 갈려야 개수로 정보가 새지 않는다(`4b-1` 과 같은 이유).
            throw new ShopException(ErrorCode.PRODUCT_FORBIDDEN);
        }
        return visible;
    }
}
