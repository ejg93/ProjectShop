package com.projectshop.shop.product;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectshop.shop.audit.AuditLog;
import com.projectshop.shop.auth.PermissionEvaluator;
import com.projectshop.shop.auth.PermissionEvaluator.Target;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;

/**
 * 상품을 등록하고 고친다.
 *
 * <p><b>스코프가 실제 자원에 처음 걸리는 자리다.</b> 지금까지 판정은 계정(`own`)과
 * 감사 로그(`all`)에만 쓰였고 셀러 축은 데이터가 없어서 안 밟혔다.
 *
 * <p>상품·옵션·옵션값·SKU 를 <b>한 트랜잭션에 쓴다</b>(`D4` — 상품은 한 덩어리로 산다).
 * 옵션만 있고 SKU 가 없는 상품은 팔 수 없는 반쪽이라 그 상태를 DB 에 만들지 않는다.
 */
@Service
public class ProductService {

    private final JdbcClient jdbc;
    private final PermissionEvaluator evaluator;
    private final AuditLog auditLog;

    ProductService(JdbcClient jdbc, PermissionEvaluator evaluator, AuditLog auditLog) {
        this.jdbc = jdbc;
        this.evaluator = evaluator;
        this.auditLog = auditLog;
    }

    /**
     * @param values 이 축의 선택지. 화면에 보이는 순서대로 온다
     */
    public record OptionCommand(String name, List<String> values) {
    }

    /**
     * @param optionValues 이 조합을 이루는 선택지들. {@code options} 에 적은 축 순서와 같아야 한다
     * @param priceInclVat        부가세를 포함한 판매가(`D8`)
     */
    public record SkuCommand(List<String> optionValues, long priceInclVat, int stockCount) {
    }

    /**
     * @param commissionBp 이 상품만의 수수료율. null 이면 셀러 기본 요율을 쓴다(`D3`)
     * @param withdrawalRestrictionReason 청약철회 제한 사유. 제한 안 하면 null(`D2` R4)
     * @param supplyLeadDays 공급시기 약정 날수(영업일). <b>null 이 기본값을 뜻하지 않는다</b> —
     *                       「약정이 없다」는 사실이고, 약정이 없으면 법정 3영업일이 걸린다
     *                       (`D2` R21, 전자상거래법 제15조제1항 단서)
     */
    public record Command(
            long sellerId,
            String name,
            String description,
            Integer commissionBp,
            boolean withdrawalRestricted,
            String withdrawalRestrictionReason,
            Integer supplyLeadDays,
            List<OptionCommand> options,
            List<SkuCommand> skus) {
    }

    public record Created(long productId, List<Long> skuIds) {
    }

    /**
     * 상품을 만든다. <b>항상 {@code draft} 다</b> — 노출은 검수가 정한다(`7c`).
     *
     * <p>셀러가 아직 {@code pending} 이어도 등록은 된다. 법이 막는 것은 청약이지 준비가 아니고
     * (전자상거래법 제20조②), {@code draft} 는 공개 목록에 안 나와서 청약이 일어날 수 없다.
     * <b>{@code on_sale} 로 올릴 때 셀러 상태를 보는 것은 `7c` 다.</b>
     */
    @Transactional
    public Created create(long actorUserId, Command command) {
        requirePermission(actorUserId, "create", command.sellerId());
        verifySkus(command);

        long productId = insertProduct(actorUserId, command);
        Map<String, Long> valueIds = insertOptions(productId, command.options());
        List<Long> skuIds = insertSkus(productId, command.skus(), valueIds);

        auditLog.record(AuditLog.Kind.OUTCOME, "product.created", actorUserId,
                AuditLog.Target.of("product", productId),
                Map.of("seller_id", command.sellerId(), "sku_count", skuIds.size()));

        return new Created(productId, skuIds);
    }

