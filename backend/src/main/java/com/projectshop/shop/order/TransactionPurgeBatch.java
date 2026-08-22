package com.projectshop.shop.order;

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
 * 거래기록 파기를 매월 1일 04:00 KST 에 돌리고 회차를 {@code batch_run} 에 남긴다.
 *
 * <p><b>주기가 개인정보 파기와 다르다.</b> 그쪽은 매일이고 이쪽은 매월인데,
 * 보존 기간이 5년·3년·6개월이라 하루 단위로 훑을 이유가 없다(`D13`).
 * 1일로 잡은 것은 날짜가 고정이라 사람이 기억하기 때문이다(`D19`).
 *
 * <p>서비스에 {@code @Scheduled} 를 안 붙인 이유는 {@code AccountPurgeBatch} 와 같다 —
 * 트랜잭션을 공유하면 실패한 회차가 이력에 안 남는다.
 */
@Component
public class TransactionPurgeBatch implements RetryableBatch {

    private static final Logger log = LoggerFactory.getLogger(TransactionPurgeBatch.class);

    /** 카탈로그(`D19`)가 부르는 이름. {@code batch_run.batch_name} 에 그대로 들어간다 */
    static final String BATCH_NAME = "transaction_purge";

    /** 업무 판단은 KST 다(`D10`) */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final TransactionPurgeService purgeService;
    private final BatchRuns runs;

    TransactionPurgeBatch(TransactionPurgeService purgeService, BatchRuns runs) {
        this.purgeService = purgeService;
        this.runs = runs;
    }

    /** 매월 1일 04:00 KST. 월초 정산 마감(청크 19)과 시각이 겹쳐도 서로 입력을 안 준다(`D19`) */
    @Scheduled(cron = "0 0 4 1 * *", zone = "Asia/Seoul")
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
            TransactionPurgeService.Purged purged = purgeService.purge(baseline);
            int processed = purged.shippingAddresses() + purged.paymentCards()
                    + purged.orders() + purged.auditLogs() + purged.batchRuns()
                    + purged.notificationBodies() + purged.notifications();

            if (processed == 0) {
                log.debug("거래기록 파기 배치 — 대상 없음 기준일={}", baselineDate);
            } else {
                log.info("거래기록 파기 배치 끝 기준일={} 배송지={} 카드={} 주문={} 감사로그={} 배치이력={} 알림본문={} 알림={}",
                        baselineDate, purged.shippingAddresses(), purged.paymentCards(),
                        purged.orders(), purged.auditLogs(), purged.batchRuns(),
                        purged.notificationBodies(), purged.notifications());
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
