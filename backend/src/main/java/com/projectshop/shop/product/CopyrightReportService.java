package com.projectshop.shop.product;

import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectshop.shop.audit.AuditLog;
import com.projectshop.shop.auth.PermissionEvaluator;
import com.projectshop.shop.auth.PermissionEvaluator.Target;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.support.ObjectStorage;
import com.projectshop.shop.support.ObjectStorage.Visibility;

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

    private final JdbcClient jdbc;
    private final PermissionEvaluator evaluator;
    private final ObjectStorage storage;
    private final AuditLog auditLog;

    CopyrightReportService(JdbcClient jdbc, PermissionEvaluator evaluator,
            ObjectStorage storage, AuditLog auditLog) {
        this.jdbc = jdbc;
        this.evaluator = evaluator;
        this.storage = storage;
        this.auditLog = auditLog;
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
     */
    @Transactional
    public void decide(long actorUserId, long reportId, CopyrightDecision decision) {
        Pending pending = jdbc.sql(FIND_PENDING)
                .param("id", reportId)
                .query(Pending.class)
                .optional()
                .orElseThrow(() -> new ShopException(ErrorCode.PRODUCT_NOT_FOUND));

        // **동작을 새로 팠다.** 기존 product:delete 는 seller_owner 가 seller 스코프로 갖고 있어서,
        // 그것을 쓰면 셀러가 자기 상품에 들어온 신고를 스스로 판정한다 — 신고의 상대가 판정하는 구조다.
        if (!evaluator.decide(actorUserId, "product", "moderate",
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
                new AuditLog.Target("copyright_report", reportId),
                Map.of("decision", decision.code()));
    }

    /** @param productImageId·reviewImageId 신고한 쪽 하나만 차고, 사진이 이미 사라졌으면 둘 다 {@code null} 이다 */
    private record Pending(Long productImageId, Long reviewImageId, long sellerId) {
    }

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
            select r.product_image_id, r.review_image_id, p.seller_id
              from copyright_report r
              join product p on p.product_id = r.product_id
             where r.copyright_report_id = :id and r.decided_at is null
            """;

    /**
     * 원본과 썸네일을 둘 다 지운다. 하나만 지우면 다른 하나로 같은 그림이 계속 열린다.
     *
     * <p><b>저장소를 먼저 지운다.</b> 행을 먼저 지우면 객체 열쇠를 잃어서 지울 대상을
     * 영영 못 찾고, 남는 것은 주인 없는 파일이다 — 그것이 곧 위반 상태다.
     */
    private void takeDown(long imageId) {
        Keys keys = jdbc.sql("""
                        select object_key, thumbnail_key from product_image
                         where product_image_id = :id
                        """)
                .param("id", imageId)
                .query(Keys.class)
                .single();

        storage.delete(Visibility.PUBLIC, keys.objectKey());
        storage.delete(Visibility.PUBLIC, keys.thumbnailKey());

        jdbc.sql("delete from product_image where product_image_id = :id")
                .param("id", imageId)
                .update();
    }

    /**
     * 후기 사진을 내린다(`Q196`). 상품 사진과 같은 순서다 — <b>저장소를 먼저 지운다</b>.
     *
     * <p><b>후기 글은 그대로다.</b> 신고된 것은 사진이고 글은 사는 사람의 말이라, 글까지 내리면 저작권 절차로
     * 후기를 지우는 길이 된다. 글을 내리는 것은 후기 신고(`Q171`)의 몫이다.
     */
    private void takeDownReviewImage(long imageId) {
        Keys keys = jdbc.sql("""
                        select object_key, thumbnail_key from review_image
                         where review_image_id = :id
                        """)
                .param("id", imageId)
                .query(Keys.class)
                .single();

        storage.delete(Visibility.PUBLIC, keys.objectKey());
        storage.delete(Visibility.PUBLIC, keys.thumbnailKey());

        jdbc.sql("delete from review_image where review_image_id = :id")
                .param("id", imageId)
                .update();
    }

    private record Keys(String objectKey, String thumbnailKey) {
    }
}