    /**
     * 상품을 통째로 바꾼다.
     *
     * <p><b>옵션과 SKU 는 전체 교체다.</b> 부분 수정을 열면 "이 옵션값을 지우면 어느 SKU 가
     * 사라지나" 를 클라이언트가 계산해야 하고, 그 계산이 틀리면 조합이 어긋난 상품이 남는다.
     *
     * <p><b>주문이 걸린 SKU 를 아직 안 지킨다.</b> {@code order_item} 테이블이 청크 10 에서 생긴다 —
     * 없는 테이블을 참조하면 실행할 때 터지므로 지금은 조건을 못 건다.
     * 그때 "주문에 쓰인 SKU 는 지우지 않고 {@code suspended} 로 내린다" 를 여기 붙인다.
     * 안 붙이면 {@code order_item.sku_id} 의 {@code restrict} 가 교체를 통째로 막는다(`D4`).
     */
    @Transactional
    public Created replace(long actorUserId, long productId, Command command) {
        long sellerId = sellerIdOf(productId);
        requirePermission(actorUserId, "update", sellerId);
        verifySkus(command);

        jdbc.sql("""
                        update product
                           set name = :name, description = :description,
                               commission_bp = :commissionBp,
                               is_withdrawal_restricted = :restricted,
                               withdrawal_restriction_reason = :reason,
                               supply_lead_days = :leadDays
                         where product_id = :id and deleted_at is null
                        """)
                .param("name", command.name())
                .param("description", command.description())
                .param("commissionBp", command.commissionBp())
                .param("restricted", command.withdrawalRestricted())
                .param("reason", command.withdrawalRestrictionReason())
                .param("leadDays", command.supplyLeadDays())
                .param("id", productId)
                .update();

        boolean ordered = hasOrderedSku(productId);
        if (ordered) {
            requireSameOptions(productId, command.options());
        }

        retireOrderedSkus(productId);
        deleteUnusedStructure(productId, ordered);

        // 주문이 걸린 상품은 옵션을 지우지 못했으므로 있는 것을 그대로 쓴다.
        // 구조가 같다는 것은 바로 위에서 확인했다.
        Map<String, Long> valueIds = ordered
                ? readOptionValueIds(productId)
                : insertOptions(productId, command.options());
        List<Long> skuIds = insertSkus(productId, command.skus(), valueIds);

        auditLog.record(AuditLog.Kind.OUTCOME, "product.updated", actorUserId,
                AuditLog.Target.of("product", productId),
                Map.of("seller_id", sellerId, "sku_count", skuIds.size()));

        return new Created(productId, skuIds);
    }

    /** 내린다. 행은 남는다 — 과거 주문이 이 상품을 가리킨다(`D13`). */
    @Transactional
    public void delete(long actorUserId, long productId) {
        long sellerId = sellerIdOf(productId);
        requirePermission(actorUserId, "delete", sellerId);

        jdbc.sql("update product set deleted_at = now() where product_id = :id and deleted_at is null")
                .param("id", productId)
                .update();

        auditLog.record(AuditLog.Kind.OUTCOME, "product.deleted", actorUserId,
                AuditLog.Target.of("product", productId), Map.of("seller_id", sellerId));
    }

    /**
     * 대상은 <b>셀러</b>다. 상품에는 주인 계정이 없다(`ADR 0004`).
     *
     * <p>{@code seller} 스코프의 뜻이 부여 방식에 따라 갈린다 — 조직 역할로 받았으면 그 셀러만,
     * 전역으로 받았으면 소속한 모든 셀러를 덮는다. 그 판단은 {@link PermissionEvaluator} 가 한다.
     *
     * <p>등록자(`created_by_user_id`)를 대상에 안 담는다. {@code own} 을 쓰는 역할이 아직 없어서다 —
     * {@code seller_staff} 가 생기는 청크 5a 에서 여기에 그 값을 넣는다.
     */
    private void requirePermission(long actorUserId, String action, long sellerId) {
        if (!evaluator.decide(actorUserId, "product", action, Target.ofSeller(sellerId)).allowed()) {
            // 403 이다. 상품은 어차피 공개 목록에 있어서 존재를 숨길 이유가 없다(`D5`).
            throw new ShopException(ErrorCode.PRODUCT_FORBIDDEN);
        }
    }

    private long sellerIdOf(long productId) {
        return jdbc.sql("select seller_id from product where product_id = :id and deleted_at is null")
                .param("id", productId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new ShopException(ErrorCode.PRODUCT_NOT_FOUND));
    }

