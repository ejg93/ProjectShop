package com.projectshop.shop.product;

import java.util.List;
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
                            (product_image_id, product_id, reporter_name, reporter_email, claimed_work)
                        select i.product_image_id, i.product_id, :name, :email, :claimedWork
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
     * 관리자가 판정한다. {@code taken_down} 이면 <b>저장소의 객체까지 지운다.</b>
     *
     * <p>순서가 <b>저장소 먼저</b>다. 행을 먼저 지우면 객체 열쇠를 잃어서 지울 대상을
     * 영영 못 찾고, 남는 것은 주인 없는 파일이다 — 그것이 곧 위반 상태다.
     *
     * <p>판정을 <b>감사 로그에 남긴다.</b> 법이 요구한 절차를 실제로 돌렸다는 증거가
     * 그 줄이고, 없으면 「절차가 있다」를 문서로만 주장하게 된다.
     */
    @Transactional
    public void decide(long actorUserId, long reportId, String decision) {
        if (!List.of("taken_down", "rejected").contains(decision)) {
            throw new ShopException(ErrorCode.VALIDATION_FAILED);
        }

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

        if (decision.equals("taken_down")) {
            takeDown(pending.productImageId());
        }

        jdbc.sql("""
                        update copyright_report
                           set decision = :decision, decided_at = now(),
                               decided_by_user_id = :actor
                         where copyright_report_id = :id
                        """)
                .param("decision", decision)
                .param("actor", actorUserId)
                .param("id", reportId)
                .update();

        auditLog.record(AuditLog.Kind.OUTCOME, "copyright.decided", actorUserId,
                new AuditLog.Target("copyright_report", reportId),
                Map.of("decision", decision));
    }

    private record Pending(long productImageId, long sellerId) {
    }

    private static final String FIND_PENDING = """
            select r.product_image_id, p.seller_id
              from copyright_report r
              join product_image i on i.product_image_id = r.product_image_id
              join product p on p.product_id = i.product_id
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

    private record Keys(String objectKey, String thumbnailKey) {
    }
}
