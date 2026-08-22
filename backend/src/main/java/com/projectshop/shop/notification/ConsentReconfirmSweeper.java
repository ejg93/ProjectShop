package com.projectshop.shop.notification;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 광고 수신동의를 2년마다 확인한다(청크 55b, 정보통신망법 제50조제8항·시행령 제62조의3).
 *
 * <p><b>확인은 반복되는 일이다.</b> 「이 동의 행에 통지가 나갔나」로는 두 번째 주기를 못 잰다 —
 * 나갔다는 사실은 한 번만 참이 되어서다. 그래서 {@code user_consent.reconfirmed_at} 에
 * 마지막 확인 시각을 남기고 거기서 다음 2년을 센다.
 *
 * <p><b>확인을 안 한 동의는 광고 관문이 거부한다</b>({@link AdvertisingGate}). 배치가 못 돌아도
 * 안 나가는 쪽으로 기울게 한 것이다 — 반대로 두면 배치가 멈춘 동안 확인 없는 동의로 광고가 나가고,
 * 그때 걸리는 것은 테스트가 아니라 과태료다.
 *
 * <p><b>하루 한 번이다.</b> 2년짜리 주기라 5분마다 훑을 이유가 없고, 하루가 늦어도 기한 안이다
 * ({@code RefundSweeper} 가 04:00 규칙에서 빠진 것과 반대 방향의 판단).
 */
@Component
public class ConsentReconfirmSweeper {

    /** 시행령 제62조의3 — 수신동의를 받은 날부터 2년마다 */
    private static final int RECONFIRM_YEARS = 2;

    /** 업무 판단은 KST 다(`D10`) */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static final Logger log = LoggerFactory.getLogger(ConsentReconfirmSweeper.class);

    private final JdbcClient jdbc;
    private final NotificationService notifications;

    ConsentReconfirmSweeper(JdbcClient jdbc, NotificationService notifications) {
        this.jdbc = jdbc;
        this.notifications = notifications;
    }

    /**
     * 04:00 규칙을 따른다(`D10`). 기준 시각을 <b>전날 24시로 고정</b>해서
     * 몇 시에 몇 번 돌든 대상이 같게 한다 — 재실행이 안전해야 한다.
     */
    @Scheduled(cron = "0 30 4 * * *", zone = "Asia/Seoul")
    public void sweep() {
        int sent = sweep(LocalDate.now(KST).atStartOfDay(KST).toOffsetDateTime());
        if (sent == 0) {
            log.debug("수신동의 확인 — 대상 없음");
        } else {
            log.info("수신동의 확인 끝 발송={}건", sent);
        }
    }

    /**
     * 확인이 밀린 동의에 확인 통지를 보낸다.
     *
     * @param baseline 이 시각에서 2년을 뺀 것보다 오래된 동의가 대상이다
     * @return 실제로 보낸 수
     */
    public int sweep(OffsetDateTime baseline) {
        OffsetDateTime due = baseline.minusYears(RECONFIRM_YEARS);
        int sent = 0;

        for (Overdue target : findOverdue(due)) {
            if (notifications.send("consent_reconfirm",
                    NotificationService.Target.consent(target.userConsentId()),
                    target.userId(),
                    Map.of("item_title", target.itemTitle(), "acted_at", target.actedOn()))
                    .isPresent()) {
                markReconfirmed(target.userConsentId(), baseline);
                sent++;
            }
        }
        return sent;
    }

    /**
     * 확인 기한이 지난 동의.
     *
     * @param userConsentId 그 동의 행
     * @param userId        받는 사람
     * @param itemTitle     무엇에 동의했나
     * @param actedOn       언제 동의했나. 사용자가 읽는 글이라 KST 날짜다
     */
    private record Overdue(long userConsentId, long userId, String itemTitle, String actedOn) {}

    /**
     * <b>지금 켜져 있는 동의만 고른다.</b> 철회한 행에 확인을 보내면
     * 「동의하셨습니다」가 거짓이 되고, 그것 자체가 광고 재유치로 읽힌다.
     */
    private List<Overdue> findOverdue(OffsetDateTime due) {
        return jdbc.sql("""
                        select uc.user_consent_id, uc.user_id, ci.title as item_title,
                               to_char(uc.acted_at at time zone 'Asia/Seoul',
                                       'YYYY년 FMMM월 FMDD일') as acted_on
                          from user_consent uc
                          join consent_item ci on ci.consent_item_id = uc.consent_item_id
                         where ci.code in ('marketing_email', 'marketing_sms', 'marketing_night')
                           and uc.granted
                           and coalesce(uc.reconfirmed_at, uc.acted_at) < :due
                           and uc.user_consent_id = (
                               select max(latest.user_consent_id) from user_consent latest
                                where latest.user_id = uc.user_id
                                  and latest.consent_item_id = uc.consent_item_id
                           )
                        """)
                .param("due", due)
                .query(Overdue.class)
                .list();
    }

    /**
     * 확인한 시각을 남긴다. <b>보낸 뒤에 남긴다</b> — 먼저 남기면 발송이 실패했을 때
     * 다음 확인이 2년 뒤가 되고, 그 사이는 확인 없이 광고가 나가는 구간이다.
     */
    private void markReconfirmed(long userConsentId, OffsetDateTime at) {
        jdbc.sql("""
                        update user_consent set reconfirmed_at = :at
                         where user_consent_id = :id
                        """)
                .param("id", userConsentId)
                .param("at", at)
                .update();
    }

}
