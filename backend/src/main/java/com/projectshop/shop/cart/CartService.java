package com.projectshop.shop.cart;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;

/**
 * 장바구니. 사기 전에 담아 두는 자리다.
 *
 * <p><b>비로그인도 담는다.</b> 동의 없이 되는 근거는 개인정보법 제15조제1항 4호다 —
 * "계약을 체결하는 과정에서 정보주체의 요청에 따른 조치" 가 담아 달라는 요청 그 자체다.
 *
 * <p><b>권한 판정이 없다.</b> 장바구니는 주인만 만지고 주인을 가리키는 것이 요청에 실려 온다 —
 * 로그인이면 계정, 아니면 쿠키 토큰. 남의 것을 가리킬 방법 자체가 없어서 판정할 대상이 없다.
 */
@Service
public class CartService {

    /** 한 조합을 몇 개까지. 상한이 없으면 재고 전체를 담아 두고 남이 못 사게 할 수 있다 */
    private static final int MAX_QUANTITY = 99;

    private final JdbcClient jdbc;

    CartService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 장바구니의 주인. 로그인이면 {@code userId}, 아니면 {@code token} 이 찬다.
     *
     * <p>둘 다 있거나 둘 다 없는 요청은 만들지 않는다 — 스키마도 그 조합을 막는다.
     */
    public record Owner(Long userId, String token) {

        public static Owner of(Long userId, String token) {
            return userId != null ? new Owner(userId, null) : new Owner(null, token);
        }
    }

    /**
     * @param optionLabel  고른 조합. 옵션이 없는 상품은 {@code null} 이다(`8c`).
     *                     <b>지금 값이라 셀러가 옵션 이름을 바꾸면 같이 바뀐다</b> —
     *                     주문할 때 {@code order_item.option_label} 로 박제되는 것과 다른 성격이다
     * @param sellerName   파는 사람. <b>주문서가 청약 이전에 신원을 제공해야 한다</b>
     *                     (`D2` R1, 전자상거래법 제20조제2항). 그 화면이 셀러별로 묶으려면 여기가 실려야 한다
     * @param priceInclVat 지금 가격이다. <b>주문할 때 박제한다</b>(청크 10) — 담아 둔 사이에 바뀔 수 있다
     * @param shippingFee  이 셀러의 배송비. <b>묶음마다 한 번 붙는다</b> — 같은 셀러 것을 여럿 담아도
     *                     한 번이라 화면이 셀러로 묶어 더한다. 담을 때 결정되는 값이 아니라
     *                     지금 값이고, 주문할 때 {@code seller_order.shipping_fee} 로 박제된다
     * @param available    지금 살 수 있나. 재고가 없거나 상품이 내려갔으면 거짓이다
     */
    public record Item(long cartItemId, long skuId, long productId, String productName,
            String optionLabel, long sellerId, String sellerName,
            long priceInclVat, long shippingFee, int quantity, boolean available) {
    }

    public record Cart(List<Item> items, long total) {
    }

    /**
     * 담는다. 이미 있으면 <b>수량을 더한다</b>.
     *
     * <p>덮어쓰지 않는 이유는 "같은 것을 하나 더" 가 담기의 뜻이라서다.
     * 수량을 정확히 정하는 것은 {@link #changeQuantity} 다.
     */
    @Transactional
    public void add(Owner owner, long skuId, int quantity) {
        requireBuyable(skuId);

        long cartId = findOrCreateCart(owner);

        jdbc.sql("""
                        insert into cart_item (cart_id, sku_id, quantity)
                        values (:cartId, :skuId, :quantity)
                        on conflict (cart_id, sku_id) do update
                            set quantity = least(cart_item.quantity + excluded.quantity, :max)
                        """)
                .param("cartId", cartId)
                .param("skuId", skuId)
                .param("quantity", Math.min(quantity, MAX_QUANTITY))
                .param("max", MAX_QUANTITY)
                .update();
    }

    /** 수량을 정한다. 0 이하는 담기를 취소한 것이라 행을 지운다 */
    @Transactional
    public void changeQuantity(Owner owner, long skuId, int quantity) {
        long cartId = findCart(owner)
                .orElseThrow(() -> new ShopException(ErrorCode.CART_ITEM_NOT_FOUND));

        if (quantity <= 0) {
            remove(owner, skuId);
            return;
        }

        int changed = jdbc.sql("""
                        update cart_item set quantity = :quantity
                         where cart_id = :cartId and sku_id = :skuId
                        """)
                .param("quantity", Math.min(quantity, MAX_QUANTITY))
                .param("cartId", cartId)
                .param("skuId", skuId)
                .update();

        if (changed == 0) {
            throw new ShopException(ErrorCode.CART_ITEM_NOT_FOUND);
        }
    }

    @Transactional
    public void remove(Owner owner, long skuId) {
        findCart(owner).ifPresent(cartId ->
                jdbc.sql("delete from cart_item where cart_id = :cartId and sku_id = :skuId")
                        .param("cartId", cartId)
                        .param("skuId", skuId)
                        .update());
    }

    /**
     * 담긴 것을 본다.
     *
     * <p><b>가격은 지금 값이다.</b> 담을 때 값을 복사해 두면 오른 가격을 안 보여주게 되고,
     * 주문 화면과 장바구니가 다른 금액을 말한다. 박제는 주문이 만들어질 때 한다(`D8`·청크 10).
     */
    public Cart read(Owner owner) {
        List<Item> items = findCart(owner)
                .map(this::itemsOf)
                .orElseGet(List::of);

        long total = items.stream()
                .filter(Item::available)
                .mapToLong(item -> item.priceInclVat() * item.quantity())
                .sum();

        return new Cart(items, total);
    }

