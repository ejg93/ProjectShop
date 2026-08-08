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
    PASSWORD_MISMATCH(HttpStatus.UNPROCESSABLE_ENTITY, "password-mismatch", "비밀번호가 맞지 않는다"),

    // 가입
    EMAIL_TAKEN(HttpStatus.CONFLICT, "email-taken", "이미 가입된 이메일이다"),

    // 동의
    UNKNOWN_CONSENT_ITEM(HttpStatus.UNPROCESSABLE_ENTITY, "unknown-consent-item", "모르는 동의 항목이다"),
    REQUIRED_CONSENT_MISSING(HttpStatus.UNPROCESSABLE_ENTITY, "required-consent-missing",
            "필수 동의 항목이다"),
    CONSENT_DEPENDENCY(HttpStatus.UNPROCESSABLE_ENTITY, "consent-dependency",
            "먼저 동의해야 하는 항목이 있다"),
    REQUIRED_CONSENT_REVOKE(HttpStatus.UNPROCESSABLE_ENTITY, "required-consent-revoke",
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
    PRODUCT_WITHOUT_SKU(HttpStatus.UNPROCESSABLE_ENTITY, "product-without-sku",
            "팔 조합이 하나도 없다"),
    SKU_OPTION_MISMATCH(HttpStatus.UNPROCESSABLE_ENTITY, "sku-option-mismatch",
            "조합이 선언한 옵션과 맞지 않는다"),

    // 입력
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "validation-failed", "요청 형식이 맞지 않는다"),

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
