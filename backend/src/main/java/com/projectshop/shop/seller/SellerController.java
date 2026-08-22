package com.projectshop.shop.seller;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;

/**
 * 셀러를 보는 입구.
 *
 * <p>지금은 공개 신원 조회 하나다. 셀러를 만들고 고치는 경로는 여기 없다 —
 * 그건 관리자·셀러 자신의 일이라 판정이 붙고, 청크 `3c` 뒤에 온다.
 */
@RestController
@RequestMapping("/api")
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
    /**
     * 내가 속한 셀러 목록(`13f-1`).
     *
     * <p>경로가 {@code /api/me/sellers} 다. <b>「내」 것을 묻는 자원은 {@code /api/me} 밑에 둔다</b>(`D5`) 2014
     * {@code /api/sellers} 밑에 두면 번호 없이 목록을 부르는 것이 되어 <b>전체 셀러 목록</b>으로 읽힌다.
     */
    @GetMapping("/me/sellers")
    public List<SellerQuery.Membership> myMemberships(@AuthenticationPrincipal ShopUser user) {
        return sellerQuery.membershipsOf(user.id());
    }

    @GetMapping("/sellers/{sellerId}")
    public SellerQuery.PublicIdentity identity(@PathVariable long sellerId) {
        return sellerQuery.findPublicIdentity(sellerId);
    }
}
