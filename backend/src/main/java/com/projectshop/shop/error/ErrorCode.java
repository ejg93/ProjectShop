package com.projectshop.shop.error;

import org.springframework.http.HttpStatus;

/**
 * 이 서비스가 내는 오류의 목록. <b>한 곳에 모아 두는 것이 이 enum 의 목적이다.</b>
 *
 * <p>오류가 클래스로 흩어지면 "우리가 몇 가지 오류를 내나" 에 답하려고 패키지를 뒤져야 하고,
 * `D5` 의 403/404 표와 대조할 수가 없다. 여기 한 화면에 있으면 눈으로 대조된다.
 *
 * <p><b>{@code type} 이 계약이다.</b> `D5` 가 "프론트는 상태 코드가 아니라 {@code type} 으로 분기한다"
 * 고 정했다. 상태 코드는 여러 오류가 공유하지만 {@code type} 은 하나를 가리킨다.
 * <b>이 값을 바꾸면 화면이 깨진다</b> — 문구({@code title})는 다듬어도 되지만 슬러그는 못 바꾼다.
 *
 * <p>URN 을 쓰는 이유는 <b>없는 도메인을 가리키지 않으려는 것</b>이다.
 * {@code https://example.com/...} 를 쓰면 언젠가 열어보는 사람이 생기고 그때 404 가 난다.
 * RFC 9457 은 {@code type} 이 역참조 가능해야 한다고 요구하지 않는다.
 */
public enum ErrorCode {

