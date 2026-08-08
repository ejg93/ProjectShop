package com.projectshop.shop.me;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectshop.shop.audit.AuditLog;

/**
 * 탈퇴 계정의 개인정보를 파기한다(`D2` R9).
 *
 * <p><b>탈퇴(5g)가 파기 대상을 만들고 이 서비스가 그것을 지운다.</b>
 * 둘 사이의 간격이 유예 기간이고, 그동안은 개인정보를 들고 있는 상태다.
 *
 * <p>행을 지우지 않는다. 주문이 {@code user_id} 로 계정을 가리키고 주문은 5년 남는다(`D13`).
 * 지우는 것은 행 안의 개인정보뿐이라, 파기 뒤에도 "이 주문을 누가 했나" 는 id 로 이어진다.
 *
 * <p><b>두 번 돌아도 결과가 같다.</b> 조건에 {@code email is not null} 과
 * {@code acted_ip is not null} 이 들어가 있어서 이미 비운 행은 대상에서 빠진다.
 * 배치가 두 번 도는 것은 사고가 아니라 정상이다 — 재시도와 수동 실행이 겹친다.
 */
@Service
public class AccountPurgeService {

    /** 업무 판단은 KST 다. 저장은 UTC 지만 "며칠 지났나" 는 한국 날짜로 센다(`D10`). */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /**
     * 탈퇴 후 파기까지의 유예. <b>법이 정한 값이 아니라 우리가 정한 값이다</b>(`D13`).
     * 짧으면 실수로 탈퇴한 사람이 복구를 못 하고, 길면 파기를 미루는 셈이 된다.
     */
    private static final int GRACE_DAYS = 30;

    /**
     * 동의 이력 보존. 감사 로그와 같은 3년이다(`D13`).
     * 분쟁이 탈퇴 뒤에 와도 "동의받고 수집했다" 에 답할 수 있어야 한다.
     * 다만 {@code acted_ip} 는 입증에 거의 안 쓰이면서 식별성이 높아 유예 30일에 먼저 버린다.
     */
    private static final int CONSENT_RETENTION_YEARS = 3;

    private final JdbcClient jdbc;
    private final AuditLog auditLog;

    AccountPurgeService(JdbcClient jdbc, AuditLog auditLog) {
        this.jdbc = jdbc;
        this.auditLog = auditLog;
    }

    /**
     * 파기한 것의 수. 배치가 무엇을 했는지 로그와 테스트가 같은 값으로 본다.
     *
     * @param accounts    이메일·이름·비밀번호 해시를 비운 계정 수
     * @param consentIps  동의 이력에서 IP 를 비운 행 수
     * @param consentRows 보존 기간이 지나 지운 동의 이력 행 수
     */
    public record Purged(int accounts, int consentIps, int consentRows) {}

    /**
     * 오늘 기준으로 파기한다. 스케줄러(청크 36)가 생기기 전까지는 손으로 부른다.
     *
     * <p>기준 시각을 <b>전날 24시로 고정</b>한다(`D10`). 몇 시에 몇 번 돌든 대상이 같아야
     * 재실행이 안전하다. {@code now()} 를 쓰면 같은 날 두 번 돌 때 대상이 달라진다.
     */
    @Transactional
    public Purged purge() {
        return purge(LocalDate.now(KST).atStartOfDay(KST).toOffsetDateTime());
    }

    /**
     * 기준 시각을 받아 파기한다. 테스트가 시간을 통제하려고 쓴다.
     *
     * @param baseline 이 시각에서 기간을 뺀 것보다 먼저 탈퇴한 계정이 대상이다
     */
    @Transactional
    public Purged purge(OffsetDateTime baseline) {
        OffsetDateTime graceEnd = baseline.minusDays(GRACE_DAYS);
        OffsetDateTime consentEnd = baseline.minusYears(CONSENT_RETENTION_YEARS);

        int consentRows = deleteExpiredConsents(consentEnd);
        int consentIps = clearConsentIps(graceEnd);
        List<Long> purgedIds = clearAccountFields(graceEnd);

        // 계정마다 한 행씩 남긴다. 합계 하나만 남기면 "이 계정이 언제 파기됐나" 에 못 답하는데,
        // 파기 사실의 증거가 이 로그뿐이라 그 질문이 실제로 온다.
        //
        // actor 가 null 인 것은 사람이 한 일이 아니라서다. 배치는 주체가 없다.
        purgedIds.forEach(userId -> auditLog.record(
                "user.purged", null, AuditLog.Target.of("user", userId),
                Map.of("grace_days", GRACE_DAYS)));

        return new Purged(purgedIds.size(), consentIps, consentRows);
    }

    /**
     * 계정에서 개인정보를 비운다. {@code deleted_at} 은 그대로 둔다 — 수명은 파기와 다른 축이고,
     * 지우면 "탈퇴한 계정" 이라는 사실까지 사라진다.
     */
    private List<Long> clearAccountFields(OffsetDateTime graceEnd) {
        return jdbc.sql("""
                        update app_user
                           set email = null, display_name = null, password_hash = null
                         where deleted_at is not null
                           and deleted_at < :graceEnd
                           and email is not null
                        returning user_id
                        """)
                .param("graceEnd", graceEnd)
                .query(Long.class)
                .list();
    }

    /**
     * 동의 이력에서 IP 만 비운다. 행은 남는다 — 무엇에 언제 동의했는지가 입증 대상이고,
     * IP 는 거기에 안 쓰인다.
     */
    private int clearConsentIps(OffsetDateTime graceEnd) {
        return jdbc.sql("""
                        update user_consent uc
                           set acted_ip = null
                          from app_user u
                         where uc.user_id = u.user_id
                           and u.deleted_at is not null
                           and u.deleted_at < :graceEnd
                           and uc.acted_ip is not null
                        """)
                .param("graceEnd", graceEnd)
                .update();
    }

    /**
     * 보존 기간이 지난 동의 이력을 지운다.
     *
     * <p>{@code V11} 이 이 관계에 {@code cascade} 를 걸어 뒀지만 <b>그것으로는 안 지워진다</b> —
     * 탈퇴는 {@code deleted_at} 을 채우는 update 고 cascade 는 delete 에만 걸린다.
     * 계정 행을 안 지우기로 한 이상 지우는 자리는 여기뿐이다.
     */
    private int deleteExpiredConsents(OffsetDateTime consentEnd) {
        return jdbc.sql("""
                        delete from user_consent uc
                         using app_user u
                         where uc.user_id = u.user_id
                           and u.deleted_at is not null
                           and u.deleted_at < :consentEnd
                        """)
                .param("consentEnd", consentEnd)
                .update();
    }
}
