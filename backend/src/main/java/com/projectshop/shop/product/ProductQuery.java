package com.projectshop.shop.product;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.projectshop.shop.auth.Allowed;
import com.projectshop.shop.auth.PermissionEvaluator;
import com.projectshop.shop.auth.PermissionEvaluator.Target;
import com.projectshop.shop.support.WithdrawalRestrictionReason;
import com.projectshop.shop.support.EnumValue;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.support.ObjectStorage;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.support.ListQuery;
import com.projectshop.shop.support.ListQuery.OrderBy;
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
    private final ObjectStorage storage;

    ProductQuery(JdbcClient jdbc, PermissionEvaluator evaluator, ObjectStorage storage) {
        this.jdbc = jdbc;
        this.evaluator = evaluator;
        this.storage = storage;
    }

    /**
     * 누구에게나 같은 값. 수수료율·재고·업무 상태가 없다.
     *
     * @param shippingFee 이 셀러의 배송비. <b>가격만 내리면 화면이 총액을 못 그린다</b> —
     *                    전자상거래법 제21조의2 1호가 첫 화면에 필수 총금액을 요구하고
     *                    배송비가 그 필수 수반 비용이다(`D2` R24). 셀러 조회로 따로 받으면
     *                    목록에서 상품마다 한 번씩이라 N+1 이다
     * @param thumbnailUrl 첫 사진의 썸네일. <b>만료 5분 서명 URL 이고 사진이 없으면 null 이다</b>
     *                     (`media-rules.md` 「여는 법」, `28`)
     */
    public record PublicItem(long productId, long sellerId, String sellerName, String name,
            long minPriceInclVat, long shippingFee, String thumbnailUrl, OffsetDateTime createdAt) {
    }

    /**
     * 셀러가 자기 상품을 볼 때. 팔기 전 상태와 재고가 보인다.
     *
     * @param status 업무 상태. <b>대문자 스네이크로 나간다</b>(`D5` 「형식」)
     */
    public record SellerItem(long productId, long sellerId, String name, String status,
            Integer commissionBp, long minPriceInclVat, long totalStock, OffsetDateTime createdAt,
            List<String> allowedActions) {
    }

    public record PublicPage(List<PublicItem> items, int page, int size, long total) {
    }

    /**
     * 공개 상세. 목록보다 많이 주지만 <b>재고 수량과 수수료율은 여전히 없다.</b>
     *
     * @param withdrawalRestrictionReason 청약철회를 제한하는 사유. 제한이 없으면 null.
     *                                    <b>법이 고지를 요구하는 값이라 공개로 나간다</b>(`D2` R4)
     */
    /**
     * @param supplyLeadDays 공급시기 약정 날수(영업일). <b>{@code null} 이면 약정이 없고
     *        법정 3영업일이 걸린다</b>(전자상거래법 제15조제1항, `D2` R21).
     *        <b>화면이 그려야 그 약정이 선다</b> — 제15조제1항 단서의 「따로 약정한 것」은
     *        고지가 성립 요건이라, 값만 두고 안 그리면 법정 기한이 그대로다(`14c`)
     */
    public record PublicDetail(long productId, long sellerId, String sellerName, String name,
            String description, boolean withdrawalRestricted, String withdrawalRestrictionReason,
            long shippingFee, Integer supplyLeadDays, List<String> imageUrls, List<OptionGroup> options,
            List<PublicSku> skus, OffsetDateTime createdAt, List<Long> imageIds) {
    }

    /** 옵션 하나와 고를 수 있는 값들. 「색상」에 「빨강·파랑」 같은 것 */
    public record OptionGroup(long productOptionId, String name, List<OptionValue> values) {
    }

    public record OptionValue(long productOptionValueId, String value) {
    }

    /**
     * 살 수 있는 조합 하나.
     *
     * @param optionValueIds 이 조합이 어느 값들로 이루어졌나. <b>화면이 고른 값으로 SKU 를 찾는 열쇠다</b>
     * @param inStock 재고가 있나. <b>몇 개인지는 안 준다</b> — 살 수 있는지만 알면 화면이 그려진다
     */
    public record PublicSku(long skuId, long priceInclVat, boolean inStock, List<Long> optionValueIds) {
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
    public PublicPage findPublic(Long sellerId, String sort, Paging paging) {
        OrderBy orderBy = ListQuery.orderBy(sort, DEFAULT_SORT, SORTABLE);

        List<PublicItem> items = jdbc.sql("""
                        select p.product_id, p.seller_id, s.name as seller_name, p.name,
                               coalesce(min(sk.price_incl_vat), 0) as min_price_incl_vat,
                               s.default_shipping_fee, p.created_at,
                               (select i.thumbnail_key from product_image i
                                 where i.product_id = p.product_id
                                 order by i.sort_no, i.product_image_id limit 1) as thumbnail_key
                          from product p
                          join seller s on s.seller_id = p.seller_id
                          left join sku sk on sk.product_id = p.product_id
                                          and sk.deleted_at is null
                                          and sk.status = 'on_sale'
                         where p.status = 'on_sale' and p.deleted_at is null
                           and (cast(:sellerId as bigint) is null
                                or p.seller_id = cast(:sellerId as bigint))
                         group by p.product_id, s.name, s.default_shipping_fee
                        """
                // 텍스트 블록은 줄 끝 공백을 지운다. 블록 안에서 이으면 "order by" 와
                // 컬럼이 붙어 버려서, 공백을 이 문자열에 직접 넣는다.
                + " order by " + orderBy.clause() + ", p.product_id desc"
                + " limit :size offset :offset")
                .param("sellerId", sellerId)
                .param("size", paging.size())
                .param("offset", paging.offset())
                .query((rs, rowNum) -> new PublicItem(
                        rs.getLong("product_id"),
                        rs.getLong("seller_id"),
                        rs.getString("seller_name"),
                        rs.getString("name"),
                        rs.getLong("min_price_incl_vat"),
                        rs.getLong("default_shipping_fee"),
                        presigned(rs.getString("thumbnail_key")),
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
    public SellerPage findForSeller(long viewerId, Long sellerId, String sort, Paging paging) {
        return findForSeller(viewerId, sellerId, null, sort, paging);
    }

    /**
     * @param status 그 상태만(`Q182` — 관리자의 검수 대기 목록이 이것을 쓴다). {@code null} 이면 전부
     */
    public SellerPage findForSeller(long viewerId, Long sellerId, String status, String sort, Paging paging) {
        String statusCode = status == null ? null : ProductStatus.ofRequest(status).code();
        Allowed<Long> visible = visibleSellersFor(viewerId);
        Set<Long> memberOf = memberOf(viewerId);

        // 조건을 만드는 자리는 여기 하나다. switch 가 두 경우를 다 다루게 강제한다 —
        // "전부" 를 빈 목록으로 넘기면 아무것도 안 나오는데, 그 실수를 컴파일러가 막는다.
        boolean seesEverything = !visible.restricted();
        Long[] sellers = visible.values().toArray(Long[]::new);

        OrderBy orderBy = ListQuery.orderBy(sort, DEFAULT_SORT, SORTABLE);

        List<SellerItem> items = jdbc.sql("""
                        select p.product_id, p.seller_id, p.created_by_user_id, p.name, p.status, p.commission_bp,
                               coalesce(min(sk.price_incl_vat), 0) as min_price_incl_vat,
                               coalesce(sum(st.available_count), 0) as total_stock,
                               p.created_at
                          from product p
                          left join sku sk on sk.product_id = p.product_id and sk.deleted_at is null
                          left join sku_stock st on st.sku_id = sk.sku_id
                         where p.deleted_at is null
                           and (:seesEverything or p.seller_id = any(:sellers))
                           and (cast(:sellerId as bigint) is null
                                or p.seller_id = cast(:sellerId as bigint))
                           and (cast(:status as text) is null or p.status = cast(:status as text))
                         group by p.product_id
                        """
                + " order by " + orderBy.clause() + ", p.product_id desc"
                + " limit :size offset :offset")
                .param("seesEverything", seesEverything)
                .param("sellers", sellers)
                .param("sellerId", sellerId)
                .param("status", statusCode)
                .param("size", paging.size())
                .param("offset", paging.offset())
                .query((rs, rowNum) -> new SellerItem(
                        rs.getLong("product_id"),
                        rs.getLong("seller_id"),
                        rs.getString("name"),
                        EnumValue.of(rs.getString("status"), ProductStatus::of),
                        rs.getObject("commission_bp", Integer.class),
                        rs.getLong("min_price_incl_vat"),
                        rs.getLong("total_stock"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        allowedActions(viewerId, memberOf, rs.getLong("seller_id"), rs.getLong("created_by_user_id"),
                                ProductStatus.of(rs.getString("status")))))
                .list();

        Long total = jdbc.sql("""
                        select count(*) from product p
                         where p.deleted_at is null
                           and (:seesEverything or p.seller_id = any(:sellers))
                           and (cast(:sellerId as bigint) is null
                                or p.seller_id = cast(:sellerId as bigint))
                           and (cast(:status as text) is null or p.status = cast(:status as text))
                        """)
                .param("seesEverything", seesEverything)
                .param("sellers", sellers)
                .param("sellerId", sellerId)
                .param("status", statusCode)
                .query(Long.class)
                .single();

        return new SellerPage(items, paging.page(), paging.size(), total);
    }

    /**
     * 공개 상세. <b>목록과 같은 조건이다</b> — 파는 중이고 살아 있는 것.
     *
     * <p>조건이 목록과 갈리면 목록에 없는 상품이 상세로는 열리거나 그 반대가 된다.
     * 주소를 직접 치는 사람이 그 틈으로 들어온다.
     *
     * <p><b>재고 수량을 안 내린다.</b> 셀러 목록에만 있는 값이고(`8`), 품절인지만 알면
     * 살 수 있는지가 정해진다. 수량은 남에게 우리 사정을 알려 주는 값이다.
     *
     * <p>쿼리를 셋으로 나눴다. 한 번에 조인하면 옵션 수 × SKU 수만큼 행이 불어나고,
     * 그걸 자바에서 다시 접어야 한다 — <b>접는 코드가 틀려도 조용하다.</b>
     */
    public PublicDetail findPublicDetail(long productId) {
        PublicDetail head = jdbc.sql("""
                        select p.product_id, p.seller_id, s.name as seller_name, p.name,
                               p.description, p.is_withdrawal_restricted,
                               p.withdrawal_restriction_reason, s.default_shipping_fee,
                               p.supply_lead_days, p.created_at
                          from product p
                          join seller s on s.seller_id = p.seller_id
                         where p.product_id = :id
                           and p.status = 'on_sale' and p.deleted_at is null
                        """)
                .param("id", productId)
                .query((rs, rowNum) -> new PublicDetail(
                        rs.getLong("product_id"),
                        rs.getLong("seller_id"),
                        rs.getString("seller_name"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getBoolean("is_withdrawal_restricted"),
                        EnumValue.of(rs.getString("withdrawal_restriction_reason"), WithdrawalRestrictionReason::of),
                        rs.getLong("default_shipping_fee"),
                        // 바깥 조인이 아니라도 null 이 오는 컬럼은 getObject 로 받는다 —
                        // getInt 는 null 을 0 으로 돌려줘서 「약정 없음」이 「당일 발송」이 된다(`D23`).
                        rs.getObject("supply_lead_days", Integer.class),
                        List.of(),
                        List.of(),
                        List.of(),
                        rs.getObject("created_at", OffsetDateTime.class),
                        List.of()))
                .optional()
                // 파는 중이 아닌 것과 아예 없는 것을 안 가른다. 가르면 draft 상품의 존재가 샌다.
                .orElseThrow(() -> new ShopException(ErrorCode.PRODUCT_NOT_FOUND));

        return new PublicDetail(head.productId(), head.sellerId(), head.sellerName(), head.name(),
                head.description(), head.withdrawalRestricted(), head.withdrawalRestrictionReason(),
                head.shippingFee(), head.supplyLeadDays(), findImageUrls(productId), findOptions(productId),
                findPublicSkus(productId), head.createdAt(), findImageIds(productId));
    }

    /**
     * 옵션과 그 값들. 정렬 순서는 셀러가 정한 것을 그대로 따른다.
     *
     * <p>값을 옵션마다 다시 조회하지 않는다 — 옵션이 셋이면 쿼리가 넷이 되고,
     * 그 모양은 상품 수만큼 늘어난다.
     */
    /**
     * 상세에 실을 사진들. <b>원본 쪽이고 목록은 썸네일이다</b>({@code media-rules.md} 「썸네일」).
     *
     * <p>사진이 없으면 빈 목록이다. {@code null} 을 안 쓴다 —
     * 「없다」를 빈 목록이 이미 말하고, {@code null} 은 <b>「모른다」로도 읽힌다</b>({@code D23}).
     */
    /**
     * 사진 번호들. <b>{@link #findImageUrls} 와 같은 순서다</b>(`Q183`) — 저작권 침해 신고가 사진 하나를 가리켜야 해서
     * 공개 상세에 번호가 필요하다(`D2` `R42`). URL 목록을 객체로 바꾸면 이미 쓰는 화면이 깨져서 칸을 더했다.
     */
    private List<Long> findImageIds(long productId) {
        return jdbc.sql("""
                        select product_image_id from product_image
                         where product_id = :id
                         order by sort_no, product_image_id
                        """)
                .param("id", productId)
                .query(Long.class)
                .list();
    }

    private List<String> findImageUrls(long productId) {
        return jdbc.sql("""
                        select object_key from product_image
                         where product_id = :id
                         order by sort_no, product_image_id
                        """)
                .param("id", productId)
                .query(String.class)
                .list()
                .stream()
                .map(storage::presignedUrl)
                .toList();
    }

    /**
     * 열쇠를 <b>만료 5분 서명 URL</b> 로 바꾼다. 열쇠가 없으면 {@code null} 이다 —
     * 사진이 없는 상품이 있고, 그 자리를 화면이 자리표시로 채운다.
     *
     * <p><b>저장소 주소를 그대로 안 내린다.</b> 버킷이 비공개라 그 주소는 밖에서 안 열리고,
     * 여는 유일한 방법이 이 서명이다({@code media-rules.md} 「여는 법」).
     */
    private String presigned(String objectKey) {
        return objectKey == null ? null : storage.presignedUrl(objectKey);
    }

    private List<OptionGroup> findOptions(long productId) {
        record Row(long optionId, String optionName, long valueId, String value) {
        }

        List<Row> rows = jdbc.sql("""
                        select o.product_option_id, o.name as option_name,
                               v.product_option_value_id, v.value
                          from product_option o
                          join product_option_value v
                            on v.product_option_id = o.product_option_id
                         where o.product_id = :id
                         order by o.sort_no, o.product_option_id, v.sort_no, v.product_option_value_id
                        """)
                .param("id", productId)
                .query((rs, rowNum) -> new Row(
                        rs.getLong("product_option_id"),
                        rs.getString("option_name"),
                        rs.getLong("product_option_value_id"),
                        rs.getString("value")))
                .list();

        // LinkedHashMap 이다. 위에서 정렬해 온 순서가 그대로 남아야 화면의 선택 순서가 셀러 뜻대로다.
        Map<Long, OptionGroup> grouped = new LinkedHashMap<>();
        for (Row row : rows) {
            grouped.computeIfAbsent(row.optionId(),
                            id -> new OptionGroup(id, row.optionName(), new ArrayList<>()))
                    .values()
                    .add(new OptionValue(row.valueId(), row.value()));
        }
        return List.copyOf(grouped.values());
    }

    /**
     * 살 수 있는 조합들.
     *
     * <p>{@code on_sale} 인 SKU 만 나간다. 내린 조합을 같이 주면 화면이 고를 수 있는 것으로 그리고,
     * 담기에서야 막힌다.
     *
     * <p><b>{@code sku_option_value} 를 left join 한다.</b> 옵션이 없는 상품도 SKU 는 하나 있는데,
     * inner join 이면 그 SKU 가 행을 하나도 안 만들어서 <b>살 수 있는 조합이 통째로 사라진다.</b>
     * 담기·주문은 {@code sku_id} 로 하므로 오류 없이 화면만 못 그린다.
     *
     * @param productId 조합을 찾을 상품
     */
    private List<PublicSku> findPublicSkus(long productId) {
        record Row(long skuId, long priceInclVat, boolean inStock, Long optionValueId) {
        }

        List<Row> rows = jdbc.sql("""
                        select sk.sku_id, sk.price_incl_vat,
                               st.available_count > 0 as in_stock,
                               sov.product_option_value_id
                          from sku sk
                          join sku_stock st on st.sku_id = sk.sku_id
                          left join sku_option_value sov on sov.sku_id = sk.sku_id
                         where sk.product_id = :id
                           and sk.status = 'on_sale' and sk.deleted_at is null
                         order by sk.sku_id, sov.product_option_value_id
                        """)
                .param("id", productId)
                .query((rs, rowNum) -> new Row(
                        rs.getLong("sku_id"),
                        rs.getLong("price_incl_vat"),
                        rs.getBoolean("in_stock"),
                        // getLong 은 null 을 0 으로 준다. 옵션 없는 SKU 가 0 번 선택지를 가리키게 된다.
                        rs.getObject("product_option_value_id", Long.class)))
                .list();

        Map<Long, PublicSku> grouped = new LinkedHashMap<>();
        for (Row row : rows) {
            PublicSku sku = grouped.computeIfAbsent(row.skuId(),
                    id -> new PublicSku(id, row.priceInclVat(), row.inStock(), new ArrayList<>()));
            if (row.optionValueId() != null) {
                sku.optionValueIds().add(row.optionValueId());
            }
        }
        return List.copyOf(grouped.values());
    }

    /**
     * 이 사람이 목록에서 볼 수 있는 셀러들.
     *
     * <p>{@link Allowed} 로 돌려준다. <b>"전부" 를 빈 집합으로 표현하면 호출자가 그걸
     * 그대로 조건에 넣어서 아무것도 안 나온다</b> — 관리자는 셀러 소속이 없어서 실제로 빈다.
     *
     * <p>여기서 {@code evaluate} 를 다시 구현하지 않는다. 대표 대상으로 실제 판정을 돌려서
     * <b>어느 범위가 열리는지를 답에서 읽는다</b> — `8a` 의 권한 목록이 쓰는 방법과 같다.
     */
    /**
     * 이 사람이 이 상품에 지금 할 수 있는 동작(`Q182`). <b>전이표와 판정을 그대로 돌린다</b> — 화면이 상태를 보고
     * 버튼을 고르면 표가 두 벌이 되고, 부여표가 바뀌는 날 없는 권한의 버튼이 조용히 남는다(`Q79` 와 같은 판단).
     * 대상은 검수 서비스와 같게 셀러와 등록자를 싣는다.
     */
    private List<String> allowedActions(long viewerId, Set<Long> memberOf, long sellerId, long createdByUserId,
            ProductStatus status) {
        Target target = Target.of(createdByUserId, sellerId);
        return ProductTransitions.all().stream()
                .filter(transition -> transition.from() == status)
                // 쉬기·다시 팔기는 셀러 소속만 받는다(`Q198`). 관리자에게 내면 셀러가 곧바로 되돌리는 버튼이 된다.
                .filter(transition -> !ProductTransitions.sellerOwn(transition) || memberOf.contains(sellerId))
                .filter(transition -> evaluator.decide(viewerId, "product", transition.permission(), target).allowed())
                .map(ProductTransitions::actionName)
                .distinct()
                .toList();
    }

    /** 이 사람이 속한 셀러들. 버튼을 고를 때 한 번 읽는다 — 행마다 읽으면 목록 크기만큼 질의가 나간다 */
    private Set<Long> memberOf(long viewerId) {
        return jdbc.sql("select seller_id from seller_member where user_id = :id")
                .param("id", viewerId)
                .query(Long.class)
                .set();
    }

    private Allowed<Long> visibleSellersFor(long viewerId) {
        // 남의 셀러 하나. all 스코프에서만 덮인다.
        if (evaluator.decide(viewerId, "product", "update", Target.of(-1L, -1L)).allowed()) {
            return Allowed.everything();
        }

        Set<Long> memberOf = memberOf(viewerId);

        // **대상에 자기를 싣는다**(`Q161` 이 놓친 자리, 마무리 44차 독립 리뷰).
        // `seller_staff` 는 `product:update` 가 `own` 뿐이라 셀러만 실으면 아무것도 안 덮고,
        // 그러면 **담당자가 자기 셀러의 관리 목록을 아예 못 연다** — 고칠 수 있는 상품이
        // 그 목록 안에 있는데 목록으로 가는 길이 막힌다.
        Set<Long> visible = memberOf.stream()
                .filter(sellerId -> evaluator
                        .decide(viewerId, "product", "update", Target.of(viewerId, sellerId))
                        .allowed())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        if (visible.isEmpty()) {
            // 소속이 있어도 상품 권한이 없으면 볼 목록이 없다. 0건이 아니라 거부다 —
            // 0건과 못 봄이 갈려야 개수로 정보가 새지 않는다(`4b-1` 과 같은 이유).
            throw new ShopException(ErrorCode.PRODUCT_FORBIDDEN);
        }
        return Allowed.only(visible);
    }
}
