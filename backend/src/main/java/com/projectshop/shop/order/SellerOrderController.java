package com.projectshop.shop.order;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;

/**
 * 셀러가 자기에게 넘어온 주문을 보는 입구.
 *
 * <p><b>경로를 갈랐다.</b> 구매자는 {@code /api/orders} 고 셀러는 여기다.
 * 한 경로에 두고 역할로 가르면 그 분기 하나가 판정이 되고, 틀렸을 때 남의 주문이 넘어간다.
 * 경로가 다르면 각 경로가 무엇을 보는지가 URL 에 적혀 있다.
 */
@RestController
@RequestMapping("/api/seller/orders")
public class SellerOrderController {

    private final SellerOrderQuery sellerOrders;

    SellerOrderController(SellerOrderQuery sellerOrders) {
        this.sellerOrders = sellerOrders;
    }

    /**
     * 처리할 것을 훑는다.
     *
     * @param sellerId 여러 셀러에 속한 사람이 하나만 볼 때. 안 주면 볼 수 있는 전부
     */
    @GetMapping
    public SellerOrderQuery.Page list(
            @AuthenticationPrincipal ShopUser user,
            @RequestParam(required = false) Long sellerId,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return sellerOrders.find(user.id(), sellerId, sort, page, size);
    }

    /** 묶음 하나를 펼친다. <b>경로에 노출 번호를 쓴다</b>(`D9`) */
    @GetMapping("/{sellerOrderNumber}")
    public SellerOrderQuery.Detail detail(
            @AuthenticationPrincipal ShopUser user,
            @PathVariable String sellerOrderNumber) {

        return sellerOrders.findByNumber(user.id(), sellerOrderNumber);
    }
}
