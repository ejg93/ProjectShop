package com.projectshop.shop.product;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.projectshop.shop.audit.AuditLog;
import com.projectshop.shop.audit.AuditTargetType;
import com.projectshop.shop.auth.PermissionEvaluator;
import com.projectshop.shop.auth.PermissionEvaluator.Target;
import com.projectshop.shop.auth.PermissionResource;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.notification.NotificationService;
import com.projectshop.shop.support.BusinessCalendar;

/**
 * 숨긴 사진의 재개 요구를 {@code copyright_reinstatement} 에 기록하고 판단·예정일 재개·소 제기 통보를 다룬다(`Q465`,
 * 저작권법 제103조③ · 시행령 제42·43조 `D2` R42).
 *
 * <p><b>복제·전송자 셀프 입구는 없다.</b> 법은 수령인(사람)에게 소명 자료를 내는 절차고, 관리자가 받은 요구를 기록하는 것이
 * 「받았다」의 증거다. 권한은 판정과 같은 {@code product:moderate} 다 — 같은 사람이 같은 자원을 다룬다.
 *
 * <p><b>통보는 커밋 뒤에 보낸다</b>(`Q463` 과 같은 까닭 — 트랜잭션 안에서 바깥을 안 부른다).
 */
@Service
public class CopyrightReinstatementService {

    /**
     * 통보받은 뒤 재개를 요구할 수 있는 날(저작권법 시행령 제42조, `D2` R42).
     */
    static final int REQUEST_DAYS = 30;

    /** 재개 예정일의 앞 끝 — 요구받은 날의 7일 이후(저작권법 시행령 제43조, `D2` R42) */
    static final int SCHEDULE_MIN_DAYS = 7;

    /** 재개 예정일의 뒤 끝 — 요구받은 날의 14일 이내(저작권법 시행령 제43조, `D2` R42) */
    static final int SCHEDULE_MAX_DAYS = 14;

    /** 통보 본문의 날짜 — 받는 사람이 읽는 날이라 한국 시간이다 */
    private static final DateTimeFormatter NOTICE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final Logger log = LoggerFactory.getLogger(CopyrightReinstatementService.class);

    private final JdbcClient jdbc;
    private final PermissionEvaluator evaluator;
    private final AuditLog auditLog;
    private final NotificationService notifications;
    private final CopyrightReportService reports;
    private final TransactionTemplate transactions;

    CopyrightReinstatementService(JdbcClient jdbc, PermissionEvaluator evaluator, AuditLog auditLog,
            NotificationService notifications, CopyrightReportService reports, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.evaluator = evaluator;
        this.auditLog = auditLog;
        this.notifications = notifications;
        this.reports = reports;
        this.transactions = transactions;
    }

    /**
     * 수령인이 받은 재개 요구를 기록한다. 게시를 중단한 신고이고, 사진이 아직 숨김에 있고, 통보 뒤 30일 안이어야 한다.
     *
     * @param requestedAt 수령인이 요구를 받은 시각 — 예정일 범위의 기준이다
     */
    @Transactional
    public void record(long actorUserId, long reportId, OffsetDateTime requestedAt, String claim) {
        Report report = moderated(actorUserId, reportId);
        if (!"taken_down".equals(report.decision()) || !report.hidden()) {
            throw new ShopException(ErrorCode.COPYRIGHT_NOT_TAKEN_DOWN);
        }
        // 「통보받은 날부터 30일」은 날로 센다(KST) — 시각으로 재면 30일째 오후 요구가 막힌다(마무리 85차 리뷰).
        LocalDate noticed = report.noticedAt().atZoneSameInstant(BusinessCalendar.ZONE).toLocalDate();
        LocalDate requested = requestedAt.atZoneSameInstant(BusinessCalendar.ZONE).toLocalDate();
        if (requested.isAfter(noticed.plusDays(REQUEST_DAYS))) {
            throw new ShopException(ErrorCode.COPYRIGHT_REINSTATEMENT_LATE);
        }
        int inserted = jdbc.sql("""
                        insert into copyright_reinstatement (copyright_report_id, requested_at, requester_claim)
                        values (:id, :requestedAt, :claim)
                        on conflict (copyright_report_id) do nothing
                        """)
                .param("id", reportId)
                .param("requestedAt", requestedAt)
                .param("claim", claim)
                .update();
        if (inserted == 0) {
            throw new ShopException(ErrorCode.COPYRIGHT_REINSTATEMENT_EXISTS);
        }
        auditLog.record(AuditLog.Kind.OUTCOME, "copyright.reinstatement_requested", actorUserId,
                new AuditLog.Target(AuditTargetType.COPYRIGHT_REPORT, reportId), Map.of());
    }

