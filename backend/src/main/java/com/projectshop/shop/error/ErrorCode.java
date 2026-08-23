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
 * <p><b>{@code tag:} URI 다</b>(RFC 4151). 없는 도메인을 가리키지 않으려는 것이 첫 이유고 —
 * {@code https://...} 를 쓰면 언젠가 열어보는 사람이 생기고 그때 404 가 난다 —
 * RFC 9457 이 {@code type} 의 역참조를 요구하지 않아서 그래도 된다.
 *
 * <p><b>{@code urn:shop:} 에서 옮겨 왔다</b>(`Q1`). RFC 8141 은 URN 의 네임스페이스 식별자를
 * <b>등록</b>하게 하는데 {@code shop} 은 정식 등록도, IANA 가 주는 비공식 이름({@code urn-<숫자>})도
 * 아니었다 — <b>문법만 맞고 URN 은 아닌 값</b>이었다. {@code tag:} 는 등록이 필요 없고
 * 권한 이름과 날짜로 소유를 밝힌다.
 *
 * <p>{@code projectshop.example} 은 자리표시다. RFC 2606 이 예시용으로 잡아 둔 이름이라
 * 남의 것을 가리킬 위험이 없다. <b>진짜 도메인이 생기면 그때 바꾸고, 그것은 계약 변경이다.</b>
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
    POLICY_NOT_FOUND(HttpStatus.NOT_FOUND, "policy-not-found", "그런 정책 문서가 없다"),

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

    // 셀러
    //
    // 아직 안 파는 셀러와 아예 없는 셀러가 같은 404 다. 가르면 번호를 하나씩 두드려서
    // 심사 중인 셀러가 존재한다는 것을 알아낼 수 있다.
    SELLER_NOT_FOUND(HttpStatus.NOT_FOUND, "seller-not-found", "그런 셀러가 없다"),

    // 장바구니
    //
    // 담긴 것을 못 찾는 것은 404 다. 장바구니는 주인만 만지고 주인은 요청이 가리키므로
    // 남의 것을 가리킬 방법이 없다 — 감출 존재가 없어서 403/404 를 저울질할 일도 없다.
    CART_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "cart-item-not-found", "장바구니에 없는 것이다"),
    SKU_NOT_BUYABLE(HttpStatus.UNPROCESSABLE_CONTENT, "sku-not-buyable", "지금 살 수 없는 것이다"),

    // 주문
    ORDER_EMPTY(HttpStatus.UNPROCESSABLE_CONTENT, "order-empty", "주문할 것이 없다"),

    // 재고 부족은 409 다.
    //
    // 청크 10-2 가 422 로 넣었다. 그때 견준 것이 400 과 422 뿐이었고 409 를 안 봤다 —
    // "형식은 맞는데 지금 상태가 못 받는다" 는 그 판단은 409 의 정의이기도 하다.
    //
    // 11c-3b 가 409 로 바꿨다. 근거 셋이다.
    //  1. `D5` 상태 코드 표가 재고 부족을 409 로 적었다. 표준(RFC 9110)은 이 경계를 안 갈랐으므로
    //     2순위가 침묵하고 3순위(프로젝트 규약)가 이긴다(`D23` 축 1)
    //  2. 가르는 기준은 "다른 시점이면 통과했나" 다(아래 청약철회 두 줄과 같은 기준).
    //     재고는 남이 먼저 샀을 뿐이고 채워지면 같은 요청이 통과한다 — 고칠 내용이 없다
    //  3. 위 EMAIL_TAKEN 이 같은 모양이다. 대상은 만들 자원인데 충돌은 다른 행과 난다
    OUT_OF_STOCK(HttpStatus.CONFLICT, "out-of-stock", "재고가 모자란다"),

    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "order-not-found", "그런 주문이 없다"),
    SELLER_ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "seller-order-not-found", "그런 셀러 주문이 없다"),

    // 주문 하나를 못 볼 때는 404 다(`D5`). 이건 목록에 쓴다 —
    // 목록에는 가리키는 자원이 없어서 403 이 존재를 흘리지 않고,
    // 0건과 못 봄이 갈려야 개수로 정보가 새지 않는다.
    ORDER_FORBIDDEN(HttpStatus.FORBIDDEN, "order-forbidden", "주문을 볼 권한이 없다"),

    // 전이표에 없는 이동이다(`D7`).
    //
    // 409 다. `D5` 「동작」이 "허용되지 않은 전이는 409" 라고 정했고 RFC 9110 §15.5.10 의
    // 409 정의("대상 자원의 현재 상태와의 충돌")와 같은 자리다.
    //
    // 청크 11-2 가 422 로 넣었던 것을 11c-3 이 고쳤다. `11-2` 는 HTTP 경로를 안 만들었으므로
    // 이 코드가 밖으로 나간 적이 없다 — 첫 노출이 11c-3 이라 고치는 대가가 없다.
    ORDER_TRANSITION_NOT_ALLOWED(HttpStatus.CONFLICT, "order-transition-not-allowed",
            "지금 상태에서 할 수 없는 처리다"),

    // 청약철회 기간이 지났다(`D2` R3, 전자상거래법 제17조).
    //
    // 409 로 가른 기준은 <b>더 일찍 왔으면 통과했나</b> 다. 기한은 행에 박제된 값이라
    // 대상 자원의 현재 상태고, 그 상태와의 충돌은 409 다.
    WITHDRAWAL_PERIOD_EXPIRED(HttpStatus.CONFLICT, "withdrawal-period-expired",
            "청약철회 기간이 지났다"),

    // 청약철회가 제한된 상품이다(`D2` R4, 전자상거래법 제17조제2항).
    //
    // 이쪽은 422 다. 상품 속성이라 언제 다시 와도 답이 같다 — 상태와의 충돌이 아니라
    // 요청 내용이 규칙을 못 통과하는 것이다.
    WITHDRAWAL_RESTRICTED(HttpStatus.UNPROCESSABLE_CONTENT, "withdrawal-restricted",
            "청약철회가 제한된 상품이다"),

    // 관리자가 옮길 때는 사유가 남아야 한다(`D7`). 정상 경로가 아니라서 왜 그랬는지가 없으면
    // 나중에 데이터가 왜 이 모양인지 아무도 모른다.
    TRANSITION_REASON_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT, "transition-reason-required",
            "관리자 처리에는 사유가 필요하다"),

    // 주문에 쓰인 SKU 가 있으면 옵션 축을 못 바꾼다. 바꾸면 지나간 주문의 옵션 라벨이
    // 가리키던 것이 사라진다 — 영수증이 뜻을 잃는다.
    PRODUCT_OPTIONS_LOCKED(HttpStatus.UNPROCESSABLE_CONTENT, "product-options-locked",
            "주문에 쓰인 상품이라 옵션 구성을 바꿀 수 없다"),

    // 결제
    //
    // 504 다. RFC 9110 §15.6.5 가 "위쪽에서 제때 응답을 못 받았다" 로 정의했고 이 자리가 그것이다.
    // 500 으로 뭉치면 우리가 터진 것과 결제사가 안 받은 것이 같은 코드가 돼서,
    // 화면이 「잠시 뒤 다시」 를 안내할지 「고객센터」 를 안내할지 못 가른다.
    //
    // 여기 오는 것은 재시도를 다 쓴 뒤다(`D11`). 그전에는 같은 멱등키로 다시 부르므로
    // 결제사가 중복 승인을 안 낸다.
    PAYMENT_GATEWAY_UNAVAILABLE(HttpStatus.GATEWAY_TIMEOUT, "payment-gateway-unavailable",
            "결제사가 응답하지 않는다"),

    // 422 다. 형식은 맞는데 수단과 값이 안 맞는 것이라, 입구의 형식 검사(400)로는 안 걸린다 —
    // 카드번호 칸이 비어 있는 것 자체는 계좌이체에서 정상이다.
    PAYMENT_CARD_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT, "payment-card-required",
            "카드 결제에는 카드번호가 필요하다"),

    // 환불
    REFUND_NOT_FOUND(HttpStatus.NOT_FOUND, "refund-not-found", "그런 환불 요청이 없다"),

    // 409 다. 이미 처리된 요청을 또 처리하려는 것이라 대상 자원의 현재 상태와 부딪힌다 —
    // ORDER_TRANSITION_NOT_ALLOWED 와 같은 기준이다(RFC 9110 §15.5.10).
    REFUND_ALREADY_DECIDED(HttpStatus.CONFLICT, "refund-already-decided",
            "이미 처리된 환불 요청이다"),

    // 자기가 낸 요청은 자기가 승인 못 한다(12a).
    //
    // 403 이다. 요청 내용이 잘못된 것도(422) 상태와 부딪히는 것도(409) 아니라
    // <b>이 사람이라서</b> 안 되는 것이고, 그건 권한 판정의 답과 같은 자리다.
    // 다른 사람이 부르면 같은 요청이 통과한다는 점이 422 와 갈리는 기준이다.
    REFUND_SELF_APPROVAL(HttpStatus.FORBIDDEN, "refund-self-approval",
            "자기가 낸 환불 요청은 자기가 승인할 수 없다"),

    // 422 다. 환불할 수 있는 것보다 많이 달라는 것이라 언제 다시 와도 답이 같다.
    // 상한은 결제액과 항목별 누계 둘 다이고(money-invariants) 어느 쪽이든 이 코드로 나간다.
    REFUND_EXCEEDS_LIMIT(HttpStatus.UNPROCESSABLE_CONTENT, "refund-exceeds-limit",
            "환불할 수 있는 금액을 넘는다"),

    // 422 다. 결제가 안 된 주문은 돌려줄 돈이 없다 — 결제하면 통과하지만 그건 다른 요청이다.
    REFUND_NOT_PAYABLE(HttpStatus.UNPROCESSABLE_CONTENT, "refund-not-payable",
            "결제되지 않은 주문은 환불할 수 없다"),

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
    INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR, "internal", "요청을 처리하지 못했다"),

    // 프레임워크가 MVC 에 닿기 전에 끊는 것.
    //
    // 이 넷이 없으면 전부 validation-failed 하나로 뭉친다 — 405 와 415 와 깨진 JSON 이
    // 같은 type 으로 나가고, 그러면 「상태 코드가 아니라 type 으로 분기한다」(`D5`)는 근거가
    // 이 경로에서만 뒤집힌다. 상태 코드보다 type 이 더 뭉치는 자리가 된다.
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "malformed-request", "요청을 읽지 못했다"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "method-not-allowed",
            "그 경로에 쓸 수 없는 메서드다"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported-media-type",
            "다룰 수 없는 미디어 타입이다"),
    // 문의(59)
    //
    // 못 보는 것도 404 다. 403 을 주면 문의번호를 훑어서 실재하는 비공개 문의의 지도를
    // 그릴 수 있고, 그것이 곧 「어느 상품에 비공개 문의가 몇 건 있나」다
    // (`RefundQuery` 와 같은 판단, `D5` 의 자원별 표).
    INQUIRY_NOT_FOUND(HttpStatus.NOT_FOUND, "inquiry-not-found", "그런 문의가 없다"),

    // 403 이다. 답할 권한이 없는 것이라 <b>이 사람이라서</b> 안 되는 것이고,
    // 다른 사람이 부르면 같은 요청이 통과한다(REFUND_SELF_APPROVAL 과 같은 기준).
    INQUIRY_FORBIDDEN(HttpStatus.FORBIDDEN, "inquiry-forbidden", "문의를 다룰 권한이 없다"),

    // 409 다. 이미 답한 문의에 또 답하려는 것이라 대상 자원의 현재 상태와 부딪힌다.
    // 내려간 게시물에 답하려는 것도 여기로 온다 — 답이 안 보이는 자리에 답을 쓰는 것이다.
    INQUIRY_ALREADY_CLOSED(HttpStatus.CONFLICT, "inquiry-already-closed",
            "이미 처리된 문의다"),

    ENDPOINT_NOT_FOUND(HttpStatus.NOT_FOUND, "endpoint-not-found", "그런 경로가 없다");

    /**
     * RFC 4151 의 {@code tag:} URI. 권한 이름과 날짜가 소유를 밝힌다.
     *
     * <p>날짜는 <b>이 이름을 쓰기 시작한 해</b>고 오류를 더할 때마다 바꾸지 않는다 —
     * 바꾸면 같은 오류가 해마다 다른 {@code type} 으로 나간다.
     */
    private static final String TAG_PREFIX = "tag:projectshop.example,2026:error:";

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
        return TAG_PREFIX + slug;
    }

    public String title() {
        return title;
    }
}
