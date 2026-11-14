package com.projectshop.shop.product;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.projectshop.shop.audit.AuditTargetType;
import com.projectshop.shop.audit.AuditLog;
import com.projectshop.shop.auth.PermissionEvaluator;
import com.projectshop.shop.auth.PermissionEvaluator.Target;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.notification.NotificationService;
import com.projectshop.shop.support.BusinessCalendar;
import com.projectshop.shop.support.ObjectStorage;
import com.projectshop.shop.support.ObjectStorage.Visibility;
import com.projectshop.shop.auth.PermissionResource;

/**
 * 남의 저작물 신고를 받고 처리한다({@code Q94}, {@code D2} {@code R42}).
 *
 * <h2>왜 이 절차가 있어야 하나</h2>
 *
 * <p>저작권법 제102조·제103조가 요구한다. <b>신고를 받고 처리하는 자리가 있어야
 * 온라인서비스제공자 책임 제한을 받는다</b> — 없으면 셀러가 올린 남의 사진에 우리가 같이 책임진다.
 *
 * <h2>지우는 것은 표가 아니라 파일이다</h2>
 *
 * <p>행만 지우고 객체를 두면 <b>서명 URL 을 아는 사람에게 계속 열린다</b>
 * ({@code media-rules.md} 「남의 것이 올라오면」). 그래서 판정이 저장소까지 간다.
 *
 * <p>순서가 <b>저장소 먼저</b>다. 행을 먼저 지우면 객체 열쇠를 잃고, 그러면
 * 지울 대상을 영영 못 찾는다 — 남는 것은 주인 없는 파일이고 그것이 곧 위반이다.
 */
@Service
public class CopyrightReportService {

    /**
     * 숨긴 사진을 지우기까지 남기는 날. 통보 3일 + 재개 요구 30일 + 재개 예정일 14일(저작권법 시행령 제41~43조, `D2` R42) —
     * 그 안에는 재개(제103조③)할 것이 남아야 한다.
     */
    static final int HIDDEN_RETENTION_DAYS = 47;

    /**
     * <b>{@code product_image} 를 안 읽는다.</b> 사진이 이미 사라진 신고도 판정할 수 있어야
     * 하는데 — 표가 {@code set null} 로 그 상태를 일부러 만들어 뒀다 — 그 표를 거쳐 읽으면
     * <b>그 행은 영영 미판정으로 남는다</b>(마무리 26차 독립 리뷰가 찾았다).
     *
     * <p>셀러는 {@code r.product_id} 에서 바로 온다. 사진이 아니라 <b>상품</b>이 판정 대상의
     * 주인이라, 사진이 사라져도 누가 판정할 수 있는지는 그대로 답해진다.
     *
     * <p>{@code product_image_id} 는 <b>지울 대상을 찾을 때만</b> 쓰고 그때는 {@code null} 일 수 있다.
     */
    private static final String FIND_PENDING = """
            select r.product_image_id, r.review_image_id, p.seller_id, r.target, r.claimed_work, v.user_id as review_author_id
              from copyright_report r
              join product p on p.product_id = r.product_id
              left join review_image ri on ri.review_image_id = r.review_image_id
              left join review v on v.review_id = ri.review_id
             where r.copyright_report_id = :id and r.decided_at is null
            """;

    /** 통보 본문의 판정 시각 — 받는 사람이 읽는 시각이라 한국 시간이다 */
    private static final DateTimeFormatter NOTICE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final JdbcClient jdbc;
    private final PermissionEvaluator evaluator;
    private final ObjectStorage storage;
    private final AuditLog auditLog;
    private final NotificationService notifications;
    private final CopyrightRecipient recipient;
    private final TransactionTemplate transactions;

    CopyrightReportService(JdbcClient jdbc, PermissionEvaluator evaluator, ObjectStorage storage, AuditLog auditLog,
            NotificationService notifications, CopyrightRecipient recipient, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.evaluator = evaluator;
        this.storage = storage;
        this.auditLog = auditLog;
        this.notifications = notifications;
        this.recipient = recipient;
        this.transactions = transactions;
    }

    /**
     * @param reporterName  신고자. <b>계정이 아니라 연락처로 받는다</b> — 저작권자가
     *                      우리 회원일 이유가 없고, 회원만 신고할 수 있게 하면
     *                      법이 요구한 절차에 가입이라는 관문이 하나 붙는다
     * @param claimedWork         어떤 저작물에 대한 권리인지. 법이 특정할 수 있는 정보를 요구한다
     */
    public record Command(String reporterName, String reporterEmail, String claimedWork) {
    }

    public record Received(long copyrightReportId) {
    }