    /**
     * 로그인할 때 비로그인 장바구니를 계정으로 옮긴다.
     *
     * <p><b>같은 조합이 양쪽에 있으면 큰 쪽을 남긴다.</b> 더하면 같은 것을 두 기기에서 담은 사람이
     * 의도하지 않은 수량을 받고, 그걸 눈치 못 채면 그대로 주문된다.
     *
     * <p>옮긴 뒤 비로그인 장바구니는 <b>그 자리에서 지운다.</b> 남겨 두면 같은 물건이 두 군데 있고
     * 다음 로그인에 또 병합된다.
     */
    @Transactional
    public void mergeIntoAccount(long userId, String token) {
        if (token == null || token.isBlank()) {
            return;
        }

        Long guestCartId = findCart(new Owner(null, token)).orElse(null);
        if (guestCartId == null) {
            return;
        }

        long userCartId = findOrCreateCart(new Owner(userId, null));

        jdbc.sql("""
                        insert into cart_item (cart_id, sku_id, quantity)
                        select :userCartId, sku_id, quantity
                          from cart_item where cart_id = :guestCartId
                        on conflict (cart_id, sku_id) do update
                            set quantity = greatest(cart_item.quantity, excluded.quantity)
                        """)
                .param("userCartId", userCartId)
                .param("guestCartId", guestCartId)
                .update();

        // cart_item 은 cascade 로 같이 사라진다.
        jdbc.sql("delete from cart where cart_id = :id")
                .param("id", guestCartId)
                .update();
    }

    /**
     * 담을 수 있는 것인가.
     *
     * <p>파는 중이 아닌 상품은 애초에 못 담는다. 담고 나서 상품이 내려가는 것은 막을 수 없고,
     * 그때는 {@code available} 이 거짓으로 나온다 — <b>지우지 않는다.</b>
     * 다시 팔면 살 수 있고, 사라지면 왜 없어졌는지를 사용자가 모른다.
     */
    private void requireBuyable(long skuId) {
        boolean buyable = Boolean.TRUE.equals(jdbc.sql("""
                        select exists(
                            select 1 from sku s
                              join product p on p.product_id = s.product_id
                             where s.sku_id = :skuId
                               and s.deleted_at is null and s.status = 'on_sale'
                               and p.deleted_at is null and p.status = 'on_sale')
                        """)
                .param("skuId", skuId)
                .query(Boolean.class)
                .single());

        if (!buyable) {
            throw new ShopException(ErrorCode.SKU_NOT_BUYABLE);
        }
    }

    /**
     * 담긴 것.
     *
     * <p><b>조합 라벨과 파는 사람이 같이 나간다</b>(청크 15-1). 없으면 화면이 같은 상품의
     * 다른 조합을 <b>글자가 똑같은 두 줄</b>로 그리고, 주문서는 누구에게 사는 것인지를
     * 청약 전에 못 보여준다(`D2` R1).
     *
     * <p>조합 라벨을 만드는 서브쿼리가 {@code OrderService.readLines} 와 같은 모양이다.
     * <b>같은 값을 두 번 만드는 것이 아니다</b> — 저쪽은 주문 시점의 값을 박제하려고 읽고,
     * 여기는 지금 값을 보여주려고 읽는다. 셀러가 옵션 이름을 바꾸면 이쪽만 따라 바뀌는 것이 맞다.
     */
    private List<Item> itemsOf(long cartId) {
        return jdbc.sql("""
                        select ci.cart_item_id, ci.sku_id, ci.quantity,
                               p.product_id, p.name as product_name, s.price_incl_vat,
                               p.seller_id, sel.name as seller_name, sel.default_shipping_fee,
                               (select string_agg(pov.value, ' / ' order by po.sort_no, pov.sort_no)
                                  from sku_option_value sov
                                  join product_option_value pov
                                    on pov.product_option_value_id = sov.product_option_value_id
                                  join product_option po
                                    on po.product_option_id = pov.product_option_id
                                 where sov.sku_id = s.sku_id) as option_label,
                               (s.deleted_at is null and s.status = 'on_sale'
                                and p.deleted_at is null and p.status = 'on_sale'
                                and s.stock_count >= ci.quantity) as available
                          from cart_item ci
                          join sku s on s.sku_id = ci.sku_id
                          join product p on p.product_id = s.product_id
                          join seller sel on sel.seller_id = p.seller_id
                         where ci.cart_id = :cartId
                         order by ci.created_at, ci.cart_item_id
                        """)
                .param("cartId", cartId)
                .query((rs, rowNum) -> new Item(
                        rs.getLong("cart_item_id"),
                        rs.getLong("sku_id"),
                        rs.getLong("product_id"),
                        rs.getString("product_name"),
                        rs.getString("option_label"),
                        rs.getLong("seller_id"),
                        rs.getString("seller_name"),
                        rs.getLong("price_incl_vat"),
                        rs.getLong("default_shipping_fee"),
                        rs.getInt("quantity"),
                        rs.getBoolean("available")))
                .list();
    }

    private java.util.Optional<Long> findCart(Owner owner) {
        return jdbc.sql("""
                        select cart_id from cart
                         where (cast(:userId as bigint) is not null
                                and user_id = cast(:userId as bigint))
                            or (cast(:token as text) is not null
                                and cart_token = cast(:token as text))
                        """)
                .param("userId", owner.userId())
                .param("token", owner.token())
                .query(Long.class)
                .optional();
    }

    private long findOrCreateCart(Owner owner) {
        return findCart(owner).orElseGet(() -> jdbc.sql("""
                        insert into cart (user_id, cart_token) values (:userId, :token)
                        returning cart_id
                        """)
                .param("userId", owner.userId())
                .param("token", owner.token())
                .query(Long.class)
                .single());
    }
}
