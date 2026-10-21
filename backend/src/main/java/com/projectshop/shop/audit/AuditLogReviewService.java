package com.projectshop.shop.audit;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectshop.shop.support.BusinessCalendar;

/**
 * 한 기간의 개인정보 열람 기록을 열람자별로 세어, 서로 다른 정보주체를 임계 이상 연 사람을 표시한다(`Q280b`).
 *
 * <p>안전성 확보조치 기준 제8조② 의 접속기록 점검이다(`D2` R44) — 고시 제2025-9호 부칙 제1조가 미룬 날(2026-10-30)까지는
 * 「월 1회 이상 점검」이 법정이고, 그 뒤는 주기·방법·사후조치를 내부 관리계획으로 정한다. 그 계획은
 * {@code security-baseline.md} 「접속기록 점검 — 내부 관리계획」이 든다.
 *
 * <p><b>대행 열람은 시킨 관리자로 센다</b> — 요청의 주인은 그 사용자지만 실제로 본 사람은 관리자다({@code V101}).
 *
 * <p><b>목록 열람은 정보주체를 안 센다</b> — 쪽마다 한 줄이고 대상이 비어 있어서({@link AuditLog#personalDataListed})
 * 건수({@code row_count})로만 든다. 목록만 여는 열람자는 임계에 안 걸리고, 점검 행의 건수로 사람이 본다.
 */
@Service
class AuditLogReviewService {

    /** 점검 결과의 사건 이름. 점검을 했다는 증거라 표시가 0이어도 남는다 */
    static final String REVIEWED = "audit_log.reviewed";

    /** 한 기간에 서로 다른 정보주체를 이만큼 연 열람자를 표시한다. 내부 관리계획 값(`security-baseline.md` 「접속기록 점검」) — 법이 정한 수가 아니다 */
    static final int REVIEW_SUBJECT_THRESHOLD = 50;

    private static final Logger log = LoggerFactory.getLogger(AuditLogReviewService.class);

    private final JdbcClient jdbc;
    private final AuditLog auditLog;

    AuditLogReviewService(JdbcClient jdbc, AuditLog auditLog) {
        this.jdbc = jdbc;
        this.auditLog = auditLog;
    }

    /** 열람자 한 사람의 그 기간 집계 */
    record ViewerCount(long viewerUserId, int rowCount, int subjectCount) {
    }

    /**
     * 기간 안의 열람 기록을 세고 점검 행 하나를 남긴다.
     *
     * @param periodStart        첫날. 그날 00:00 KST 부터 센다
     * @param periodEndExclusive 끝 다음 날. 그날 00:00 KST 앞까지 센다
     * @return 임계 이상이라 표시된 열람자. 정보주체가 많은 순이다
     */
    @Transactional
    List<ViewerCount> review(LocalDate periodStart, LocalDate periodEndExclusive) {
        OffsetDateTime from = periodStart.atStartOfDay(BusinessCalendar.ZONE).toOffsetDateTime();
        OffsetDateTime to = periodEndExclusive.atStartOfDay(BusinessCalendar.ZONE).toOffsetDateTime();

        List<ViewerCount> viewers = jdbc.sql("""
                select coalesce(impersonator_user_id, actor_user_id) as viewer_user_id,
                       count(*) as row_count,
                       count(distinct target_id) filter (where target_type = 'user') as subject_count
                  from audit_log
                 where event_type = :eventType
                   and created_at >= :from
                   and created_at < :to
                 group by coalesce(impersonator_user_id, actor_user_id)
                 order by subject_count desc, viewer_user_id
                """)
                .param("eventType", AuditLog.PERSONAL_DATA_VIEWED)
                .param("from", from)
                .param("to", to)
                .query((rs, rowNum) -> new ViewerCount(
                        rs.getLong("viewer_user_id"), rs.getInt("row_count"), rs.getInt("subject_count")))
                .list();

        List<ViewerCount> flagged = viewers.stream()
                .filter(viewer -> viewer.subjectCount() >= REVIEW_SUBJECT_THRESHOLD)
                .toList();

        for (ViewerCount viewer : flagged) {
            log.warn("접속기록 점검 — 임계 이상 열람자 viewerUserId={} 정보주체={}명 기록={}건 기간={}~{}",
                    viewer.viewerUserId(), viewer.subjectCount(), viewer.rowCount(), periodStart, periodEndExclusive);
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("period_start", periodStart.toString());
        detail.put("period_end_exclusive", periodEndExclusive.toString());
        detail.put("threshold", REVIEW_SUBJECT_THRESHOLD);
        detail.put("viewer_count", viewers.size());
        detail.put("flagged", flagged.stream()
                .map(viewer -> Map.of(
                        "viewer_user_id", viewer.viewerUserId(),
                        "subject_count", viewer.subjectCount(),
                        "row_count", viewer.rowCount()))
                .toList());
        auditLog.record(AuditLog.Kind.OUTCOME, REVIEWED, null, AuditLog.Target.none(), detail);

        return flagged;
    }
}
