package com.projectshop.shop.seller;

import java.util.Arrays;

/**
 * 통신판매업 신고를 안 해도 되는 사유. 저장값은 {@code seller.mail_order_exempt_reason} 이고
 * 목록은 {@code seller_exempt_reason_check} 다.
 *
 * <p>전자상거래법 제12조 단서가 면제 기준을 공정위 고시에 위임했고, 그 고시가 정한 둘이다.
 * <b>신고번호가 없는 것과 면제라 없는 것은 다르다</b> — 자유 텍스트로 두면 화면이
 * "아직 안 넣은 것" 과 구분해서 그릴 수가 없다.
 *
 * <p>표가 아니라 {@code check} 인 이유는 <b>값이 늘면 화면 문구가 따라와야 해서</b>다(`D23`).
 * 사유가 하나 생기면 그것을 뭐라고 적을지 정하는 것이 곧 배포다.
 */
public enum MailOrderExemption {

    /** 직전연도 통신판매 거래 횟수가 50회 미만 */
    UNDER_50_TRANSACTIONS,

    /** 부가가치세법상 간이과세자 */
    SIMPLIFIED_TAXPAYER;

    /** 저장값. DB 는 소문자고 응답은 대문자다(`D5` 「형식」) */
    String code() {
        return name().toLowerCase();
    }

    /**
     * 저장값을 사유로 되돌린다. 신고번호가 있는 셀러는 면제가 아니라서 {@code null} 이 그대로 나간다.
     *
     * <p><b>모르는 값이면 터진다.</b> 조용히 통과시키면 화면이 처음 보는 값을 받는데,
     * 그 자리는 법이 표시를 요구하는 자리다(`D2` R1).
     */
    static MailOrderExemption of(String code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(reason -> reason.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("모르는 신고 면제 사유다: " + code));
    }
}
