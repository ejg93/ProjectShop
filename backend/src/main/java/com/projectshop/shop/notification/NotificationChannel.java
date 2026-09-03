package com.projectshop.shop.notification;

import java.util.Arrays;

/**
 * 무엇으로 보내나. 저장값은 {@code notification.channel} 이고
 * 목록은 {@code notification_channel_check} 다(`V43`).
 *
 * <p><b>지금 값이 하나뿐인데도 열거형이다.</b> `D23` 「가르는 물음」이 묻는 것은
 * 값이 몇 개냐가 아니라 <b>값이 하나 늘 때 코드를 안 고쳐도 그게 동작하나</b>인데,
 * 채널이 늘면 <b>보내는 코드도 주소를 찾는 코드도 갈린다</b> — 표에 행만 넣으면
 * 그 채널로는 아무것도 안 나간다.
 *
 * <p>그전에는 발송 SQL 안에 {@code 'email'} 이 박혀 있었다. 문자가 붙는 날
 * <b>고칠 자리를 쿼리 본문에서 찾아야 했다.</b>
 */
enum NotificationChannel {

    /** 메일. 주소는 계정에서 온다 — 발송 이력에 주소를 복사하지 않는다(`D18` 「개인정보」) */
    EMAIL;

    /** 저장값. DB 는 소문자다(`D5` 「형식」) */
    String code() {
        return name().toLowerCase();
    }

    /**
     * 저장값을 채널로 되돌린다.
     *
     * <p><b>모르는 값이면 터진다.</b> {@code check} 가 이미 막고 있으므로 여기 오는 모르는 값은
     * <b>마이그레이션과 이 enum 이 어긋났다</b>는 뜻이다.
     */
    static NotificationChannel of(String code) {
        return Arrays.stream(values())
                .filter(channel -> channel.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("모르는 발송 채널이다: " + code));
    }
}
