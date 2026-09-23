package com.projectshop.shop.review;

import java.util.Arrays;
import java.util.Locale;

import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;

/**
 * 후기를 신고하고 내리는 사유(`Q167`, {@code review_report.reason}·{@code review.blocked_reason}).
 *
 * <p><b>한 목록이 두 칸에 쓰인다.</b> 신고한 사유로 내리므로 값이 갈리면 옮길 때 사람이 다시 골라야 한다 —
 * DB 도 두 제약에 같은 목록을 뒀다(`V85`). {@code EnumConstraintTest} 가 둘 다와 맞춰 본다.
 *
 * <p><b>넷 다 약관에서 온다.</b> 문의의 {@code advertisement} 는 정보통신망법 제50조의7 에서 왔지만
 * 후기에는 그런 근거가 없다 — 공개한 운영정책(`R27`)의 「삭제 기준」 넷이 이것이다.
 *
 * <p>바깥은 대문자, DB 는 소문자다({@code inquiry.BlockReason} 과 같다).
 */
enum ReviewReason {

    /** 광고 또는 홍보 목적 */
    ADVERTISEMENT,

    /** 욕설·비방 */
    ABUSE,

    /** 구매한 상품과 관련 없는 내용 */
    UNRELATED,

    /** 다른 사람의 개인정보가 담긴 내용 */
    PRIVACY;

    /** DB 에 들어가는 값. {@code EnumConstraintTest} 가 이 이름의 메서드를 읽는다 */
    String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** 저장값으로 고른다. 모르는 값이면 마이그레이션과 어긋난 것이라 터진다 */
    static ReviewReason of(String code) {
        return Arrays.stream(values())
                .filter(reason -> reason.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("모르는 후기 사유다: " + code));
    }

    /** 바깥 값으로 고른다. 요청이 틀린 것이라 400 이다 */
    static ReviewReason ofRequest(String name) {
        return Arrays.stream(values())
                .filter(reason -> reason.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new ShopException(ErrorCode.VALIDATION_FAILED, "모르는 사유다: " + name));
    }
}
