package com.projectshop.shop.review;

import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectshop.shop.audit.AuditLog;
import com.projectshop.shop.auth.PermissionEvaluator;
import com.projectshop.shop.auth.PermissionEvaluator.Target;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;

/**
 * 후기에 답하고, 신고하고, 신고를 처리하고, 내린 것을 되살린다(`Q167`).
 *
 * <p><b>`48` 이 표와 권한을 세우고 입구를 안 냈다.</b> 답글·신고·내림을 부르는 코드가 시험뿐이었고,
 * 되살리는 길은 <b>아무 제약도 감사도 없는 맨 UPDATE</b> 였다 — 공개한 운영정책은 「판단이 뒤집히면
 * 다시 게시합니다」라고 약속하는데(`D2` `R27`) 누가 왜 되살렸는지에 답할 자리가 없었다.
 *
 * <h2>되살림은 신고가 아니라 후기 쪽 동작이다</h2>
 *
 * <p>처리한 신고는 다시 못 연다({@code check_review_report_transition}). 신고를 되돌리면
 * 처음 판단이 기록에서 사라진다 — 그래서 <b>신고는 그대로 두고 후기를 되살리며</b>, 그 자리에서
 * 이전 사유와 되살린 이유를 감사에 남긴다. 두 판단이 둘 다 남는다.
 *
 * <h2>셀러는 답만 한다</h2>
 *
 * <p>내리는 것은 관리자만이다({@code review:moderate}, `V85`). 셀러에게 열면 불리한 후기를 내리는 자리가
 * 같이 생긴다 — 공개한 운영정책도 「셀러는 후기를 삭제할 수 없습니다」라고 적었다.
 */
@Service
public class ReviewModerationService {

    private static final String RESOURCE = "review";

    private final JdbcClient jdbc;
    private final PermissionEvaluator evaluator;
    private final AuditLog auditLog;

    ReviewModerationService(JdbcClient jdbc, PermissionEvaluator evaluator, AuditLog auditLog) {
        this.jdbc = jdbc;
        this.evaluator = evaluator;
        this.auditLog = auditLog;
    }

    /**
     * 답글을 달거나 고친다. <b>후기 하나에 답글 하나다</b>({@code review_reply.review_id} 유니크) —
     * 지운 답글도 행이 남으므로 다시 달면 그 행을 되살린다.
     *
     * <p><b>자기 상품만이다.</b> 판정의 {@code seller} 스코프가 먼저 거르고, 그것을 안 지나는 길은
     * {@code check_review_reply_seller} 트리거가 막는다(`V85`).
     */
    @Transactional
    public void reply(long actorUserId, long reviewId, String body) {
        Subject review = visibleSubjectOf(reviewId);
        require(actorUserId, "reply", Target.ofSeller(review.sellerId()));

        jdbc.sql("""
                        insert into review_reply (review_id, user_id, body)
                        values (:review, :user, :body)
                        on conflict (review_id) do update
                           set user_id = excluded.user_id, body = excluded.body, deleted_at = null
                        """)
                .param("review", reviewId)
                .param("user", actorUserId)
                .param("body", body)
                .update();

        auditLog.record(AuditLog.Kind.OUTCOME, "review.replied", actorUserId,
                AuditLog.Target.of("review", reviewId), Map.of("seller_id", review.sellerId()));
    }

    /** 답글을 지운다. <b>행은 남는다</b> — 무엇을 말했다가 거뒀는지가 기록이다 */
    @Transactional
    public void deleteReply(long actorUserId, long reviewId) {
        Subject review = visibleSubjectOf(reviewId);
        require(actorUserId, "reply", Target.ofSeller(review.sellerId()));

        int updated = jdbc.sql("""
                        update review_reply set deleted_at = now()
                         where review_id = :review and deleted_at is null
                        """)
                .param("review", reviewId)
                .update();
        if (updated == 0) {
            throw new ShopException(ErrorCode.REVIEW_NOT_FOUND, "지울 답글이 없다");
        }

        auditLog.record(AuditLog.Kind.OUTCOME, "review.reply_deleted", actorUserId,
                AuditLog.Target.of("review", reviewId), Map.of("seller_id", review.sellerId()));
    }

