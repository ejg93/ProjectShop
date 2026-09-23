package com.projectshop.shop.review;

import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectshop.shop.audit.AuditLog;
import com.projectshop.shop.auth.PermissionEvaluator;
import com.projectshop.shop.auth.PermissionEvaluator.Target;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.support.ImagePipeline;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 쓴 사람이 자기 후기에 사진을 붙이고 뗀다(`Q159`).
 *
 * <p><b>받은 바이트는 {@link ImagePipeline} 이 다룬다</b> — 판별·EXIF 제거·썸네일·저장이 상품 사진과 같은 규칙이다.
 * 여기 남은 것은 <b>누구의 후기인가</b>(판정)와 행이다.
 *
 * <p><b>판정은 후기 고치기와 같다</b>({@code review:update}, {@code own}). 사진을 붙이는 것은 후기를 고치는 것이고,
 * 권한을 따로 두면 「글은 못 고치는데 사진은 붙이는」 사람이 생긴다.
 *
 * <p><b>공개 게시물이다</b>(사용자 선택). 후기 글과 같은 급이라 새 동의 항목이 없다 — 남의 개인정보가 담긴
 * 사진은 신고 사유 {@code PRIVACY} 로 후기째 내린다.
 */
@Service
public class ReviewImageService {

    private final JdbcClient jdbc;
    private final PermissionEvaluator evaluator;
    private final ImagePipeline images;
    private final AuditLog auditLog;

    ReviewImageService(JdbcClient jdbc, PermissionEvaluator evaluator, ImagePipeline images,
            AuditLog auditLog) {
        this.jdbc = jdbc;
        this.evaluator = evaluator;
        this.images = images;
        this.auditLog = auditLog;
    }

    @Schema(name = "ReviewImageUploaded")
    public record Uploaded(long reviewImageId) {}

    /**
     * 사진 한 장을 붙인다. <b>저장소에 먼저 넣고 행을 나중에 쓴다</b> — 까닭은 {@link ImagePipeline#store} 가 든다.
     *
     * <p>장수 상한은 앱이 먼저 답하고(422) {@code review_image_limit} 트리거가 뒤를 막는다.
     */
    @Transactional
    public Uploaded upload(long actorUserId, long reviewId, ImagePipeline.Incoming file) {
        long writerUserId = writerOf(reviewId);
        requireWriter(actorUserId, writerUserId);
        if (countOf(reviewId) >= ImagePipeline.MAX_IMAGES_PER_OWNER) {
            throw new ShopException(ErrorCode.IMAGE_LIMIT_REACHED);
        }

        ImagePipeline.Stored stored = images.store("review", file);

        long id = jdbc.sql("""
                        insert into review_image
                            (review_id, object_key, thumbnail_key, original_name,
                             content_type, byte_size, sort_no)
                        values (:reviewId, :objectKey, :thumbnailKey, :originalName,
                                :contentType, :byteSize,
                                coalesce((select max(sort_no) + 1 from review_image
                                          where review_id = :reviewId), 0))
                        returning review_image_id
                        """)
                .param("reviewId", reviewId)
                .param("objectKey", stored.objectKey())
                .param("thumbnailKey", stored.thumbnailKey())
                .param("originalName", stored.originalName())
                .param("contentType", stored.contentType())
                .param("byteSize", stored.byteSize())
                .query(Long.class)
                .single();

        auditLog.record(AuditLog.Kind.OUTCOME, "review.image_added", actorUserId,
                AuditLog.Target.of("review", reviewId), Map.of("review_image_id", id));
        return new Uploaded(id);
    }

    /** 사진 한 장을 뗀다. <b>저장소까지 간다</b> — 행만 지우면 주인 없는 파일이 공개 버킷에 남는다 */
    @Transactional
    public void delete(long actorUserId, long reviewImageId) {
        record Owned(long reviewId, long writerUserId, String objectKey, String thumbnailKey) {}

        Owned owned = jdbc.sql("""
                        select ri.review_id, r.user_id, ri.object_key, ri.thumbnail_key
                          from review_image ri
                          join review r on r.review_id = ri.review_id
                         where ri.review_image_id = :id and r.deleted_at is null
                        """)
                .param("id", reviewImageId)
                .query((rs, rowNum) -> new Owned(rs.getLong("review_id"), rs.getLong("user_id"),
                        rs.getString("object_key"), rs.getString("thumbnail_key")))
                .optional()
                .orElseThrow(() -> new ShopException(ErrorCode.REVIEW_NOT_FOUND));
        requireWriter(actorUserId, owned.writerUserId());

        images.delete(owned.objectKey(), owned.thumbnailKey());
        jdbc.sql("delete from review_image where review_image_id = :id")
                .param("id", reviewImageId)
                .update();

        auditLog.record(AuditLog.Kind.OUTCOME, "review.image_removed", actorUserId,
                AuditLog.Target.of("review", owned.reviewId()), Map.of("review_image_id", reviewImageId));
    }

    /** 살아 있는 후기의 쓴 사람. 지운 후기에는 붙이지 않는다 */
    private long writerOf(long reviewId) {
        return jdbc.sql("select user_id from review where review_id = :id and deleted_at is null")
                .param("id", reviewId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new ShopException(ErrorCode.REVIEW_NOT_FOUND));
    }

    /**
     * 후기 고치기와 같은 판정이다. <b>남의 후기면 쓰기 쪽과 같은 답</b>({@code REVIEW_NOT_ALLOWED})이다 —
     * 가르면 후기 번호를 두드려 누가 쓴 것인지를 셀 수 있다.
     */
    private void requireWriter(long actorUserId, long writerUserId) {
        if (!evaluator.decide(actorUserId, "review", "update", Target.ownedBy(writerUserId)).allowed()) {
            throw new ShopException(ErrorCode.REVIEW_NOT_ALLOWED);
        }
    }

    private int countOf(long reviewId) {
        return jdbc.sql("select count(*) from review_image where review_id = :id")
                .param("id", reviewId)
                .query(Integer.class)
                .single();
    }
}
