package com.projectshop.shop.notification;

import java.util.Arrays;

/**
 * 보내려던 것이 어떻게 됐나. 저장값은 {@code notification.status} 이고
 * 목록은 {@code notification_status_check} 다(`V43`).
 *
 * <p><b>{@link #PENDING} 으로 먼저 남기고 결과를 덮어쓴다.</b> 보낸 뒤에 남기면
 * 그 사이에 죽었을 때 <b>나갔는데 기록이 없는</b> 발송이 생기고, 분쟁에서 「보냈다」를 못 댄다(`D18`).
 *
 * <p>값마다 같이 채워야 하는 칸이 다르다 — {@link #SUCCEEDED} 에는 보낸 시각이,
 * {@link #FAILED} 에는 실패 종류가 있어야 한다. 그 짝은 `V43` 의 {@code check} 가 든다.
 *
 * <p>열거형으로 두는 근거는 `D23` 「가르는 물음」이다 — <b>값이 하나 늘면 코드를 고쳐야 한다.</b>
 * 상태가 늘면 <b>어느 칸을 같이 채우나</b>와 <b>재시도가 그것을 집나</b>가 같이 정해져야 한다.
 */
enum NotificationStatus {

    /** 남겼고 아직 결과를 모른다. 보낸 시각도 실패 이유도 비어 있다 */
    PENDING,

    /** 나갔다. <b>보낸 시각이 있어야 한다</b> */
    SUCCEEDED,

    /** 못 나갔다. <b>실패 종류가 있어야 한다</b> — 주소는 안 적는다(`D18` 「개인정보」) */
    FAILED;

    /** 저장값. DB 는 소문자다(`D5` 「형식」) */
    String code() {
        return name().toLowerCase();
    }

    /**
     * 저장값을 상태로 되돌린다.
     *
     * <p><b>모르는 값이면 터진다.</b> {@code check} 가 이미 막고 있으므로 여기 오는 모르는 값은
     * <b>마이그레이션과 이 enum 이 어긋났다</b>는 뜻이다.
     */
    static NotificationStatus of(String code) {
        return Arrays.stream(values())
                .filter(status -> status.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("모르는 발송 상태다: " + code));
    }
}