    /**
     * 신고한다. <b>같은 후기는 한 번만이다</b> — 여러 번 허용하면 신고 수가 여론이 아니라
     * 한 사람의 끈기를 센다({@code review_report_once}).
     *
     * <p><b>자기 후기는 신고하지 않는다.</b> 지우면 되고, 신고로 받으면 관리자가 쓴 사람의 마음을 대신 판단한다.
     */
    @Transactional
    public void report(long actorUserId, long reviewId, ReviewReason reason) {
        Subject review = visibleSubjectOf(reviewId);
        require(actorUserId, "report", Target.ownedBy(review.writerUserId()));
        if (review.writerUserId() == actorUserId) {
            throw new ShopException(ErrorCode.REVIEW_FORBIDDEN, "자기 후기는 신고하지 않는다");
        }

        try {
            jdbc.sql("""
                            insert into review_report (review_id, reporter_user_id, reason)
                            values (:review, :user, :reason)
                            """)
                    .param("review", reviewId)
                    .param("user", actorUserId)
                    .param("reason", reason.code())
                    .update();
        } catch (DuplicateKeyException e) {
            throw new ShopException(ErrorCode.REVIEW_ALREADY_REPORTED);
        }

        auditLog.record(AuditLog.Kind.OUTCOME, "review.reported", actorUserId,
                AuditLog.Target.of("review", reviewId), Map.of("reason", reason.code()));
    }

    /**
     * 신고를 받아들이고 후기를 <b>그 신고의 사유로</b> 내린다.
     *
     * <p><b>이미 내려간 후기면 사유를 안 바꾼다</b> — 처음 내린 판단이 남는다. 신고만 처리된다.
     */
    @Transactional
    public void acceptReport(long actorUserId, long reportId) {
        Report report = reportOf(reportId);
        require(actorUserId, "moderate", Target.ownedBy(report.writerUserId()));
        resolve(actorUserId, reportId, ReviewReportStatus.ACCEPTED);

        int blocked = jdbc.sql("""
                        update review set blocked_at = now(), blocked_reason = :reason
                         where review_id = :review and blocked_at is null
                        """)
                .param("reason", report.reason().code())
                .param("review", report.reviewId())
                .update();

        auditLog.record(AuditLog.Kind.OUTCOME, "review.report_accepted", actorUserId,
                AuditLog.Target.of("review", report.reviewId()),
                Map.of("review_report_id", reportId, "reason", report.reason().code(),
                        "blocked_now", blocked == 1));
    }

    /** 신고를 물린다. 후기는 그대로다 */
    @Transactional
    public void rejectReport(long actorUserId, long reportId) {
        Report report = reportOf(reportId);
        require(actorUserId, "moderate", Target.ownedBy(report.writerUserId()));
        resolve(actorUserId, reportId, ReviewReportStatus.REJECTED);

        auditLog.record(AuditLog.Kind.OUTCOME, "review.report_rejected", actorUserId,
                AuditLog.Target.of("review", report.reviewId()), Map.of("review_report_id", reportId));
    }

