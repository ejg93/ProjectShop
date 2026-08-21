package com.projectshop.shop.order;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.support.ExposedNumber;

/**
 * 장바구니에서 고른 것을 주문으로 굳힌다.
 *
 * <p>이 클래스가 하는 일의 핵심은 <b>박제</b>다. 상품명·단가·수수료율을 주문 항목에 복사해 넣어서,
 * 셀러가 나중에 값을 바꿔도 지나간 주문 금액이 안 흔들리게 한다.
 *
 * <p>금액 등식과 강제 지점은 `money-invariants.md` 에 있고 스키마가 그걸 막는다.
 * 여기서는 등식을 <b>맞춰서 넣을</b> 뿐이고, 틀리면 커밋할 때 지연 트리거가 잡는다.
 *
 * <p><b>멱등은 여기 없다.</b> 컨트롤러가 {@link IdempotencyService} 로 감싸고 이 서비스를 람다로 넘긴다 —
 * 그래야 선점·주문 생성·응답 저장이 한 트랜잭션에 들어간다(`D11`).
 */
@Service
public class OrderService {

    /**
     * 셀러 묶음의 노출 번호 접두어. <b>주문번호는 접두어가 없다</b>(`D9`).
     *
     * <p>형식이 같으면 전화로 번호를 받는 자리에서 어느 쪽인지 못 가른다.
     */
    private static final String SELLER_ORDER_PREFIX = "S-";

    /**
     * 공급시기 약정이 없을 때의 날수. 전자상거래법 제15조제1항(`D2` R21).
     *
     * <p>상품이 값을 안 가지면 이것이 걸린다. <b>{@code product.supply_lead_days} 의 기본값이
     * 아니다</b> — 그 컬럼의 {@code null} 은 「약정이 없다」는 사실이고, 약정이 없으면
     * 법정 기한이 그대로 걸리는 것이다(`V26`).
     */
    private static final int LEGAL_SUPPLY_LEAD_DAYS = 3;

    /** 청약철회 제한 사유(`V13`). 성립 조건이 사유마다 달라서 이름으로 가른다(`Q5`) */
    private static final String DIGITAL_CONTENT = "digital_content";
    private static final String MADE_TO_ORDER = "made_to_order";

    private final JdbcClient jdbc;

    OrderService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param cartItemIds 살 것. 장바구니에 담긴 것 중 고른 것만 온다 — 나머지는 담긴 채로 남는다
     * @param withdrawalRestrictionAgreed 주문제작 상품의 청약철회 제한에 동의했나.
     *        <b>시행령 제21조가 거래마다 별도 고지와 동의를 요구한다</b> — 안 받으면
     *        그 주문에는 제한이 안 걸린다(`Q5`). 화면이 그 칸을 내는 것은 `Q6` 다
     */
    public record Command(List<Long> cartItemIds, Shipping shipping,
            boolean withdrawalRestrictionAgreed) {

        /**
         * 동의를 안 밝히면 <b>안 받은 것</b>이다.
         *
         * <p>기본을 참으로 두면 부르는 쪽이 아무 말도 안 했을 때 제한이 걸린다 —
         * 시행령 제21조가 요구하는 것은 동의고, <b>침묵은 동의가 아니다</b>.
         * `D23` 의 「기본이 열림이면 안 된다」와 같은 방향이다.
         */
        public Command(List<Long> cartItemIds, Shipping shipping) {
            this(cartItemIds, shipping, false);
        }
    }

    public record Shipping(String receiverName, String receiverPhone, String postalCode,
            String address1, String address2, String deliveryMemo) {}

    public record Created(long orderId, String orderNumber, long payableAmount) {}

    /**
     * 담긴 것 하나를 주문 시점 값으로 굳힌 것. 장바구니에서 읽어 온 뒤로는 DB 를 다시 안 본다.
     *
     * <p>{@code commissionBp} 는 상품에 정해진 것이 있으면 그것이고 없으면 셀러 기본값이다(`D3`).
     */
    private record Line(long skuId, long sellerId, String productName, String optionLabel,
            long unitPriceInclVat, int quantity, int commissionBp, int supplyLeadDays,
            Integer agreedLeadDays, String withdrawalRestrictionReason) {

        long lineAmount() {
            return unitPriceInclVat * quantity;
        }

        /** 항목마다 자르고 원 미만은 버린다. 전체를 한 번에 자른 값과 1원 다를 수 있고 그게 맞다(`D8`) */
        long commissionAmount() {
            return lineAmount() * commissionBp / 10_000;
        }
    }