    // 인증
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "login-failed", "아이디 또는 비밀번호가 맞지 않는다"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "unauthenticated", "로그인이 필요하다"),
    ALREADY_WITHDRAWN(HttpStatus.UNAUTHORIZED, "already-withdrawn", "이미 탈퇴한 계정이다"),
    PASSWORD_MISMATCH(HttpStatus.UNPROCESSABLE_CONTENT, "password-mismatch", "비밀번호가 맞지 않는다"),

    // 가입
    EMAIL_TAKEN(HttpStatus.CONFLICT, "email-taken", "이미 가입된 이메일이다"),

    // 동의
    UNKNOWN_CONSENT_ITEM(HttpStatus.UNPROCESSABLE_CONTENT, "unknown-consent-item", "모르는 동의 항목이다"),
    REQUIRED_CONSENT_MISSING(HttpStatus.UNPROCESSABLE_CONTENT, "required-consent-missing",
            "필수 동의 항목이다"),
    CONSENT_DEPENDENCY(HttpStatus.UNPROCESSABLE_CONTENT, "consent-dependency",
            "먼저 동의해야 하는 항목이 있다"),
    REQUIRED_CONSENT_REVOKE(HttpStatus.UNPROCESSABLE_CONTENT, "required-consent-revoke",
            "필수 동의 항목이라 철회할 수 없다"),
    CONSENT_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "consent-item-not-found", "그런 동의 항목이 없다"),
    CONSENT_NOT_FOUND(HttpStatus.NOT_FOUND, "consent-not-found", "동의한 적이 없는 항목이다"),

    // 권한
    //
    // /api/me 아래는 403 이다. `D5` 표가 사용자 자원에 404 를 준 것은 남의 계정을 가리키는 경우고,
    // 여기는 자기 것이라 존재가 이미 드러나 있다. 404 로 감출 대상이 없다.
    ACCOUNT_FORBIDDEN(HttpStatus.FORBIDDEN, "account-forbidden", "계정을 다룰 권한이 없다"),
    CONSENT_FORBIDDEN(HttpStatus.FORBIDDEN, "consent-forbidden", "동의 내역을 다룰 권한이 없다"),
    AUDIT_FORBIDDEN(HttpStatus.FORBIDDEN, "audit-forbidden", "감사 로그를 볼 권한이 없다"),

    // 상품
    //
    // 403 이다. 상품은 공개 목록에 있어서 존재를 숨길 이유가 없다(`D5` 의 자원별 표).
    PRODUCT_FORBIDDEN(HttpStatus.FORBIDDEN, "product-forbidden", "상품을 다룰 권한이 없다"),
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "product-not-found", "그런 상품이 없다"),
    PRODUCT_WITHOUT_SKU(HttpStatus.UNPROCESSABLE_CONTENT, "product-without-sku",
            "팔 조합이 하나도 없다"),
    SKU_OPTION_MISMATCH(HttpStatus.UNPROCESSABLE_CONTENT, "sku-option-mismatch",
            "조합이 선언한 옵션과 맞지 않는다"),
    PRODUCT_TRANSITION_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_CONTENT, "product-transition-not-allowed",
            "지금 상태에서 할 수 없는 것이다"),
    // 트리거도 같은 것을 막는다. 여기서 먼저 걸러야 이유가 500 이 아니라 422 로 나간다.
    SELLER_NOT_VERIFIED(HttpStatus.UNPROCESSABLE_CONTENT, "seller-not-verified",
            "셀러 신원정보가 확인되지 않았다"),

    // 장바구니
    //
    // 담긴 것을 못 찾는 것은 404 다. 장바구니는 주인만 만지고 주인은 요청이 가리키므로
    // 남의 것을 가리킬 방법이 없다 — 감출 존재가 없어서 403/404 를 저울질할 일도 없다.
    CART_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "cart-item-not-found", "장바구니에 없는 것이다"),
    SKU_NOT_BUYABLE(HttpStatus.UNPROCESSABLE_CONTENT, "sku-not-buyable", "지금 살 수 없는 것이다"),

    // 주문
    //
    // 재고 부족은 422 다. 요청 형식은 맞는데 지금 상태가 못 받아들이는 것이라 400 이 아니다.
    ORDER_EMPTY(HttpStatus.UNPROCESSABLE_CONTENT, "order-empty", "주문할 것이 없다"),
    OUT_OF_STOCK(HttpStatus.UNPROCESSABLE_CONTENT, "out-of-stock", "재고가 모자란다"),

    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "order-not-found", "그런 주문이 없다"),
    SELLER_ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "seller-order-not-found", "그런 셀러 주문이 없다"),

    // 주문 하나를 못 볼 때는 404 다(`D5`). 이건 목록에 쓴다 —
    // 목록에는 가리키는 자원이 없어서 403 이 존재를 흘리지 않고,
    // 0건과 못 봄이 갈려야 개수로 정보가 새지 않는다.
    ORDER_FORBIDDEN(HttpStatus.FORBIDDEN, "order-forbidden", "주문을 볼 권한이 없다"),

    // 전이표에 없는 이동이다(`D7`). 422 인 이유는 요청 형식이 아니라 지금 상태가 못 받아서다 —
    // 같은 요청이 상태가 달랐으면 통과한다.
    ORDER_TRANSITION_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_CONTENT, "order-transition-not-allowed",
            "지금 상태에서 할 수 없는 처리다"),

    // 주문에 쓰인 SKU 가 있으면 옵션 축을 못 바꾼다. 바꾸면 지나간 주문의 옵션 라벨이
    // 가리키던 것이 사라진다 — 영수증이 뜻을 잃는다.
    PRODUCT_OPTIONS_LOCKED(HttpStatus.UNPROCESSABLE_CONTENT, "product-options-locked",
            "주문에 쓰인 상품이라 옵션 구성을 바꿀 수 없다"),

    // 멱등키
    //
    // 409 는 "진행중" 이 아니라 "앞 요청을 기다렸는데 너무 길다" 다. 처리와 기록이 한 트랜잭션이라
    // 진행중인 행은 남에게 안 보이고, 뒤 요청은 앞이 끝날 때까지 대기하다 알아서 재생을 읽는다.
    // 그 대기가 lock_timeout 을 넘겼을 때만 여기로 온다(`D11`).
    IDEMPOTENCY_IN_PROGRESS(HttpStatus.CONFLICT, "idempotency-in-progress",
            "같은 요청이 아직 처리 중이다"),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_CONTENT, "idempotency-key-reused",
            "같은 키로 다른 요청을 보냈다"),

    // 입력
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "validation-failed", "요청 형식이 맞지 않는다"),
    SORT_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "sort-not-allowed", "정렬할 수 없는 필드다"),

    // 그 밖
    //
    // 판정이 실패하거나 예상 못 한 것이 터졌을 때다. detail 에 원인을 안 담는다 —
    // 스택이나 SQL 문구가 응답으로 나가면 그 자체가 정보 유출이다(`D14`).
    INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR, "internal", "요청을 처리하지 못했다");

    private static final String URN_PREFIX = "urn:shop:error:";

    private final HttpStatus status;
    private final String slug;
    private final String title;

    ErrorCode(HttpStatus status, String slug, String title) {
        this.status = status;
        this.slug = slug;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    /** 응답의 {@code type}. 프론트가 이 값으로 분기한다 */
    public String type() {
        return URN_PREFIX + slug;
    }

    public String title() {
        return title;
    }
}
