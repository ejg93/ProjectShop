package com.projectshop.shop.notification;

import java.util.Arrays;

/**
 * 알림을 부른 사건. 저장값은 {@code notification.event_type} 이고
 * 목록은 {@code notification_event_type_check} 다(`43a-26`).
 *
 * <p><b>값이 마이그레이션 셋을 지나며 늘었다</b> — {@code V43} 일곱, {@code V45} 가
 * {@link #CONSENT_RESULT} 를, {@code V46} 이 {@link #CONSENT_RECONFIRM} 을 더해 아홉이다.
 * 그 사이 코드는 발송 자리마다 생 문자열이었고, <b>따라왔는지 볼 자리가 없었다.</b>
 *
 * <p><b>둘은 아직 아무도 안 보낸다.</b> {@link #PASSWORD_RESET} 과 {@link #EMAIL_CHANGE} 는
 * 목록에는 있는데 부르는 코드가 없다 — <b>빠뜨린 것이 아니라 미착수 청크의 몫</b>이다.
 * `D18` 이 「무엇을 보내나」에서 둘을 {@code 5c-1}(비밀번호 재설정)·{@code 5e-1}(이메일 변경 확인)에
 * 배정해 뒀다. <b>죽은 값으로 보고 지우면 그 청크가 제약부터 다시 열어야 한다.</b>
 *
 * <p><b>사건과 템플릿 코드가 대개 같은 값이다.</b> 갈리는 것은 광고 하나다 —
 * 사건은 {@link #ADVERTISEMENT} 하나인데 문안이 캠페인마다 달라서
 * {@link AdvertisingNotifications} 가 코드를 따로 넘긴다.
 */
enum NotificationEventType {

    /** 주문이 들어왔다 */
    ORDER_PLACED,

    /** 결제가 끝났다 */
    PAYMENT_COMPLETED,

    /** 공급이 늦어졌다 */
    SUPPLY_DELAYED,

    /** 환불이 끝났다 */
    REFUND_COMPLETED,

    /** 비밀번호 재설정. <b>부르는 코드가 아직 없다</b> — 청크 {@code 5c-1} 의 몫이다(`D18`) */
    PASSWORD_RESET,

    /** 이메일 변경 확인. <b>부르는 코드가 아직 없다</b> — 청크 {@code 5e-1} 의 몫이다(`D18`) */
    EMAIL_CHANGE,

    /** 광고. 문안은 캠페인마다 갈린다 */
    ADVERTISEMENT,

    /** 동의 처리 결과(`V45`) */
    CONSENT_RESULT,

    /** 동의 재확인 요청(`V46`) */
    CONSENT_RECONFIRM;

    /** 저장값. DB 는 소문자다 */
    String code() {
        return name().toLowerCase();
    }

    /**
     * 저장값을 사건으로 되돌린다.
     *
     * <p><b>모르는 값이면 터진다.</b> {@code notification_event_type_check} 가 이미 막고 있으므로
     * 여기 오는 모르는 값은 <b>마이그레이션과 이 enum 이 어긋났다</b>는 뜻이다.
     */
    static NotificationEventType of(String code) {
        return Arrays.stream(values())
                .filter(event -> event.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("모르는 알림 사건이다: " + code));
    }
}