    /**
     * SKU 가 가리키는 선택지가 실제로 선언된 것인지 본다.
     *
     * <p>DB 는 이걸 못 막는다. {@code sku_option_value} 의 외래키는 "그 선택지가 존재하나" 까지고
     * <b>"이 상품의 것인가" 는 안 본다</b>. 남의 상품 옵션값으로 조합을 만들 수 있다는 뜻이다.
     */
    private void verifySkus(Command command) {
        if (command.skus().isEmpty()) {
            throw new ShopException(ErrorCode.PRODUCT_WITHOUT_SKU);
        }

        List<String> declared = command.options().stream()
                .flatMap(option -> option.values().stream())
                .toList();

        for (SkuCommand sku : command.skus()) {
            if (sku.optionValues().size() != command.options().size()) {
                throw new ShopException(ErrorCode.SKU_OPTION_MISMATCH,
                        "옵션 축 수와 조합의 길이가 다르다");
            }
            for (String value : sku.optionValues()) {
                if (!declared.contains(value)) {
                    throw new ShopException(ErrorCode.SKU_OPTION_MISMATCH,
                            "선언하지 않은 옵션값이다: " + value);
                }
            }
        }
    }

    private long insertProduct(long actorUserId, Command command) {
        return jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name, description,
                                             commission_bp, is_withdrawal_restricted,
                                             withdrawal_restriction_reason, supply_lead_days)
                        values (:sellerId, :actor, :name, :description,
                                :commissionBp, :restricted, :reason, :leadDays)
                        returning product_id
                        """)
                .param("sellerId", command.sellerId())
                .param("actor", actorUserId)
                .param("name", command.name())
                .param("description", command.description())
                .param("commissionBp", command.commissionBp())
                .param("restricted", command.withdrawalRestricted())
                .param("reason", command.withdrawalRestrictionReason())
                .param("leadDays", command.supplyLeadDays())
                .query(Long.class)
                .single();
    }

    /** 주문에 한 번이라도 쓰인 SKU 가 있나. 있으면 이 상품의 구조를 마음대로 못 바꾼다 */
    private boolean hasOrderedSku(long productId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        select exists(
                            select 1 from order_item oi
                              join sku s on s.sku_id = oi.sku_id
                             where s.product_id = :productId)
                        """)
                .param("productId", productId)
                .query(Boolean.class)
                .single());
    }

    /**
     * 옵션 축이 그대로인지 본다.
     *
     * <p>주문이 걸린 상품에서 옵션을 바꾸면 <b>지나간 주문의 옵션 라벨이 가리키던 것이 사라진다</b> —
     * "검정 / M" 이라고 찍힌 영수증이 뜻을 잃는다. 가격·재고 변경과 새 조합 추가는 그대로 된다.
     *
     * <p>순서는 안 본다. 화면에 보이는 차례가 바뀌는 것은 구조 변경이 아니다.
     */
    private void requireSameOptions(long productId, List<OptionCommand> options) {
        Map<String, Set<String>> current = new LinkedHashMap<>();
        jdbc.sql("""
                        select po.name as option_name, pov.value
                          from product_option po
                          join product_option_value pov
                            on pov.product_option_id = po.product_option_id
                         where po.product_id = :productId
                        """)
                .param("productId", productId)
                .query((rs, rowNum) -> Map.entry(rs.getString("option_name"), rs.getString("value")))
                .list()
                .forEach(entry -> current
                        .computeIfAbsent(entry.getKey(), k -> new HashSet<>())
                        .add(entry.getValue()));

        Map<String, Set<String>> requested = new LinkedHashMap<>();
        options.forEach(option -> requested.put(option.name(), new HashSet<>(option.values())));

        if (!current.equals(requested)) {
            throw new ShopException(ErrorCode.PRODUCT_OPTIONS_LOCKED);
        }
    }

    /**
     * 주문에 쓰인 SKU 를 판매중지로 내린다.
     *
     * <p>지울 수가 없다 — {@code order_item.sku_id} 가 {@code restrict} 라 지우려 들면
     * 상품 수정 자체가 통째로 막힌다. 그 제약은 <b>어떤 SKU 였나를 끝까지 따라가려고</b> 건 것이다.
     *
     * <p>{@code deleted_at} 을 같이 채워서 조회에서 빠지게 한다. 고객에게는 사라진 것과 같고
     * 과거 주문에서는 그대로 보인다.
     */
    private void retireOrderedSkus(long productId) {
        jdbc.sql("""
                        update sku
                           set status = :status, deleted_at = now()
                         where product_id = :productId and deleted_at is null
                           and exists (select 1 from order_item oi where oi.sku_id = sku.sku_id)
                        """)
                .param("status", SkuStatus.SUSPENDED.code())
                .param("productId", productId)
                .update();
    }

    /** @return 선택지 이름 → 그 행의 id. 옵션을 다시 만들지 않을 때 쓴다 */
    private Map<String, Long> readOptionValueIds(long productId) {
        Map<String, Long> valueIds = new LinkedHashMap<>();
        jdbc.sql("""
                        select pov.value, pov.product_option_value_id
                          from product_option po
                          join product_option_value pov
                            on pov.product_option_id = po.product_option_id
                         where po.product_id = :productId
                        """)
                .param("productId", productId)
                .query((rs, rowNum) ->
                        Map.entry(rs.getString("value"), rs.getLong("product_option_value_id")))
                .list()
                .forEach(entry -> valueIds.put(entry.getKey(), entry.getValue()));

        return valueIds;
    }

    /** @return 선택지 이름 → 그 행의 id. SKU 를 붙일 때 쓴다 */
    private Map<String, Long> insertOptions(long productId, List<OptionCommand> options) {
        Map<String, Long> valueIds = new LinkedHashMap<>();

        for (int i = 0; i < options.size(); i++) {
            OptionCommand option = options.get(i);
            long optionId = jdbc.sql("""
                            insert into product_option (product_id, name, sort_no)
                            values (:productId, :name, :sortNo)
                            returning product_option_id
                            """)
                    .param("productId", productId)
                    .param("name", option.name())
                    .param("sortNo", i)
                    .query(Long.class)
                    .single();

            List<String> values = option.values();
            for (int j = 0; j < values.size(); j++) {
                long valueId = jdbc.sql("""
                                insert into product_option_value (product_option_id, value, sort_no)
                                values (:optionId, :value, :sortNo)
                                returning product_option_value_id
                                """)
                        .param("optionId", optionId)
                        .param("value", values.get(j))
                        .param("sortNo", j)
                        .query(Long.class)
                        .single();

                valueIds.put(values.get(j), valueId);
            }
        }
        return valueIds;
    }

    private List<Long> insertSkus(long productId, List<SkuCommand> skus, Map<String, Long> valueIds) {
        List<Long> skuIds = new ArrayList<>();

        for (SkuCommand sku : skus) {
            long skuId = jdbc.sql("""
                            with new_sku as (
                                insert into sku (product_id, price_incl_vat)
                                values (:productId, :priceInclVat)
                                returning sku_id
                            )
                            -- 재고를 같은 문장에서 넣는다. 갈라 두면 한쪽을 빠뜨릴 수 있고,
                            -- `V40` 의 지연 제약이 커밋 때 그것을 잡지만 잡히는 자리는 늦다.
                            insert into sku_stock (sku_id, on_hand)
                            select sku_id, :stockCount from new_sku
                            returning sku_id
                            """)
                    .param("productId", productId)
                    .param("priceInclVat", sku.priceInclVat())
                    .param("stockCount", sku.stockCount())
                    .query(Long.class)
                    .single();


            for (String value : sku.optionValues()) {
                jdbc.sql("""
                                insert into sku_option_value (sku_id, product_option_value_id)
                                values (:skuId, :valueId)
                                """)
                        .param("skuId", skuId)
                        .param("valueId", valueIds.get(value))
                        .update();
            }
            skuIds.add(skuId);
        }
        return skuIds;
    }

    /**
     * 주문에 안 쓰인 옵션·SKU 를 지운다. 교체 전에 자리를 비우는 것이다.
     *
     * <p>순서가 있다 — {@code sku_option_value} 가 양쪽을 가리키므로 그것부터 사라져야 한다.
     * {@code sku} 는 cascade 로 딸려 가고, 옵션은 따로 지운다.
     */
    private void deleteUnusedStructure(long productId, boolean keepOptions) {
        // 주문에 쓰인 것은 바로 앞에서 deleted_at 이 채워져 여기 안 걸린다.
        jdbc.sql("""
                        delete from sku
                         where product_id = :productId and deleted_at is null
                        """)
                .param("productId", productId)
                .update();

        // 살아남은 SKU 가 옵션 값을 가리키고 있으면 지울 수 없다({@code sku_option_value} 가 restrict).
        // 구조가 같다는 것을 확인했으므로 그대로 두고 재사용한다.
        if (keepOptions) {
            return;
        }

        jdbc.sql("delete from product_option where product_id = :productId")
                .param("productId", productId)
                .update();
    }
}