    /**
     * 주문을 만든다.
     *
     * <p>순서에 이유가 있다.
     * <ol>
     *   <li>장바구니에서 읽어 값을 박제한다 — 이후로는 상품 테이블을 안 본다</li>
     *   <li><b>재고를 `sku_id` 오름차순으로 깎는다</b> — 잠그는 순서가 요청마다 다르면 데드락이다(`D11`)</li>
     *   <li>주문·셀러주문·항목을 넣는다</li>
     *   <li>산 것을 장바구니에서 뺀다</li>
     * </ol>
     *
     * <p>재고를 먼저 깎는 것은 <b>조건부 UPDATE 가 곧 검사</b>라서다. 주문을 만들고 나서 깎으면
     * 모자랄 때 되돌릴 것이 늘어난다.
     */
    @Transactional
    public Created create(long userId, Command command) {
        List<Line> lines = readLines(userId, command.cartItemIds());
        if (lines.isEmpty()) {
            throw new ShopException(ErrorCode.ORDER_EMPTY);
        }

        decreaseStock(lines);

        long orderId = insertOrder(userId, lines);
        insertSellerOrdersAndItems(orderId, lines, command.withdrawalRestrictionAgreed());
        insertShipping(orderId, command.shipping());
        insertContractDocuments(orderId);
        removeOrderedFromCart(userId, command.cartItemIds());

        return readCreated(orderId);
    }

    /**
     * 계약 시점의 문서 판을 박제한다(`D2` R22, 전자상거래법 제13조제2항 후단).
     *
     * <p><b>가격·수수료율과 같은 이유다.</b> 화면 바닥 링크는 「지금의 문안」을 가리켜서,
     * 청약철회 안내를 개정하면 지나간 주문의 계약 조건까지 바뀐 것처럼 보인다.
     *
     * <p><b>약관도 여기서 다시 박제한다.</b> {@code user_consent} 가 이미 판을 가리키지만
     * 그건 <b>가입 시점</b>이다 — 가입 후 약관이 개정되면 주문 시점 약관이 무엇이었는지
     * 아무 데도 없다.
     *
     * <p>시행된 판은 못 고친다({@code policy_document_immutable}). 그 제약이 있어야
     * 「그때의 형태로 보존」이 성립하고, 그래야 이것이 서면이다(전자문서법 제4조의2 2호).
     *
     * <p><b>문서가 없으면 그 호는 안 붙는다.</b> 없는 것을 가리키는 행을 만드는 대신
     * 빠진 채로 두고, 빠졌다는 사실은 주문 상세가 그대로 내린다 —
     * 조용히 채우면 그 주문이 무엇을 고지받았는지가 사실과 달라진다.
     */
    private void insertContractDocuments(long orderId) {
        jdbc.sql("""
                        insert into order_contract_document (order_id, policy_document_id, clause)
                        select :orderId, d.policy_document_id, v.clause
                          from (values ('withdrawal_guide', 'withdrawal'),
                                       ('withdrawal_guide', 'exchange'),
                                       ('dispute_resolution', 'dispute')) as v (code, clause)
                          join lateral (
                              select p.policy_document_id
                                from policy_document p
                               where p.code = v.code and p.effective_at <= now()
                               order by p.effective_at desc, p.version desc
                               limit 1) d on true
                        """)
                .param("orderId", orderId)
                .update();

        jdbc.sql("""
                        insert into order_contract_document (order_id, consent_item_id, clause)
                        select :orderId, c.consent_item_id, 'terms'
                          from consent_item c
                         where c.code = 'terms_of_service' and c.effective_at <= now()
                         order by c.effective_at desc, c.version desc
                         limit 1
                        """)
                .param("orderId", orderId)
                .update();
    }

