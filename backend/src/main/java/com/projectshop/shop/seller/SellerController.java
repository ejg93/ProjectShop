package com.projectshop.shop.seller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 셀러를 보는 입구.
 *
 * <p>지금은 공개 신원 조회 하나다. 셀러를 만들고 고치는 경로는 여기 없다 —
 * 그건 관리자·셀러 자신의 일이라 판정이 붙고, 청크 `3c` 뒤에 온다.
 */
@RestController
@RequestMapping("/api/sellers")
public class SellerController {

    private final SellerQuery sellerQuery;

    SellerController(SellerQuery sellerQuery) {
        this.sellerQuery = sellerQuery;
    }

    /**
     * 공개 신원 조회. <b>로그인 없이 부르고 판정이 없다</b>(`D2` R1).
     *
     * <p>법이 청약 이전에 제공하라고 한 값이라 사는 사람이 로그인하기 전에 본다.
     */
    @GetMapping("/{sellerId}")
    public SellerQuery.PublicIdentity identity(@PathVariable long sellerId) {
        return sellerQuery.findPublicIdentity(sellerId);
    }
}
