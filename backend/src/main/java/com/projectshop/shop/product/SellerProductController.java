package com.projectshop.shop.product;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;

/**
 * 셀러가 자기 상품을 보는 입구.
 *
 * <p>{@code /api/products} 와 <b>경로를 가른 이유</b>는 조건의 성격이 달라서다.
 * 그쪽은 사용자를 안 받고 파는 중인 것만 주지만, 여기는 <b>보는 사람에 따라 답이 달라진다.</b>
 *
 * <p>둘을 한 경로에 두면 비로그인 분기까지 한 쿼리에 섞이고, 조건 하나가 틀리면
 * 공개 목록으로 {@code draft} 가 샌다. 그 실수는 조용해서 못 잡는다.
 */
@RestController
@RequestMapping("/api/seller/products")
public class SellerProductController {

    private final ProductQuery productQuery;

    SellerProductController(ProductQuery productQuery) {
        this.productQuery = productQuery;
    }

    /**
     * 팔기 전 상태와 재고가 같이 나온다.
     *
     * @param sellerId 여러 셀러에 속한 사람이 하나로 좁힐 때 쓴다. 안 주면 볼 수 있는 전부
     */
    @GetMapping
    public ProductQuery.SellerPage list(
            @AuthenticationPrincipal ShopUser user,
            @RequestParam(name = "seller_id", required = false) Long sellerId,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return productQuery.findForSeller(user.id(), sellerId, sort, page, size);
    }
}