    /**
     * 장바구니에서 살 것을 읽는다.
     *
     * <p><b>여기가 박제 지점이다.</b> 담을 때 값을 복사해 두면 오른 가격을 안 보여주게 되므로
     * 장바구니는 지금 값을 보여주고(청크 9), 굳히는 것은 이 순간이다.
     *
     * <p>살 수 없는 것이 섞여 있으면 통째로 거절한다. 조용히 빼면 사려던 것과 산 것이 달라진다.
     */
    private List<Line> readLines(long userId, List<Long> cartItemIds) {
        List<Line> lines = jdbc.sql("""
                        select s.sku_id, p.seller_id, p.name as product_name, ci.quantity,
                               s.price_incl_vat, coalesce(p.commission_bp, sel.commission_bp) as commission_bp,
                               coalesce(p.supply_lead_days, :legalLeadDays) as supply_lead_days,
                               p.supply_lead_days as agreed_lead_days,
                               case when p.is_withdrawal_restricted then p.withdrawal_restriction_reason end
                                    as withdrawal_restriction_reason,
                               (select string_agg(pov.value, ' / ' order by po.sort_no, pov.sort_no)
                                  from sku_option_value sov
                                  join product_option_value pov
                                    on pov.product_option_value_id = sov.product_option_value_id
                                  join product_option po
                                    on po.product_option_id = pov.product_option_id
                                 where sov.sku_id = s.sku_id) as option_label
                          from cart_item ci
                          join cart c on c.cart_id = ci.cart_id
                          join sku s on s.sku_id = ci.sku_id
                          join sku_stock st on st.sku_id = s.sku_id
                          join product p on p.product_id = s.product_id
                          join seller sel on sel.seller_id = p.seller_id
                         where c.user_id = :userId
                           and ci.cart_item_id in (:cartItemIds)
                           and s.deleted_at is null and s.status = 'on_sale'
                           and p.deleted_at is null and p.status = 'on_sale'
                        """)
                .param("userId", userId)
                .param("cartItemIds", cartItemIds)
                .param("legalLeadDays", LEGAL_SUPPLY_LEAD_DAYS)
                .query((rs, rowNum) -> new Line(
                        rs.getLong("sku_id"),
                        rs.getLong("seller_id"),
                        rs.getString("product_name"),
                        rs.getString("option_label"),
                        rs.getLong("price_incl_vat"),
                        rs.getInt("quantity"),
                        rs.getInt("commission_bp"),
                        rs.getInt("supply_lead_days"),
                        // 약정이 없으면 null 이다. getInt 로 받으면 0(당일 발송)이 돼서
                        // 「약정 없음」과 「오늘 보내기로 약속」이 같아진다(`D23`).
                        rs.getObject("agreed_lead_days", Integer.class),
                        rs.getString("withdrawal_restriction_reason")))
                .list();

        if (lines.size() != cartItemIds.size()) {
            throw new ShopException(ErrorCode.SKU_NOT_BUYABLE);
        }
        return lines;
    }

    /**
     * 재고를 깎는다.
     *
     * <p><b>조건부 UPDATE 다</b>(`D11`). 조회해서 검사하고 다시 쓰는 절차가 없어서 그 사이에 끼어들 틈이 없다.
     * 갱신된 행이 0개면 재고가 모자란 것이다.
     *
     * <p><b>`sku_id` 오름차순으로 돈다.</b> 잠그는 순서가 요청마다 다르면
     * A 가 10을 잡고 20을 기다리는 사이 B 가 20을 잡고 10을 기다려서 서로 막힌다.
     */
    private void decreaseStock(List<Line> lines) {
        List<Line> ordered = new ArrayList<>(lines);
        ordered.sort(Comparator.comparingLong(Line::skuId));

        for (Line line : ordered) {
            int changed = jdbc.sql("""
                            update sku_stock set on_hand = on_hand - :quantity
                             where sku_id = :skuId and available_count >= :quantity
                            """)
                    .param("quantity", line.quantity())
                    .param("skuId", line.skuId())
                    .update();

            if (changed == 0) {
                // 왜 0행인지는 UPDATE 결과만으로 모른다. 실패 경로에서만 도는 쿼리다(`D11`).
                throw new ShopException(ErrorCode.OUT_OF_STOCK,
                        "sku_id=%d 의 재고가 모자란다".formatted(line.skuId()));
            }
        }
    }