    /** 로그인 없이 받는다. 접수 자체는 판정이 아니고, 판정은 {@link #decide} 가 한다 */
    @Transactional
    public Received report(long productImageId, Command command) {
        long id = jdbc.sql("""
                        insert into copyright_report
                            (product_image_id, product_id, target, reporter_name, reporter_email, claimed_work)
                        select i.product_image_id, i.product_id, 'product_image', :name, :email, :claimedWork
                          from product_image i
                         where i.product_image_id = :imageId
                        returning copyright_report_id
                        """)
                .param("imageId", productImageId)
                .param("name", command.reporterName())
                .param("email", command.reporterEmail())
                .param("claimedWork", command.claimedWork())
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new ShopException(ErrorCode.PRODUCT_NOT_FOUND));

        return new Received(id);
    }

    /**
     * 후기 사진을 신고받는다(`Q196`). 상품 사진과 같은 절차다 — 후기 사진도 우리가 여는 공개 표면이라
     * 권리자가 알려 올 자리가 없으면 그 사진에 대해 책임 제한을 못 받는다(저작권법 제103조, `R42`).
     *
     * <p><b>지운 후기의 사진은 안 받는다.</b> 후기를 지우면 공개 목록에서 빠져서 신고할 표면이 없다.
     */
    @Transactional
    public Received reportReviewImage(long reviewImageId, Command command) {
        long id = jdbc.sql("""
                        insert into copyright_report
                            (review_image_id, product_id, target, reporter_name, reporter_email, claimed_work)
                        select ri.review_image_id, r.product_id, 'review_image', :name, :email, :claimedWork
                          from review_image ri
                          join review r on r.review_id = ri.review_id
                         where ri.review_image_id = :imageId and r.deleted_at is null
                        returning copyright_report_id
                        """)
                .param("imageId", reviewImageId)
                .param("name", command.reporterName())
                .param("email", command.reporterEmail())
                .param("claimedWork", command.claimedWork())
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new ShopException(ErrorCode.REVIEW_NOT_FOUND));

        return new Received(id);
    }

    /**
     * 관리자가 판정한다. {@code taken_down} 이면 <b>저장소의 객체까지 지운다.</b>
     *
     * <p>순서가 <b>저장소 먼저</b>다. 행을 먼저 지우면 객체 열쇠를 잃어서 지울 대상을
     * 영영 못 찾고, 남는 것은 주인 없는 파일이다 — 그것이 곧 위반 상태다.
     *
     * <p>판정을 <b>감사 로그에 남긴다.</b> 법이 요구한 절차를 실제로 돌렸다는 증거가
     * 그 줄이고, 없으면 「절차가 있다」를 문서로만 주장하게 된다.
     *
     * <p><b>통보는 커밋 뒤에 보낸다</b>(`Q463`) — 발송이 바깥 시스템이라 트랜잭션 안에서 부르면 메일 응답을 기다리는 동안
     * 잠금이 남는다(`concurrency-rules.md` 「트랜잭션 경계」, `ArchitectureTest`). 비밀번호 재설정(`PasswordResetService`)과 같은 꼴이다.
     */
    public void decide(long actorUserId, long reportId, CopyrightDecision decision) {
        Pending pending = transactions.execute(status -> record(actorUserId, reportId, decision));

        // 중단하면 권리주장자와 복제·전송자에게 알린다(저작권법 제103조②, 시행령 제41조 「3일 이내」).
        // 기각은 법이 통보를 요구하지 않는다.
        if (decision == CopyrightDecision.TAKEN_DOWN) {
            notifications.sendCopyrightTakenDown(reportId, uploaders(pending), Map.of(
                    "claimed_work", pending.claimedWork(),
                    "decided_at", OffsetDateTime.now(BusinessCalendar.ZONE).format(NOTICE_TIME),
                    "recipient", recipient.configured() ? recipient.displayText() : "신고 화면에 공지된 수령인"));
        }
    }

    /** 판정을 기록한다 — 판정 · 내리기 · 감사가 한 트랜잭션이다 */
    private Pending record(long actorUserId, long reportId, CopyrightDecision decision) {
        Pending pending = jdbc.sql(FIND_PENDING)
                .param("id", reportId)
                .query(Pending.class)
                .optional()
                .orElseThrow(() -> new ShopException(ErrorCode.PRODUCT_NOT_FOUND));

        // **동작을 새로 팠다.** 기존 product:delete 는 seller_owner 가 seller 스코프로 갖고 있어서,
        // 그것을 쓰면 셀러가 자기 상품에 들어온 신고를 스스로 판정한다 — 신고의 상대가 판정하는 구조다.
        if (!evaluator.decide(actorUserId, PermissionResource.PRODUCT, "moderate",
                Target.ofSeller(pending.sellerId())).allowed()) {
            throw new ShopException(ErrorCode.PRODUCT_FORBIDDEN);
        }

        // 사진이 이미 사라졌으면 내릴 것이 없다. 판정은 그대로 기록한다 —
        // 「이미 없어서 안 내렸다」와 「판정을 안 했다」는 다른 말이다.
        if (decision == CopyrightDecision.TAKEN_DOWN && pending.productImageId() != null) {
            takeDown(pending.productImageId());
        }
        if (decision == CopyrightDecision.TAKEN_DOWN && pending.reviewImageId() != null) {
            takeDownReviewImage(pending.reviewImageId());
        }

        jdbc.sql("""
                        update copyright_report
                           set decision = :decision, decided_at = now(),
                               decided_by_user_id = :actor
                         where copyright_report_id = :id
                        """)
                .param("decision", decision.code())
                .param("actor", actorUserId)
                .param("id", reportId)
                .update();

        auditLog.record(AuditLog.Kind.OUTCOME, "copyright.decided", actorUserId,
                new AuditLog.Target(AuditTargetType.COPYRIGHT_REPORT, reportId),
                Map.of("decision", decision.code()));
        return pending;
    }

    /** 그 신고의 복제·전송자 — 재개 통보(`Q465`)가 부른다. 숨긴 사진은 행이 남아 있어 작성자를 찾는다 */
    List<Long> uploadersOf(long reportId) {
        return uploaders(jdbc.sql("""
                        select r.product_image_id, r.review_image_id, p.seller_id, r.target, r.claimed_work,
                               v.user_id as review_author_id
                          from copyright_report r
                          join product p on p.product_id = r.product_id
                          left join review_image ri on ri.review_image_id = r.review_image_id
                          left join review v on v.review_id = ri.review_id
                         where r.copyright_report_id = :id
                        """)
                .param("id", reportId)
                .query(Pending.class)
                .single());
    }

    /**
     * 복제·전송자(저작권법 제103조②). 상품 사진은 그 셀러의 구성원 전원 — 누가 올렸는지를 표가 안 들고, 셀러 계정이
     * 게시의 주체다. 후기 사진은 작성자다 — 사진이 이미 사라져 작성자를 못 찾으면 비어 있다.
     */
    private List<Long> uploaders(Pending pending) {
        if (CopyrightTarget.REVIEW_IMAGE.code().equals(pending.target())) {
            return pending.reviewAuthorId() == null ? List.of() : List.of(pending.reviewAuthorId());
        }
        return jdbc.sql("select user_id from seller_member where seller_id = :sellerId order by user_id")
                .param("sellerId", pending.sellerId())
                .query(Long.class)
                .list();
    }

    /**
     * @param productImageId·reviewImageId 신고한 쪽 하나만 차고, 사진이 이미 사라졌으면 둘 다 {@code null} 이다
     * @param reviewAuthorId 후기 사진의 작성자 — 사진이 남아 있을 때만 찾는다
     */
    private record Pending(Long productImageId, Long reviewImageId, long sellerId, String target, String claimedWork,
            Long reviewAuthorId) {
    }

    /**
     * 원본과 썸네일을 둘 다 비공개 버킷으로 숨긴다(`Q464`). 하나만 옮기면 다른 하나로 같은 그림이 계속 열린다.
     *
     * <p><b>지우지 않는다</b> — 복제·전송자가 재개를 요구하면(저작권법 제103조③) 되돌릴 것이 있어야 한다. 47일 뒤 재개 예정이
     * 없으면 {@link CopyrightHiddenImagePurgeBatch} 가 지운다.
     *
     * <p><b>행을 먼저 숨긴다.</b> 옮기다 실패하면 트랜잭션이 행을 되돌리고 객체는 공개에 남아 다시 판정할 수 있다. 이미 숨긴
     * 사진(같은 사진의 둘째 신고)은 0행이라 안 옮긴다.
     */
    private void takeDown(long imageId) {
        jdbc.sql("""
                        update product_image set hidden_at = now()
                         where product_image_id = :id and hidden_at is null
                        returning object_key, thumbnail_key
                        """)
                .param("id", imageId)
                .query(Keys.class)
                .optional()
                .ifPresent(keys -> hide(keys));
    }

    /** 숨긴 상품 사진을 공개로 되돌린다(`Q465` 재개). 이미 공개거나 파기됐으면 거짓이다 */
    boolean restore(long imageId) {
        return jdbc.sql("""
                        update product_image set hidden_at = null
                         where product_image_id = :id and hidden_at is not null
                        returning object_key, thumbnail_key
                        """)
                .param("id", imageId)
                .query(Keys.class)
                .optional()
                .map(keys -> {
                    show(keys);
                    return true;
                })
                .orElse(false);
    }

    /**
     * 숨긴 지 {@link #HIDDEN_RETENTION_DAYS} 일이 지난 사진을 비공개 버킷과 표에서 지운다({@link CopyrightHiddenImagePurgeBatch}).
     * <b>저장소를 먼저 지운다</b> — 행을 먼저 지우면 객체 열쇠를 잃는다. 신고 기록은 {@code set null} 로 남는다.
     *
     * @return 지운 사진 수
     */
    @Transactional
    int purgeHidden(LocalDate baselineDate) {
        OffsetDateTime cutoff = baselineDate.minusDays(HIDDEN_RETENTION_DAYS - 1L)
                .atStartOfDay(BusinessCalendar.ZONE).toOffsetDateTime();
        record Hidden(String kind, long imageId, String objectKey, String thumbnailKey) {
        }
        // 재개 요구가 판단 전이거나 재개 예정인 것은 안 지운다(`Q465`) — 지우면 그 예정일에 되돌릴 것이 없다.
        List<Hidden> hidden = jdbc.sql("""
                        select 'product' as kind, i.product_image_id as image_id, i.object_key, i.thumbnail_key
                          from product_image i
                         where i.hidden_at < :cutoff
                           and not exists (select 1 from copyright_report r
                                             join copyright_reinstatement x on x.copyright_report_id = r.copyright_report_id
                                            where r.product_image_id = i.product_image_id
                                              and (x.decision is null
                                                   or (x.decision = 'scheduled' and x.reinstated_at is null
                                                       and x.suit_notified_at is null)))
                        union all
                        select 'review', i.review_image_id, i.object_key, i.thumbnail_key
                          from review_image i
                         where i.hidden_at < :cutoff
                           and not exists (select 1 from copyright_report r
                                             join copyright_reinstatement x on x.copyright_report_id = r.copyright_report_id
                                            where r.review_image_id = i.review_image_id
                                              and (x.decision is null
                                                   or (x.decision = 'scheduled' and x.reinstated_at is null
                                                       and x.suit_notified_at is null)))
                        """)
                .param("cutoff", cutoff)
                .query(Hidden.class)
                .list();
        for (Hidden photo : hidden) {
            storage.delete(Visibility.PRIVATE, photo.objectKey());
            storage.delete(Visibility.PRIVATE, photo.thumbnailKey());
            String table = "product".equals(photo.kind())
                    ? "delete from product_image where product_image_id = :id"
                    : "delete from review_image where review_image_id = :id";
            jdbc.sql(table).param("id", photo.imageId()).update();
        }
        return hidden.size();
    }

    /** 숨김 중인 사진 수 — 지표가 쌓임을 보인다 */
    long countHidden() {
        return jdbc.sql("""
                        select (select count(*) from product_image where hidden_at is not null)
                             + (select count(*) from review_image where hidden_at is not null)
                        """)
                .query(Long.class)
                .single();
    }

    private void hide(Keys keys) {
        storage.move(Visibility.PUBLIC, Visibility.PRIVATE, keys.objectKey());
        storage.move(Visibility.PUBLIC, Visibility.PRIVATE, keys.thumbnailKey());
    }

    private void show(Keys keys) {
        storage.move(Visibility.PRIVATE, Visibility.PUBLIC, keys.objectKey());
        storage.move(Visibility.PRIVATE, Visibility.PUBLIC, keys.thumbnailKey());
    }

    /**
     * 후기 사진을 내린다(`Q196`). 상품 사진과 같은 순서다 — <b>저장소를 먼저 지운다</b>.
     *
     * <p><b>후기 글은 그대로다.</b> 신고된 것은 사진이고 글은 사는 사람의 말이라, 글까지 내리면 저작권 절차로
     * 후기를 지우는 길이 된다. 글을 내리는 것은 후기 신고(`Q171`)의 몫이다.
     */
    private void takeDownReviewImage(long imageId) {
        jdbc.sql("""
                        update review_image set hidden_at = now()
                         where review_image_id = :id and hidden_at is null
                        returning object_key, thumbnail_key
                        """)
                .param("id", imageId)
                .query(Keys.class)
                .optional()
                .ifPresent(keys -> hide(keys));
    }

    /** 숨긴 후기 사진을 공개로 되돌린다(`Q465` 재개). 이미 공개거나 파기됐으면 거짓이다 */
    boolean restoreReviewImage(long imageId) {
        return jdbc.sql("""
                        update review_image set hidden_at = null
                         where review_image_id = :id and hidden_at is not null
                        returning object_key, thumbnail_key
                        """)
                .param("id", imageId)
                .query(Keys.class)
                .optional()
                .map(keys -> {
                    show(keys);
                    return true;
                })
                .orElse(false);
    }

    private record Keys(String objectKey, String thumbnailKey) {
    }
}
