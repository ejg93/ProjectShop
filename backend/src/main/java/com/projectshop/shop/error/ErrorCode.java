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
    // 필터가 끊는 자리 셋(Q84). 셋 다 그전에는 본문 없이 상태 코드만 나갔다 —
    // 받는 쪽이 trace_id 도 type 도 못 받았고, 오류율 지표(62)에도 안 잡혔다.
    //
    // 셋을 가른 이유는 받는 쪽이 갈라 대응해서다. 죽은 계정은 다시 로그인해도 소용없고,
    // 밀려난 세션은 다시 로그인하면 되며, 인가 거부는 로그인 상태가 맞는데 권한이 없다.
    ACCOUNT_INACTIVE(HttpStatus.UNAUTHORIZED, "account-inactive", "쓸 수 없는 계정이다"),
    SESSION_SUPERSEDED(HttpStatus.UNAUTHORIZED, "session-superseded",
            "다른 기기에서 로그인해 이 세션이 끊겼다"),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "access-denied", "이 요청을 할 권한이 없다"),
    // 요청이 너무 잦다(71). RFC 6585 가 429 를 정하고 Retry-After 가 같이 나간다.
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "too-many-requests",
            "요청이 너무 잦다"),
    PASSWORD_MISMATCH(HttpStatus.UNPROCESSABLE_CONTENT, "password-mismatch", "비밀번호가 맞지 않는다"),

    /**
     * 흔하거나 추측하기 쉬운 비밀번호다(`D14-2`, NIST SP 800-63B). 목록에 있거나, 서비스 이름·그 사람의
     * 이메일 앞부분·이름을 품거나, 한 글자를 되풀이한 것이다. <b>어느 것에 걸렸는지는 안 가른다</b> —
     * 가르면 목록에 무엇이 있는지를 하나씩 물어볼 수 있다.
     */
    PASSWORD_TOO_COMMON(HttpStatus.UNPROCESSABLE_CONTENT, "password-too-common",
            "흔하거나 추측하기 쉬운 비밀번호다"),

    /**
     * 대행할 수 없다(`16b`). 권한이 없거나, 대상이 관리자·자기 자신·탈퇴·정지 계정이다 —
     * <b>넷을 안 가른다</b>. 가르면 계정 번호를 두드려 누가 관리자인지를 셀 수 있다.
     */
    IMPERSONATION_FORBIDDEN(HttpStatus.FORBIDDEN, "impersonation-forbidden", "그 계정을 대행할 수 없다"),

    /** 대행 중에는 쓰기가 막힌다(`16b`). 보기만 한다 — 끝내기와 로그아웃만 열려 있다 */
    IMPERSONATION_READ_ONLY(HttpStatus.FORBIDDEN, "impersonation-read-only", "대행 중에는 보기만 한다"),

    /** 이미 대행 중인데 또 시작하거나, 대행 중이 아닌데 끝낸다 */
    IMPERSONATION_CONFLICT(HttpStatus.CONFLICT, "impersonation-conflict", "대행 상태가 맞지 않는다"),

    /**
     * 재설정 토큰이 없거나, 만료됐거나, 이미 썼다(`5c-1`).
     *
     * <p><b>셋을 안 가른다.</b> 「만료됐다」와 「그런 토큰이 없다」를 갈라 주면 남의 링크를
     * 주워 온 사람이 <b>그것이 실재했는지</b>를 알게 된다(`D14`).
     */
    PASSWORD_RESET_TOKEN_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "password-reset-token-invalid",
            "쓸 수 없는 재설정 토큰이다"),

    // 가입
    EMAIL_TAKEN(HttpStatus.CONFLICT, "email-taken", "이미 가입된 이메일이다"),

    /** 확인 토큰이 없거나, 만료됐거나, 이미 썼다(`5e-1`). 셋을 안 가른다 */
    EMAIL_CHANGE_TOKEN_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "email-change-token-invalid",
            "쓸 수 없는 확인 토큰이다"),

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

    // 역할 편집(`16`)
    ROLE_FORBIDDEN(HttpStatus.FORBIDDEN, "role-forbidden", "역할을 다룰 권한이 없다"),

    /**
     * 그 사람이 없다.
     *
     * <p><b>숨기지 않는다.</b> 이 입구는 역할을 편집할 수 있는 사람만 지나고, 그 사람에게
     * 계정의 존재는 이미 보이는 것이다 — 감추면 <b>없는 번호와 못 보는 번호</b>가 같아져서
     * 관리자가 오타를 못 알아챈다.
     */
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "user-not-found", "그런 사용자가 없다"),

    /**
     * 이 입구로 줄 수 있는 역할이 아니다.
     *
     * <p>조직 역할은 소속과 함께 들어가야 해서 {@code SellerMemberService} 가 든다(`5a`).
     * 여기서 주면 {@code V4} 의 트리거가 막는데 그것은 <b>500</b> 이라, 부르는 쪽이 고칠 수 있는
     * 오류로 먼저 답한다.
     */
    ROLE_NOT_ASSIGNABLE(HttpStatus.UNPROCESSABLE_CONTENT, "role-not-assignable",
            "이 입구로 줄 수 있는 역할이 아니다"),

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

    /**
     * 초대 토큰이 못 쓰는 것이다.
     *
     * <p><b>넷을 안 가른다.</b> 만료·취소·이미 수락·없는 토큰이 같은 응답이다 —
     * 가르면 남의 초대 링크를 주워 온 사람이 <b>그 셀러가 누구를 불렀는지</b>를 알게 된다
     * ({@link #PASSWORD_RESET_TOKEN_INVALID} 와 같은 판단, `D14`).
     */
    SELLER_INVITATION_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "seller-invitation-invalid",
            "쓸 수 없는 초대 토큰이다"),

    /**
     * 그 셀러의 멤버를 다룰 권한이 없다(`16a`).
     *
     * <p><b>없는 셀러와 남의 셀러가 같은 응답이다.</b> 가르면 번호를 두드려 어느 셀러가
     * 있는지 셀 수 있고, 조직 경계는 그 수를 안 흘리는 것까지가 경계다(`D14`).
     */
    SELLER_MEMBER_FORBIDDEN(HttpStatus.FORBIDDEN, "seller-member-forbidden",
            "셀러의 멤버를 다룰 권한이 없다"),

    SELLER_MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "seller-member-not-found",
            "그 셀러에 속한 사람이 아니다"),

    /**
     * 마지막 대표는 못 내린다(`Q165`).
     *
     * <p>대표가 0이 되면 그 셀러는 <b>멤버를 부를 수도 뺄 수도 없는 상태</b>로 잠긴다 —
     * 푸는 길이 관리자의 직접 개입뿐이라 그 자리를 안 만든다.
     */
    SELLER_LAST_OWNER(HttpStatus.UNPROCESSABLE_CONTENT, "seller-last-owner",
            "마지막 대표는 내보내거나 역할을 바꿀 수 없다"),

    /**
     * 어느 살아 있는 셀러의 마지막 대표라 탈퇴할 수 없다(`Q169`).
     *
     * <p>탈퇴는 역할 행을 안 지워서 위의 검사로는 안 걸린다. 대표를 넘긴 뒤에 탈퇴한다(사용자 선택) —
     * 풀어 주면 그 셀러가 위와 같은 자리로 잠긴다.
     */
    WITHDRAWAL_LAST_OWNER(HttpStatus.UNPROCESSABLE_CONTENT, "withdrawal-last-owner",
            "대표로 있는 셀러의 대표를 넘기기 전에는 탈퇴할 수 없다"),

    /**
     * 쿠폰을 쓸 수 없다.
     *
     * <p><b>넷을 안 가른다.</b> 없는 발급·남의 발급·이미 쓴 것·기한이 지난 것이 같은 응답이다 —
     * 가르면 번호를 두드려 <b>남이 무슨 쿠폰을 받았는지</b>를 알아낼 수 있다(`D14`).
     */
    COUPON_NOT_USABLE(HttpStatus.UNPROCESSABLE_CONTENT, "coupon-not-usable", "쓸 수 없는 쿠폰이다"),

    // 후기(`Q160`)
    /**
     * 이 후기를 쓰거나 고칠 수 없다.
     *
     * <p><b>넷을 안 가른다.</b> 없는 주문 줄·남의 주문 줄·아직 안 받은 것·권한이 없는 것이
     * 같은 응답이다 — 가르면 주문 줄 번호를 두드려 <b>남이 무엇을 샀는지</b> 셀 수 있다(`D14`).
     */
    REVIEW_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_CONTENT, "review-not-allowed",
            "이 주문에 후기를 쓸 수 없다"),

    /** 한 주문 줄에 살아 있는 후기는 하나다(`47`). 고치거나 지우고 다시 쓴다 */
    REVIEW_ALREADY_WRITTEN(HttpStatus.CONFLICT, "review-already-written",
            "이미 후기를 쓴 주문이다"),

    REVIEW_NOT_FOUND(HttpStatus.NOT_FOUND, "review-not-found", "그런 후기가 없다"),

    /**
     * 이 후기에 그 조작을 할 권한이 없다(`Q167`) — 남의 상품 후기에 답하는 셀러, 신고를 처리하는
     * 관리자가 아닌 사람이다. <b>쓰기의 {@link #REVIEW_NOT_ALLOWED} 와 가른다</b>: 그쪽은 넷을 한데
     * 묶어 422 로 숨기는 자리고, 여기는 대상이 공개 글이라 숨길 것이 없어 403 이다.
     */
    REVIEW_FORBIDDEN(HttpStatus.FORBIDDEN, "review-forbidden", "이 후기에 그 조작을 할 권한이 없다"),

    /** 같은 후기를 한 번만 신고한다({@code review_report_once}, `48`) */
    REVIEW_ALREADY_REPORTED(HttpStatus.CONFLICT, "review-already-reported", "이미 신고한 후기다"),

    REVIEW_REPORT_NOT_FOUND(HttpStatus.NOT_FOUND, "review-report-not-found", "그런 신고가 없다"),

    /**
     * 처리한 신고는 다시 못 연다(`D7`, {@code check_review_report_transition}). 판단이 뒤집혔으면
     * 신고가 아니라 후기를 되살린다 — 그래야 처음 판단과 뒤집은 판단이 둘 다 기록에 남는다.
     */
    REVIEW_REPORT_ALREADY_RESOLVED(HttpStatus.CONFLICT, "review-report-already-resolved",
            "이미 처리한 신고다"),

    /** 내려가지 않은 후기는 되살릴 것이 없다 */
    REVIEW_NOT_BLOCKED(HttpStatus.CONFLICT, "review-not-blocked", "내려간 후기가 아니다"),

    /**
     * 쿠폰은 살아 있는데 이 주문에 안 맞는다.
     *
     * <p>{@link #COUPON_NOT_USABLE} 과 가르는 이유는 <b>고칠 수 있는 쪽이라서</b>다 —
     * 최소 주문 금액에 못 미치거나 대상 셀러의 상품이 없는 것은 장바구니를 바꾸면 통과한다.
     * 여기서는 아무것도 안 흘린다: 그 쿠폰은 이미 자기 것이다.
     */
    COUPON_NOT_APPLICABLE(HttpStatus.UNPROCESSABLE_CONTENT, "coupon-not-applicable",
            "이 주문에 쓸 수 없는 쿠폰이다"),

    /**
     * 그 코드로 받을 쿠폰이 없다(`Q163`).
     *
     * <p><b>셋을 안 가른다.</b> 없는 코드·내려간 쿠폰·발급 기간이 아닌 것이 같은 응답이다 —
     * 가르면 코드를 찍어 보며 <b>어떤 쿠폰이 존재하는지</b>를 알아낼 수 있다(`D14`).
     * 코드는 밖에서 듣고 와서 치는 값이라 맞히기가 목록을 뿌린 것과 같아진다.
     */
    COUPON_CODE_NOT_ISSUABLE(HttpStatus.UNPROCESSABLE_CONTENT, "coupon-code-not-issuable",
            "지금 받을 수 없는 쿠폰 코드다"),

    /** 한 사람이 같은 쿠폰을 한 번만 받는다(`coupon_issue_once`, `49`) */
    COUPON_ALREADY_ISSUED(HttpStatus.CONFLICT, "coupon-already-issued", "이미 받은 쿠폰이다"),

    /** 같은 코드의 쿠폰이 이미 있다(`coupon.code` 유니크) */
    COUPON_CODE_TAKEN(HttpStatus.CONFLICT, "coupon-code-taken", "이미 쓰는 쿠폰 코드다"),

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

    // 물건이 안 왔는데 반품을 승인했다(`D2` R5, 전자상거래법 제18조제2항 1호).
    //
    // 409 다. 반품 행의 현재 상태와의 충돌이고, 입고되면 같은 요청이 통과한다.
    //
    // **막는 것은 `V63` 의 `return_request_timeline_check` 다.** 이 코드는 말을 붙이는 자리다 —
    // 그 제약은 지연이라 커밋에서야 터지고, 그때 나가는 것은 500 이다.
    RETURN_NOT_RECEIVED(HttpStatus.CONFLICT, "return-not-received",
            "반품 물건이 아직 입고되지 않았다"),

    // 반품 거절에는 사유가 필요하다(`V63` 의 `return_requires_rejection_reason`).
    //
    // 422 다. 언제 다시 와도 사유 없는 거절은 같은 답이라 상태와의 충돌이 아니다.
    RETURN_DECISION_REASON_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT,
            "return-decision-reason-required", "반품 거절에는 사유가 필요하다"),

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
    // 상품 사진(27, media-rules.md)
    //
    // 크기와 형식은 HTTP 가 이미 뜻을 정해 둔 자리라 그 코드를 쓴다(D5).
    // 장수 제한만 우리 규칙이라 422 다.
    IMAGE_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "image-too-large",
            "사진이 너무 크다"),
    IMAGE_TYPE_NOT_ALLOWED(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "image-type-not-allowed",
            "받지 않는 사진 형식이다"),
    IMAGE_LIMIT_REACHED(HttpStatus.UNPROCESSABLE_CONTENT, "image-limit-reached",
            "사진을 더 올릴 수 없다"),
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

    // 정산(20)
    //
    // 못 보는 것도 404 다. 403 을 주면 번호를 훑어서 실재하는 정산서를 셀 수 있고,
    // 그 수가 곧 셀러 수 × 개월이다 — 매출 추정에 쓰인다(`D9`).
    SETTLEMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "settlement-not-found", "그런 정산서가 없다"),

    // 403 이다. 볼 수 있는 셀러가 하나도 없는 것이라 <b>이 사람이라서</b> 안 되는 것이고,
    // 빈 목록을 주면 0건과 못 봄이 안 갈린다.
    SETTLEMENT_FORBIDDEN(HttpStatus.FORBIDDEN, "settlement-forbidden", "정산서를 볼 권한이 없다"),

    // 매출 통계(`41`). 403 인 이유는 위 정산과 같다 — 볼 수 있는 셀러가 하나도 없는 사람이다.
    SALES_STATS_FORBIDDEN(HttpStatus.FORBIDDEN, "sales-stats-forbidden", "매출 통계를 볼 권한이 없다"),
    // 시작이 끝보다 늦거나 한 번에 1년을 넘긴다. 넘기면 원장을 통째로 긁는 질의가 된다.
    STATS_RANGE_INVALID(HttpStatus.BAD_REQUEST, "stats-range-invalid", "통계 기간이 맞지 않는다"),

    // 지급(21)
    //
    // 409 다. 이미 처리된 지급을 또 다루려는 것이라 대상 자원의 현재 상태와 부딪힌다 —
    // REFUND_ALREADY_DECIDED 와 같은 기준이다(RFC 9110 §15.5.10).
    SETTLEMENT_ALREADY_DECIDED(HttpStatus.CONFLICT, "settlement-already-decided",
            "이미 처리된 지급이다"),

    // 403 이다. 요청 내용이 잘못된 것도(422) 상태와 부딪히는 것도(409) 아니라
    // <b>이 사람이라서</b> 안 되는 것이고, 다른 관리자가 부르면 같은 요청이 통과한다.
    SETTLEMENT_SELF_APPROVAL(HttpStatus.FORBIDDEN, "settlement-self-approval",
            "자기가 올린 지급은 자기가 승인할 수 없다"),

    // 422 다. 줄 돈이 없는 정산서라 언제 다시 와도 답이 같다.
    // 지급액이 0 이하면 이월로 넘어가지 지급 대상이 아니다.
    SETTLEMENT_NOTHING_TO_PAY(HttpStatus.UNPROCESSABLE_CONTENT, "settlement-nothing-to-pay",
            "지급할 금액이 없는 정산서다"),

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
