package com.projectshop.shop.inquiry;

import java.util.Arrays;

/**
 * 문의 한 건이 어디까지 왔나. 저장값은 {@code inquiry.status} 고 목록은
 * {@code inquiry_status_check} 다(`V53`).
 *
 * <p><b>{@link #BLOCKED} 와 {@link #WITHDRAWN} 은 둘 다 「안 보인다」인데 주체가 다르다</b> —
 * 앞은 우리가 가린 것이고 뒤는 쓴 사람이 거둔 것이다. 한 값으로 뭉개면
 * <b>누가 내렸는지가 데이터에서 사라진다.</b>
 *
 * <p>열거형으로 두는 근거는 `D23` 「가르는 물음」이다 — <b>값이 하나 늘면 코드를 고쳐야 한다.</b>
 * 공개 목록이 {@code received}·{@code answered} 만 담고, 답변 기한 초과 판정도
 * {@code received} 일 때만 선다.
 */
enum InquiryStatus {

    /** 접수됐다. 아직 답이 없다 — <b>기한을 세는 것은 이 상태뿐이다</b> */
    RECEIVED,

    /** 답이 달렸다. 답과 시각이 같이 있어야 한다({@code inquiry_answer_check}) */
    ANSWERED,

    /** 우리가 가렸다. 광고·욕설 같은 것이고 <b>사유가 남는다</b>({@code blocked_reason}) */
    BLOCKED,

    /** 쓴 사람이 거뒀다. 접수 상태에서만 갈 수 있다 */
    WITHDRAWN;

    /** 저장값. DB 는 소문자고 응답은 대문자다(`D5` 「형식」) */
    String code() {
        return name().toLowerCase();
    }

    /**
     * 저장값을 상태로 되돌린다.
     *
     * <p><b>모르는 값이면 터진다.</b> {@code inquiry_status_check} 가 이미 막고 있으므로
     * 여기 오는 모르는 값은 <b>마이그레이션과 이 enum 이 어긋났다</b>는 뜻이다.
     */
    static InquiryStatus of(String code) {
        return Arrays.stream(values())
                .filter(status -> status.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("모르는 문의 상태다: " + code));
    }
}
