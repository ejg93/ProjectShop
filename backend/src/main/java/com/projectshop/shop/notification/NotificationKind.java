package com.projectshop.shop.notification;

import java.util.Arrays;

/**
 * 이 통지가 광고인가 거래 통지인가. 저장값은 {@code notification.kind} 와
 * {@code notification_template.kind} 이고 목록은 그 둘의 {@code check} 다(`V43`).
 *
 * <p><b>법이 가른 구분이다</b>(정보통신망법 제50조, `D2`). {@link #ADVERTISING} 은 사전 동의가
 * 있어야 나가고 야간에는 별도 동의가 더 필요한데, {@link #TRANSACTIONAL} 은 그 동의를 안 본다 —
 * 주문 확인을 광고 동의로 막으면 산 사람이 자기 거래 내역을 못 받는다.
 *
 * <p><b>둘을 가르는 자리가 두 곳이라 목록도 두 곳이다.</b> 판에 적힌 종류가
 * 발송 이력에 박제된다 — 판이 개정돼도 그때 보낸 것이 무엇이었는지가 안 흔들린다(`D18`).
 *
 * <p>열거형으로 두는 근거는 `D23` 「가르는 물음」이다 — <b>값이 하나 늘면 코드를 고쳐야 한다.</b>
 * 종류가 늘면 <b>어느 동의를 봐야 하는지</b>가 같이 정해져야 하고, 그건 표에 행을 넣어서 안 정해진다.
 */
enum NotificationKind {

    /** 거래 통지. 동의를 안 본다 — 주문 확인·환불 완료·처리 결과 */
    TRANSACTIONAL,

    /** 광고. <b>{@link AdvertisingGate} 를 지나야 나간다</b>(제50조제1항·제3항·제8항) */
    ADVERTISING;

    /** 저장값. DB 는 소문자다(`D5` 「형식」) */
    String code() {
        return name().toLowerCase();
    }

    /**
     * 저장값을 종류로 되돌린다.
     *
     * <p><b>모르는 값이면 터진다.</b> 여기서 조용히 통과시키면 <b>동의를 봐야 하는 통지가
     * 안 보고 나가는</b> 쪽으로 틀릴 수 있어서, 틀리는 방향이 법에 걸리는 쪽이다.
     */
    static NotificationKind of(String code) {
        return Arrays.stream(values())
                .filter(kind -> kind.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("모르는 통지 종류다: " + code));
    }
}
