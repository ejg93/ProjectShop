package com.projectshop.shop.notification;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.projectshop.shop.consent.ConsentService;

/**
 * 광고성 정보를 보내도 되는지 판정한다(청크 55, 정보통신망법 제50조).
 *
 * <p><b>동의 조회와 야간 판정을 한 자리에 모은다.</b> 두 곳에서 각자 보면 한쪽이 빠지고,
 * 빠진 쪽은 <b>안 걸리는 것이 아니라 과태료로 걸린다</b>(제50조 위반).
 *
 * <p><b>거래 통지는 이 클래스를 안 지난다.</b> 그것이 `D18` 이 요구한 강제 지점이다 —
 * 거래 통지가 동의를 보기 시작하면 <b>안 보낸 것이 우리 위반</b>이 되고, 사용자가 껐다는
 * 사실이 그 위반을 안 덮는다. 분기로 가르지 않고 <b>부를 자리를 아예 안 만드는 것</b>으로 막는다
 * ({@code NotificationService} 는 이 클래스를 안 든다).
 */
@Component
public class AdvertisingGate {

    /** 업무 판단은 KST 다(`D10`) */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /**
     * 야간이 시작되는 시각. 제50조제3항이 「오후 9시부터 그 다음 날 오전 8시까지」로 정했다.
     *
     * <p>경계는 <b>시작을 포함하고 끝을 뺀다</b> — 21:00 정각은 야간이고 08:00 정각은 아니다.
     */
    private static final LocalTime NIGHT_FROM = LocalTime.of(21, 0);

    /** 야간이 끝나는 시각. 제50조제3항 */
    private static final LocalTime NIGHT_UNTIL = LocalTime.of(8, 0);

    /** 광고성 정보 수신 동의(`V11`). 제50조제1항의 「명시적인 사전 동의」다 */
    private static final String MARKETING = "marketing_email";

    /** 야간 수신의 별도 동의(`V11`). 제50조제3항이 제1항과 <b>따로</b> 받으라고 한 것이다 */
    private static final String MARKETING_NIGHT = "marketing_night";

    /**
     * 시행령 제62조의3 — 수신동의를 받은 날부터 2년마다 확인.
     *
     * <p><b>{@link ConsentReconfirmSweeper} 가 이 값을 같이 쓴다.</b> 두 곳에 적으면
     * 시행령이 바뀔 때 한쪽만 고쳐지고, 그러면 <b>배치는 보냈는데 관문은 아직 밀렸다고 보는</b>
     * 구간이 열린다 — 그 어긋남은 광고가 안 나가는 것으로만 드러나서 원인을 못 찾는다.
     */
    static final int RECONFIRM_YEARS = 2;

    private final ConsentService consents;
    private final JdbcClient jdbc;

    AdvertisingGate(ConsentService consents, JdbcClient jdbc) {
        this.consents = consents;
        this.jdbc = jdbc;
    }

    /** 보내도 되나. 안 되면 왜 안 되는지까지 답한다 */
    public enum Verdict {

        /** 보내도 된다 */
        ALLOWED,

        /** 광고 수신 동의가 없다. 제50조제1항 */
        NO_CONSENT,

        /** 야간인데 야간 동의가 없다. 제50조제3항 */
        NO_NIGHT_CONSENT,

        /** 동의를 받은 지 2년이 넘었는데 확인을 안 했다. 제50조제8항 */
        NOT_RECONFIRMED;

        public boolean allowed() {
            return this == ALLOWED;
        }
    }

    /**
     * 보내도 되는지 본다.
     *
     * <p><b>보내기 직전에 묻는다.</b> 미리 골라 둔 목록으로 보내면 그 사이에 철회한 사람에게
     * 나간다 — 제50조제2항은 철회 표시가 있으면 <b>전송하여서는 아니 된다</b>고 한다.
     *
     * @param userId 받을 사람
     * @param at     보내려는 시각. 야간인지를 KST 로 센다
     */
    public Verdict check(long userId, OffsetDateTime at) {
        if (!consents.isGranted(userId, MARKETING)) {
            return Verdict.NO_CONSENT;
        }
        if (!isReconfirmed(userId, MARKETING, at)) {
            return Verdict.NOT_RECONFIRMED;
        }

        if (isNight(at)) {
            if (!consents.isGranted(userId, MARKETING_NIGHT)) {
                return Verdict.NO_NIGHT_CONSENT;
            }
            // **야간 동의도 2년마다 확인해야 한다.** 제50조제8항은 항목을 안 가른다 2014
            // 여기서 안 보면 확인 안 된 야간 동의로 야간에 광고가 나간다.
            if (!isReconfirmed(userId, MARKETING_NIGHT, at)) {
                return Verdict.NOT_RECONFIRMED;
            }
        }
        return Verdict.ALLOWED;
    }

    /**
     * 확인이 밀리지 않았나. 시행령 제62조의3 이 동의받은 날부터 2년마다 확인하게 한다.
     *
     * <p><b>여기서 막는 것이 강제 지점이다.</b> 확인 배치가 못 돌면 확인 없는 동의가 쌓이는데,
     * 관문이 안 보면 그 동안 광고가 그대로 나간다 — 그때 걸리는 것은 테스트가 아니라 과태료다.
     * 배치가 멈추면 <b>안 나가는 쪽</b>으로 기울게 둔다.
     */
    private boolean isReconfirmed(long userId, String code, OffsetDateTime at) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        select coalesce(uc.reconfirmed_at, uc.acted_at) >= :due
                          from user_consent uc
                          join consent_item ci on ci.consent_item_id = uc.consent_item_id
                         where uc.user_id = :userId and ci.code = :code
                         order by uc.acted_at desc, uc.user_consent_id desc
                         limit 1
                        """)
                .param("userId", userId)
                .param("code", code)
                .param("due", at.minusYears(RECONFIRM_YEARS))
                .query(Boolean.class)
                .optional()
                .orElse(false));
    }

    /** 21시부터 다음 날 08시 전까지. 자정을 넘어가므로 두 구간의 합집합이다 */
    private boolean isNight(OffsetDateTime at) {
        LocalTime local = at.atZoneSameInstant(KST).toLocalTime();
        return !local.isBefore(NIGHT_FROM) || local.isBefore(NIGHT_UNTIL);
    }
}
