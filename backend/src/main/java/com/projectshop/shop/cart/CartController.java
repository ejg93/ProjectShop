package com.projectshop.shop.cart;

import java.time.Duration;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;

/**
 * 장바구니 입구. <b>로그인 없이도 쓴다.</b>
 *
 * <p>비로그인은 쿠키로 사람을 구분한다. <b>세션 ID 를 안 쓴다</b> —
 * 로그인할 때 세션 고정 방어로 ID 가 바뀌어서(`D14`), 세션에 묶으면 로그인하는 순간
 * 장바구니를 잃고 병합할 대상을 못 찾는다.
 */
@RestController
@RequestMapping("/api/cart")
public class CartController {

    /** 비로그인 장바구니를 가리키는 쿠키. 이 이름은 프론트와의 계약이다 */
    public static final String CART_COOKIE = "CART-TOKEN";

    /** 방치된 것을 파기 배치가 30일에 지운다(`D13`). 쿠키도 같은 기간을 산다 */
    private static final Duration COOKIE_AGE = Duration.ofDays(30);

    private final CartService cartService;

    CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    public CartService.Cart read(@AuthenticationPrincipal ShopUser user, HttpServletRequest http) {
        // 조회는 쿠키를 안 만든다. 구경만 하는 사람에게 식별자를 심을 이유가 없다.
        return cartService.read(ownerOf(user, tokenOf(http)));
    }

    /**
     * 담는다. 비로그인이면 <b>이때 쿠키를 만든다</b> — 담기 전에는 구분할 이유가 없다.
     */
    @PostMapping("/items")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void add(@AuthenticationPrincipal ShopUser user,
            @Valid @RequestBody AddRequest request,
            HttpServletRequest http, HttpServletResponse response) {

        String token = user == null ? issueTokenIfAbsent(http, response) : null;

        cartService.add(ownerOf(user, token), request.skuId(), request.quantity());
    }

    @PutMapping("/items/{skuId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changeQuantity(@AuthenticationPrincipal ShopUser user, @PathVariable long skuId,
            @Valid @RequestBody QuantityRequest request, HttpServletRequest http) {

        cartService.changeQuantity(ownerOf(user, tokenOf(http)), skuId, request.quantity());
    }

    @DeleteMapping("/items/{skuId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal ShopUser user, @PathVariable long skuId,
            HttpServletRequest http) {

        cartService.remove(ownerOf(user, tokenOf(http)), skuId);
    }

    /** @param quantity 담는 개수. 이미 있으면 더해진다 */
    public record AddRequest(@NotNull Long skuId, @NotNull Integer quantity) {
    }

    /** @param quantity 0 이하면 담기를 취소한 것이라 행이 사라진다 */
    public record QuantityRequest(@NotNull Integer quantity) {
    }

    private static CartService.Owner ownerOf(ShopUser user, String token) {
        return CartService.Owner.of(user == null ? null : user.id(), token);
    }

    /** 요청에 실린 장바구니 쿠키. 없으면 null */
    public static String tokenOf(HttpServletRequest http) {
        Cookie[] cookies = http.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (CART_COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /**
     * 쿠키를 만들어 응답에 싣는다. 이미 있으면 그대로 쓴다.
     *
     * <p>{@code HttpOnly} 다. 세션 쿠키와 달리 <b>스크립트가 읽을 이유가 없다</b> —
     * CSRF 토큰은 헤더에 실어야 해서 열어 뒀지만 이건 서버만 본다.
     */
    private static String issueTokenIfAbsent(HttpServletRequest http, HttpServletResponse response) {
        String existing = tokenOf(http);
        if (existing != null) {
            return existing;
        }

        String token = UUID.randomUUID().toString();

        response.addHeader("Set-Cookie", ResponseCookie.from(CART_COOKIE, token)
                .httpOnly(true)
                .sameSite("Lax")
                // 로컬이 http 라 끈다. 배포가 생기면 세션 쿠키와 같이 바뀐다(`D14`).
                .secure(false)
                .path("/")
                .maxAge(COOKIE_AGE)
                .build()
                .toString());

        return token;
    }
}