    /**
     * 판단한다. {@code SCHEDULED} 면 예정일을 정하고 권리주장자에게 재개 요구 사실과 예정일을 알린다(제103조③ 「지체 없이」).
     * 예정일 범위는 제약({@code copyright_reinstatement_window_check})이 들지만 위반이 500 으로 나가지 않게 여기서 먼저 잰다.
     */
    public void decide(long actorUserId, long reportId, ReinstatementDecision decision, LocalDate scheduledFor) {
        String claimedWork = transactions.execute(status -> {
            Report report = moderated(actorUserId, reportId);
            Pending pending = pending(reportId);
            if (decision == ReinstatementDecision.SCHEDULED && !withinWindow(pending.requestedAt(), scheduledFor)) {
                throw new ShopException(ErrorCode.COPYRIGHT_REINSTATEMENT_WINDOW);
            }
            jdbc.sql("""
                            update copyright_reinstatement
                               set decision = :decision, decided_at = now(), decided_by_user_id = :actor,
                                   scheduled_for = :scheduledFor
                             where copyright_report_id = :id and decision is null
                            """)
                    .param("decision", decision.code())
                    .param("actor", actorUserId)
                    .param("scheduledFor", decision == ReinstatementDecision.SCHEDULED ? scheduledFor : null)
                    .param("id", reportId)
                    .update();
            auditLog.record(AuditLog.Kind.OUTCOME, "copyright.reinstatement_decided", actorUserId,
                    new AuditLog.Target(AuditTargetType.COPYRIGHT_REPORT, reportId), Map.of("decision", decision.code()));
            return report.claimedWork();
        });
        if (decision == ReinstatementDecision.SCHEDULED) {
            notifications.sendCopyrightReinstatementScheduled(reportId,
                    Map.of("claimed_work", claimedWork, "scheduled_for", scheduledFor.format(NOTICE_DATE)));
        }
    }

    /** 권리주장자가 소를 제기했다고 알려 왔다 — 예정일 전이면 재개하지 않는다(제103조③ 단서) */
    @Transactional
    public void recordSuitNotice(long actorUserId, long reportId, OffsetDateTime notifiedAt) {
        moderated(actorUserId, reportId);
        int updated = jdbc.sql("""
                        update copyright_reinstatement set suit_notified_at = :notifiedAt
                         where copyright_report_id = :id and decision = 'scheduled'
                           and reinstated_at is null and suit_notified_at is null
                        """)
                .param("notifiedAt", notifiedAt)
                .param("id", reportId)
                .update();
        if (updated == 0) {
            throw new ShopException(ErrorCode.COPYRIGHT_REINSTATEMENT_NOT_PENDING);
        }
        auditLog.record(AuditLog.Kind.OUTCOME, "copyright.suit_notified", actorUserId,
                new AuditLog.Target(AuditTargetType.COPYRIGHT_REPORT, reportId), Map.of());
    }

    /** 그 신고의 재개 기록. 관리자 목록이 같은 칸을 바깥 조인으로 싣는다 */
    @Transactional(readOnly = true)
    public CopyrightReportQuery.Reinstatement find(long actorUserId, long reportId) {
        moderated(actorUserId, reportId);
        return jdbc.sql("""
                        select requested_at, decision, scheduled_for, suit_notified_at, reinstated_at
                          from copyright_reinstatement where copyright_report_id = :id
                        """)
                .param("id", reportId)
                .query((rs, rowNum) -> CopyrightReportQuery.Reinstatement.of(rs))
                .optional()
                .orElseThrow(() -> new ShopException(ErrorCode.COPYRIGHT_REINSTATEMENT_NOT_PENDING));
    }