    /**
     * 주문 머리를 넣는다. 합계는 항목에서 계산해 같이 채운다.
     *
     * <p>합계를 따로 계산하는 게 아니라 <b>항목의 합이 곧 주문 총액</b>이다(`D8`).
     * 어긋나면 커밋할 때 지연 트리거가 잡는다.
     */
    private long insertOrder(long userId, List<Line> lines) {
        long totalAmount = lines.stream().mapToLong(Line::lineAmount).sum();
        long commissionTotal = lines.stream().mapToLong(Line::commissionAmount).sum();
        long shippingTotal = shippingFeeBySeller(lines).values().stream().mapToLong(Long::longValue).sum();

        return ExposedNumber.insertWith("", "주문번호", number -> jdbc.sql("""
                                insert into shop_order (order_number, user_id, total_amount,
                                                        commission_total, shipping_fee_total, payable_amount)
                                values (:number, :userId, :total, :commission, :shipping, :payable)
                                returning order_id
                                """)
                .param("number", number)
                .param("userId", userId)
                .param("total", totalAmount)
                .param("commission", commissionTotal)
                .param("shipping", shippingTotal)
                .param("payable", totalAmount + shippingTotal)
                .query(Long.class)
                .single());
    }

    private void insertSellerOrdersAndItems(long orderId, List<Line> lines, boolean restrictionAgreed) {
        Map<Long, Long> shippingFees = shippingFeeBySeller(lines);

        Map<Long, List<Line>> bySeller = new LinkedHashMap<>();
        for (Line line : lines) {
            bySeller.computeIfAbsent(line.sellerId(), k -> new ArrayList<>()).add(line);
        }

        bySeller.forEach((sellerId, sellerLines) -> {
            // 묶음 안에서 가장 긴 것을 쓴다. 한 셀러 묶음은 한 번에 나가므로
            // 가장 늦게 준비되는 항목이 그 묶음의 발송 시점을 정한다(`V26`).
            int leadDays = sellerLines.stream()
                    .mapToInt(Line::supplyLeadDays)
                    .max()
                    .orElse(LEGAL_SUPPLY_LEAD_DAYS);

            // 약정만 따로 센다(`14c`). 약정이 하나도 없으면 null 이고, 그것이
            // 「법정 기한이 걸렸다」는 사실이다 — 계산 결과(leadDays)로는 그것을 못 가른다.
            Integer agreedDays = sellerLines.stream()
                    .map(Line::agreedLeadDays)
                    .filter(java.util.Objects::nonNull)
                    .max(Integer::compareTo)
                    .orElse(null);

            long sellerOrderId = insertSellerOrder(
                    orderId, sellerId, shippingFees.get(sellerId), leadDays, agreedDays);

            for (Line line : sellerLines) {
                jdbc.sql("""
                                insert into order_item (seller_order_id, sku_id, product_name, option_label,
                                                        unit_price_incl_vat, quantity, line_amount,
                                                        commission_bp, commission_amount,
                                                        withdrawal_restriction_reason,
                                                        withdrawal_restriction_agreed_at)
                                values (:sellerOrderId, :skuId, :productName, :optionLabel,
                                        :unitPriceInclVat, :quantity, :lineAmount, :bp, :commission,
                                        :restrictionReason, :restrictionAgreedAt)
                                """)
                        .param("sellerOrderId", sellerOrderId)
                        .param("skuId", line.skuId())
                        .param("productName", line.productName())
                        .param("optionLabel", line.optionLabel())
                        .param("unitPriceInclVat", line.unitPriceInclVat())
                        .param("quantity", line.quantity())
                        .param("lineAmount", line.lineAmount())
                        .param("bp", line.commissionBp())
                        .param("commission", line.commissionAmount())
                        .param("restrictionReason", agreedRestriction(line, restrictionAgreed))
                        .param("restrictionAgreedAt",
                                MADE_TO_ORDER.equals(agreedRestriction(line, restrictionAgreed))
                                        ? OffsetDateTime.now() : null)
                        .update();
            }
        });
    }

    /**
     * 이 거래에서 <b>실제로 성립한</b> 청약철회 제한(`Q5`, `D2` R4).
     *
     * <p>상품에 붙은 것은 「이 사유에 해당할 수 있다」는 표시고, 제한이 서려면
     * <b>사유마다 다른 조건</b>이 차야 한다(전자상거래법 제17조제2항).
     *
     * <ul>
     *   <li>{@code digital_content}(5호) — 제공이 개시돼야 한다. 반품 접수가 배송완료에서만
     *       열려서({@code OrderStatusPolicy}) 공급이 전제고, 주문 시점에 성립한 것으로 본다</li>
     *   <li>{@code made_to_order}(시행령 제21조) — <b>거래마다 별도 고지와 소비자의 동의</b>가
     *       요건이다. 안 받았으면 제한이 없는 주문이고, 나중에 무를 수 있다</li>
     *   <li>{@code copyable_media}(4호) — <b>여기서는 절대 성립하지 않는다.</b> 포장 훼손은
     *       물건이 돌아와야 아는 사실이고 제17조제5항이 그 입증을 우리에게 지웠다.
     *       판단은 반품 검수 축(43·44)이 한다</li>
     * </ul>
     *
     * @return 성립한 사유. 없으면 {@code null}
     */
    private static String agreedRestriction(Line line, boolean restrictionAgreed) {
        String reason = line.withdrawalRestrictionReason();
        if (reason == null) {
            return null;
        }
        if (DIGITAL_CONTENT.equals(reason)) {
            return reason;
        }
        return MADE_TO_ORDER.equals(reason) && restrictionAgreed ? reason : null;
    }

