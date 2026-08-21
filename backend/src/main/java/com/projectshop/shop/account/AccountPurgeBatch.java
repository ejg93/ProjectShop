package com.projectshop.shop.account;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.projectshop.shop.support.BatchRuns;
import com.projectshop.shop.support.RetryableBatch;

/**
 * 개인정보 파기를 매일 04:00 KST 에 돌리고 회차를 {@code batch_run} 에 남긴다.
 *
 * <p><b>파기 규칙은 여기 없다.</b> 무엇을 언제 지우는지는 {@link AccountPurgeService} 가 정하고
 * 이 클래스는 언제 부르나만 안다(`D23` 「배치는 규칙을 갖지 않는다」).
 *
 * <p><b>서비스에 {@code @Scheduled} 를 안 붙였다.</b> 그쪽은 {@code @Transactional} 이라
 * 회차 기록이 파기와 한 트랜잭션이 되고, 그러면 <b>실패한 회차가 롤백돼서 이력에 안 남는다</b>.
 * 실패를 남기는 것이 이력의 쓸모 절반이다(`D19`).
 */
@Component
public class AccountPurgeBatch implements RetryableBatch {

    private static final Logger log = LoggerFactory.getLogger(AccountPurgeBatch.class);

    /** 카탈로그(`D19`)가 부르는 이름. {@code batch_run.batch_name} 에 그대로 들어간다 */
    static final String BATCH_NAME = "account_purge";

    /** 업무 판단은 KST 다(`D10`) */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final AccountPurgeService purgeService;
    private final BatchRuns runs;

    AccountPurgeBatch(AccountPurgeService purgeService, BatchRuns runs) {
        this.purgeService = purgeService;
        this.runs = runs;
    }

    /**
     * 매일 04:00 KST 에 돈다. 04:00 은 하루 중 사람이 가장 적은 시각이다(`D10`).
     *
     * <p>재시도로 04:30 에 돌아도 같은 기준일이라 대상이 같다 — 기준 시각이
     * 그날 00:00 KST 로 고정돼 있어서다(`AccountPurgeService`).
     */
    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    public void purge() {
        purge(LocalDate.now(KST));
    }

    /**
     * 기준일을 받아 그날 회차를 돌린다. 테스트가 날짜를 통제하려고 쓴다.
     *
     * @param baselineDate 이 날의 00:00 KST 가 기준 시각이 된다
     * @return 실제로 돈 회차의 처리 수. 이미 성공했거나 실패했으면 빈 값
     */
    public Optional<BatchRuns.Counts> purge(LocalDate baselineDate) {
        OffsetDateTime baseline = baselineDate.atStartOfDay(KST).toOffsetDateTime();

        return runs.record(BATCH_NAME, baselineDate, () -> {
            AccountPurgeService.Purged purged = purgeService.purge(baseline);
            int processed = purged.accounts() + purged.consentIps() + purged.consentRows()
                    + purged.guestCarts() + purged.idempotencyKeys();

            if (processed == 0) {
                // 지울 것이 없는 날이 대부분이다. INFO 로 남기면 진짜 파기가 그 사이에 묻힌다(`D16`).
                log.debug("개인정보 파기 배치 — 대상 없음 기준일={}", baselineDate);
            } else {
                log.info("개인정보 파기 배치 끝 기준일={} 계정={} 동의IP={} 동의이력={} 장바구니={} 멱등키={}",
                        baselineDate, purged.accounts(), purged.consentIps(), purged.consentRows(),
                        purged.guestCarts(), purged.idempotencyKeys());
            }
            return BatchRuns.Counts.of(processed);
        });
    }

    @Override
    public String batchName() {
        return BATCH_NAME;
    }

    /** 재시도가 부르는 자리(`36a`). 이미 성공한 회차면 `purge` 안에서 걸러진다 */
    @Override
    public void runFor(LocalDate baselineDate) {
        purge(baselineDate);
    }
}
