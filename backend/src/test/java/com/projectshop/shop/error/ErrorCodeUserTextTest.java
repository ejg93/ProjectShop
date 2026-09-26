package com.projectshop.shop.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 응답의 {@code message} 문구를 대조한다(`Q233`, `D20` 「화면 문구는 존댓말이다」).
 *
 * <p><b>문구는 서버 한 곳에 있고 화면은 그대로 그린다.</b> 그래서 존댓말인지를 화면의 {@code screen-text}
 * 시험이 못 본다 — 문구가 변수로 들어오기 때문이다. 여기서 본다.
 */
@DisplayName("오류의 사용자 문구")
class ErrorCodeUserTextTest {

    /**
     * 존댓말 어미로 끝난다. 문장 끝의 마침표까지 본다 — {@code 니다} 는 「입니다」「아닙니다」「습니다」를
     * 다 받는다(`screen-text` 시험과 같은 기준).
     */
    private static final Pattern HONORIFIC_END = Pattern.compile("(니다|세요|십시오)\\.$");

    /**
     * 문구가 반드시 있어야 하는 코드. 화면 열둘(`Q228` 이 셌다)이 닿는 서비스 코드 21 과 어느 화면에나
     * 닿는 공통 12 다(`Q233`). <b>새 화면이 새 코드에 닿으면 여기와 {@link ErrorCode} 에 같이 더한다.</b>
     */
    static final Set<ErrorCode> REQUIRED_USER_TEXT = EnumSet.copyOf(List.of(
            // 쿠폰 — 만들기·등록
            ErrorCode.COUPON_NOT_APPLICABLE, ErrorCode.COUPON_CODE_TAKEN,
            ErrorCode.COUPON_CODE_NOT_ISSUABLE, ErrorCode.COUPON_ALREADY_ISSUED,
            // 역할 편집
            ErrorCode.ROLE_FORBIDDEN, ErrorCode.ROLE_NOT_ASSIGNABLE, ErrorCode.USER_NOT_FOUND,
            // 문의 — 남기기·거두기·답하기
            ErrorCode.INQUIRY_ALREADY_CLOSED, ErrorCode.INQUIRY_FORBIDDEN, ErrorCode.INQUIRY_NOT_FOUND,
            ErrorCode.PRODUCT_NOT_FOUND, ErrorCode.SELLER_ORDER_NOT_FOUND,
            // 후기
            ErrorCode.REVIEW_ALREADY_WRITTEN, ErrorCode.REVIEW_NOT_ALLOWED,
            // 멤버·초대
            ErrorCode.SELLER_INVITATION_INVALID, ErrorCode.SELLER_LAST_OWNER,
            ErrorCode.SELLER_MEMBER_FORBIDDEN, ErrorCode.SELLER_MEMBER_NOT_FOUND,
            // 상품 등록
            ErrorCode.PRODUCT_FORBIDDEN, ErrorCode.PRODUCT_WITHOUT_SKU, ErrorCode.SKU_OPTION_MISMATCH,
            // 공통
            ErrorCode.UNAUTHENTICATED, ErrorCode.ACCESS_DENIED, ErrorCode.VALIDATION_FAILED,
            ErrorCode.TOO_MANY_REQUESTS, ErrorCode.MALFORMED_REQUEST, ErrorCode.UNSUPPORTED_MEDIA_TYPE,
            ErrorCode.METHOD_NOT_ALLOWED, ErrorCode.INTERNAL, ErrorCode.ACCOUNT_INACTIVE,
            ErrorCode.IMPERSONATION_READ_ONLY, ErrorCode.IMPERSONATION_REVOKED,
            ErrorCode.SESSION_SUPERSEDED));

    @Test
    @DisplayName("목록의 코드는 공통 문구가 아니라 제 문구를 든다")
    void requiredCodesHaveOwnText() {
        assertThat(REQUIRED_USER_TEXT).hasSize(33);
        // INTERNAL 은 제 문구가 공통 문구와 같다 — 뜻이 같아서다. 그 하나만 빼고 본다.
        assertThat(REQUIRED_USER_TEXT.stream()
                .filter(code -> code != ErrorCode.INTERNAL)
                .filter(code -> code.userText().equals(ErrorCode.FALLBACK_USER_TEXT)
                        || code.userText().equals(ErrorCode.FALLBACK_CLIENT_USER_TEXT))
                .toList())
                .as("문구를 안 넣어 공통 문구로 떨어진 코드")
                .isEmpty();
    }

    @Test
    @DisplayName("모든 코드의 문구가 존댓말로 끝난다")
    void everyTextIsHonorific() {
        assertThat(Arrays.stream(ErrorCode.values())
                .filter(code -> !HONORIFIC_END.matcher(code.userText()).find())
                .map(code -> code.name() + " = " + code.userText())
                .toList())
                .as("존댓말 어미(니다·세요·십시오 + 마침표)로 안 끝나는 문구")
                .isEmpty();
    }

    @Test
    @DisplayName("문구에 내부 값이 안 섞인다 — 영문 소문자 낱말이 없다")
    void noInternalTokens() {
        // 「이미 answered 인 문의다」가 화면에 나가던 자리를 다시 안 만든다(`D20` 「내부 값을 그대로 안 보인다」).
        assertThat(Arrays.stream(ErrorCode.values())
                .filter(code -> code.userText().matches(".*[a-z_]{2,}.*"))
                .map(ErrorCode::name)
                .toList())
                .isEmpty();
    }
}