    /**
     * 셀러 묶음 하나를 넣는다. <b>노출 번호를 여기서 뽑는다</b>(`D9`).
     *
     * <p>주문번호와 같은 방식으로 부딪히면 다시 뽑는다. 재시도 수도 같은 상수를 쓴다 —
     * 두 번호가 같은 난수 집합에서 나오므로 충돌 확률도 같고, 따로 두면 한쪽만 고치는 날이 온다.
     */
    private long insertSellerOrder(long orderId, long sellerId, long shippingFee,
            int leadDays, Integer agreedDays) {

        return ExposedNumber.insertWith(SELLER_ORDER_PREFIX, "셀러 주문번호", number -> jdbc.sql("""
                                insert into seller_order (seller_order_number, order_id,
                                                          seller_id, shipping_fee, supply_lead_days,
                                                          agreed_lead_days)
                                values (:number, :orderId, :sellerId, :fee, :leadDays, :agreedDays)
                                returning seller_order_id
                                """)
                .param("number", number)
                .param("orderId", orderId)
                .param("sellerId", sellerId)
                .param("fee", shippingFee)
                .param("leadDays", leadDays)
                .param("agreedDays", agreedDays)
                .query(Long.class)
                .single());
    }

    /** 셀러마다 한 번씩 붙는다. 한 셀러 것을 여럿 사도 배송비는 하나다 */
    private Map<Long, Long> shippingFeeBySeller(List<Line> lines) {
        List<Long> sellerIds = lines.stream().map(Line::sellerId).distinct().toList();

        Map<Long, Long> fees = new LinkedHashMap<>();
        jdbc.sql("select seller_id, default_shipping_fee from seller where seller_id in (:ids)")
                .param("ids", sellerIds)
                .query((rs, rowNum) -> Map.entry(rs.getLong("seller_id"), rs.getLong("default_shipping_fee")))
                .list()
                .forEach(entry -> fees.put(entry.getKey(), entry.getValue()));

        return fees;
    }

    /**
     * 배송지를 넣는다.
     *
     * <p><b>주문 행에 안 담는다</b>(`D13`). 주문은 5년 보존이고 배송지는 파기 대상이라
     * 한 행에 두면 남길 것과 지울 것이 엉킨다. 파기는 청크 10a 가 이 행을 지운다.
     */
    private void insertShipping(long orderId, Shipping shipping) {
        jdbc.sql("""
                        insert into order_shipping (order_id, receiver_name, receiver_phone,
                                                    postal_code, address1, address2, delivery_memo)
                        values (:orderId, :name, :phone, :postalCode, :address1, :address2, :memo)
                        """)
                .param("orderId", orderId)
                .param("name", shipping.receiverName())
                .param("phone", shipping.receiverPhone())
                .param("postalCode", shipping.postalCode())
                .param("address1", shipping.address1())
                .param("address2", shipping.address2())
                .param("memo", shipping.deliveryMemo())
                .update();
    }

    /** 산 것만 뺀다. 고르지 않은 것은 담긴 채로 남는다 */
    private void removeOrderedFromCart(long userId, List<Long> cartItemIds) {
        jdbc.sql("""
                        delete from cart_item
                         where cart_item_id in (:ids)
                           and cart_id in (select cart_id from cart where user_id = :userId)
                        """)
                .param("ids", cartItemIds)
                .param("userId", userId)
                .update();
    }

    private Created readCreated(long orderId) {
        return jdbc.sql("""
                        select order_id, order_number, payable_amount
                          from shop_order where order_id = :orderId
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new Created(
                        rs.getLong("order_id"),
                        rs.getString("order_number"),
                        rs.getLong("payable_amount")))
                .single();
    }

}