    /**
     * 내린 후기를 다시 게시한다. <b>되살림은 이 입구로만 한다</b> — 공개한 운영정책의 「판단이 뒤집히면
     * 다시 게시합니다」가 이 자리고, 이전 사유와 되살린 이유가 감사에 같이 남는다.
     *
     * @param note 왜 되살리나. 이의제기 문의 번호나 판단 근거를 적는다
     */
    @Transactional
    public void restore(long actorUserId, long reviewId, String note) {
        Blocked blocked = blockedOf(reviewId);
        require(actorUserId, "moderate", Target.ownedBy(blocked.writerUserId()));
        if (blocked.reason() == null) {
            throw new ShopException(ErrorCode.REVIEW_NOT_BLOCKED);
        }

        int updated = jdbc.sql("""
                        update review set blocked_at = null, blocked_reason = null
                         where review_id = :review and blocked_at is not null and deleted_at is null
                        """)
                .param("review", reviewId)
                .update();
        if (updated == 0) {
            throw new ShopException(ErrorCode.REVIEW_NOT_BLOCKED);
        }

        auditLog.record(AuditLog.Kind.OUTCOME, "review.restored", actorUserId,
                AuditLog.Target.of("review", reviewId),
                Map.of("previous_reason", blocked.reason().code(), "note", note));
    }

    /**
     * 신고를 처리한다. <b>조건부 UPDATE 가 곧 판정이다</b> — 읽고 나서 쓰는 사이를 우리가 못 잠그고,
     * 이미 처리된 신고를 건드리면 트리거가 500 으로 답한다. 접수 상태일 때만 옮기고 아니면 409 다.
     */
    private void resolve(long actorUserId, long reportId, ReviewReportStatus to) {
        int updated = jdbc.sql("""
                        update review_report
                           set status = :to, resolved_at = now(), resolved_by_user_id = :actor
                         where review_report_id = :id and status = :pending
                        """)
                .param("to", to.code())
                .param("actor", actorUserId)
                .param("id", reportId)
                .param("pending", ReviewReportStatus.PENDING.code())
                .update();
        if (updated == 0) {
            throw new ShopException(ErrorCode.REVIEW_REPORT_ALREADY_RESOLVED);
        }
    }

    private void require(long actorUserId, String action, Target target) {
        if (!evaluator.decide(actorUserId, RESOURCE, action, target).allowed()) {
            throw new ShopException(ErrorCode.REVIEW_FORBIDDEN);
        }
    }

    /** 답하고 신고할 대상. 공개 목록에 보이는 후기만이다 — 내려간·지운 것은 없는 것과 같다 */
    private record Subject(long writerUserId, long sellerId) {}

    private Subject visibleSubjectOf(long reviewId) {
        return jdbc.sql("""
                        select r.user_id, p.seller_id
                          from review r
                          join product p on p.product_id = r.product_id
                         where r.review_id = :id and r.deleted_at is null and r.blocked_at is null
                        """)
                .param("id", reviewId)
                .query((rs, rowNum) -> new Subject(rs.getLong("user_id"), rs.getLong("seller_id")))
                .optional()
                .orElseThrow(() -> new ShopException(ErrorCode.REVIEW_NOT_FOUND));
    }

    private record Report(long reviewId, long writerUserId, ReviewReason reason) {}

    private Report reportOf(long reportId) {
        return jdbc.sql("""
                        select rp.review_id, r.user_id, rp.reason
                          from review_report rp
                          join review r on r.review_id = rp.review_id
                         where rp.review_report_id = :id
                        """)
                .param("id", reportId)
                .query((rs, rowNum) -> new Report(rs.getLong("review_id"), rs.getLong("user_id"),
                        ReviewReason.of(rs.getString("reason"))))
                .optional()
                .orElseThrow(() -> new ShopException(ErrorCode.REVIEW_REPORT_NOT_FOUND));
    }

    /** @param reason 안 내려갔으면 {@code null} */
    private record Blocked(long writerUserId, ReviewReason reason) {}

    private Blocked blockedOf(long reviewId) {
        return jdbc.sql("""
                        select user_id, blocked_reason
                          from review
                         where review_id = :id and deleted_at is null
                        """)
                .param("id", reviewId)
                .query((rs, rowNum) -> {
                    String reason = rs.getString("blocked_reason");
                    return new Blocked(rs.getLong("user_id"), reason == null ? null : ReviewReason.of(reason));
                })
                .optional()
                .orElseThrow(() -> new ShopException(ErrorCode.REVIEW_NOT_FOUND));
    }
}