    /**
     * 예정일이 찬 재개를 한다({@link CopyrightReinstatementBatch}). 사진을 공개로 되돌리고 복제·전송자에게 알린다.
     * 사진이 이미 사라졌으면(파기·셀러 삭제) 건너뛰고 경고한다.
     *
     * @return 재개한 수
     */
    int reinstateDue(LocalDate baselineDate) {
        List<Long> due = jdbc.sql("""
                        select copyright_report_id from copyright_reinstatement
                         where decision = 'scheduled' and scheduled_for <= :baseline
                           and reinstated_at is null and suit_notified_at is null
                         order by copyright_report_id
                        """)
                .param("baseline", baselineDate)
                .query(Long.class)
                .list();
        int reinstated = 0;
        for (long reportId : due) {
            Boolean done = transactions.execute(status -> reinstate(reportId));
            if (Boolean.TRUE.equals(done)) {
                reinstated++;
                notifications.sendCopyrightReinstated(reportId, reports.uploadersOf(reportId), Map.of(
                        "claimed_work", claimedWork(reportId),
                        "reinstated_at", OffsetDateTime.now(BusinessCalendar.ZONE).format(NOTICE_DATE)));
            }
        }
        return reinstated;
    }

    private boolean reinstate(long reportId) {
        Images images = jdbc.sql("""
                        select product_image_id, review_image_id from copyright_report where copyright_report_id = :id
                        """)
                .param("id", reportId)
                .query(Images.class)
                .single();
        boolean restored = images.productImageId() != null ? reports.restore(images.productImageId())
                : images.reviewImageId() != null && reports.restoreReviewImage(images.reviewImageId());
        if (!restored) {
            log.warn("재개할 사진이 없다 — 이미 지웠거나 공개다 신고={}", reportId);
            return false;
        }
        jdbc.sql("update copyright_reinstatement set reinstated_at = now() where copyright_report_id = :id")
                .param("id", reportId)
                .update();
        auditLog.record(AuditLog.Kind.OUTCOME, "copyright.reinstated", null,
                new AuditLog.Target(AuditTargetType.COPYRIGHT_REPORT, reportId), Map.of());
        return true;
    }

    /** 신고를 읽고 판정 권한을 본다. 통보일은 복제·전송자에게 처음 보낸 시각, 없으면 판정 시각이다 */
    private Report moderated(long actorUserId, long reportId) {
        Report report = jdbc.sql("""
                        select p.seller_id, r.decision, r.claimed_work,
                               coalesce(pi.hidden_at, ri.hidden_at) is not null as hidden,
                               coalesce((select min(n.sent_at) from notification n
                                          where n.copyright_report_id = r.copyright_report_id
                                            and n.event_type = 'copyright_taken_down_uploader'),
                                        r.decided_at) as noticed_at
                          from copyright_report r
                          join product p on p.product_id = r.product_id
                          left join product_image pi on pi.product_image_id = r.product_image_id
                          left join review_image ri on ri.review_image_id = r.review_image_id
                         where r.copyright_report_id = :id
                        """)
                .param("id", reportId)
                .query(Report.class)
                .optional()
                .orElseThrow(() -> new ShopException(ErrorCode.PRODUCT_NOT_FOUND));
        if (!evaluator.decide(actorUserId, PermissionResource.PRODUCT, "moderate", Target.ofSeller(report.sellerId()))
                .allowed()) {
            throw new ShopException(ErrorCode.PRODUCT_FORBIDDEN);
        }
        return report;
    }

    private Pending pending(long reportId) {
        return jdbc.sql("""
                        select requested_at from copyright_reinstatement
                         where copyright_report_id = :id and decision is null
                        """)
                .param("id", reportId)
                .query(Pending.class)
                .optional()
                .orElseThrow(() -> new ShopException(ErrorCode.COPYRIGHT_REINSTATEMENT_NOT_PENDING));
    }

    private String claimedWork(long reportId) {
        return jdbc.sql("select claimed_work from copyright_report where copyright_report_id = :id")
                .param("id", reportId)
                .query(String.class)
                .single();
    }

    private static boolean withinWindow(OffsetDateTime requestedAt, LocalDate scheduledFor) {
        if (scheduledFor == null) {
            return false;
        }
        LocalDate requested = requestedAt.atZoneSameInstant(BusinessCalendar.ZONE).toLocalDate();
        return !scheduledFor.isBefore(requested.plusDays(SCHEDULE_MIN_DAYS))
                && !scheduledFor.isAfter(requested.plusDays(SCHEDULE_MAX_DAYS));
    }

    private record Report(long sellerId, String decision, String claimedWork, boolean hidden, OffsetDateTime noticedAt) {
    }

    private record Pending(OffsetDateTime requestedAt) {
    }

    private record Images(Long productImageId, Long reviewImageId) {
    }
}
