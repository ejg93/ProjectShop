package com.projectshop.shop.product;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.projectshop.shop.auth.PermissionEvaluator;
import com.projectshop.shop.auth.PermissionEvaluator.Target;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.support.EnumValue;
import com.projectshop.shop.support.ImagePipeline;
import com.projectshop.shop.support.ListQuery.Paging;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 판정할 저작권 신고를 훑는다(`Q183`).
 *
 * <p><b>`Q94` 가 접수와 판정 입구를 세우고 목록을 안 세웠다</b> — 판정 입구가 신고 번호를 받는데 그 번호를 관리자가
 * 볼 자리가 없어서, 절차가 있어도 돌릴 수가 없었다(저작권법 제103조, `D2` `R42`).
 *
 * <p><b>판정과 같은 권한이다</b>({@code product:moderate}). 셀러는 못 본다 — 신고의 상대가 신고를 보고 판정하는
 * 구조를 막으려고 판정 권한을 따로 팠고, 보는 것도 같은 줄에 선다.
 */
@Service
public class CopyrightReportQuery {

    private final JdbcClient jdbc;
    private final PermissionEvaluator evaluator;
    private final ImagePipeline images;

    CopyrightReportQuery(JdbcClient jdbc, PermissionEvaluator evaluator, ImagePipeline images) {
        this.jdbc = jdbc;
        this.evaluator = evaluator;
        this.images = images;
    }

    /**
     * 신고 한 줄.
     *
     * @param target        무엇을 신고했나(`Q196`) — 상품 사진이나 후기 사진. 사진이 지워져도 남는다
     * @param thumbnailUrl  신고된 사진의 썸네일. 이미 내렸거나 올린 사람이 지웠으면 {@code null} 이다 — 대상이 후기
     *                      사진이면 그 사진의 것이다. <b>관리자가 사진을 보고 판정한다</b>
     * @param reporterEmail 권리자에게 결과를 알리는 자리라 관리자에게만 나간다
     * @param decision      판정(대문자). 아직 안 봤으면 {@code null}
     */
    @Schema(name = "CopyrightReport")
    public record Item(long copyrightReportId, long productId, String productName, String target,
            Long productImageId, Long reviewImageId, String thumbnailUrl, String reporterName, String reporterEmail, String claimedWork,
            OffsetDateTime reportedAt, String decision, OffsetDateTime decidedAt) {}

    @Schema(name = "CopyrightReportPage")
    public record Result(List<Item> items, int page, int size, long total) {}

    /**
     * @param pending 참이면 아직 판정 안 한 것(오래된 것부터), 거짓이면 판정한 것(최근 것부터)
     */
    public Result find(long viewerId, boolean pending, Paging paging) {
        // 남의 셀러 상품 하나. all 스코프에서만 덮인다 — 관리자만 연다.
        if (!evaluator.decide(viewerId, "product", "moderate", Target.ofSeller(-1L)).allowed()) {
            throw new ShopException(ErrorCode.PRODUCT_FORBIDDEN);
        }

        String where = pending ? "cr.decision is null" : "cr.decision is not null";
        String order = pending ? "cr.reported_at asc, cr.copyright_report_id asc"
                : "cr.decided_at desc, cr.copyright_report_id desc";

        // 조건과 정렬은 두 값 중 하나라 사용자 입력이 섞일 자리가 없다 — 바인딩 변수로는 못 넘긴다.
        List<Item> items = jdbc.sql("""
                                select cr.copyright_report_id, cr.product_id, p.name as product_name,
                                       cr.target, cr.product_image_id, cr.review_image_id,
                                       coalesce(pi.thumbnail_key, ri.thumbnail_key) as thumbnail_key,
                                       cr.reporter_name,
                                       cr.reporter_email, cr.claimed_work, cr.reported_at,
                                       cr.decision, cr.decided_at
                                  from copyright_report cr
                                  join product p on p.product_id = cr.product_id
                                  left join product_image pi on pi.product_image_id = cr.product_image_id
                                  left join review_image ri on ri.review_image_id = cr.review_image_id
                                 where {where}
                                 order by {order}
                                 limit :size offset :offset
                                """.replace("{where}", where).replace("{order}", order))
                        .param("size", paging.size())
                        .param("offset", paging.page() * paging.size())
                        .query((rs, rowNum) -> {
                            String thumbnailKey = rs.getString("thumbnail_key");
                            String decision = rs.getString("decision");
                            return new Item(
                                    rs.getLong("copyright_report_id"),
                                    rs.getLong("product_id"),
                                    rs.getString("product_name"),
                                    EnumValue.of(rs.getString("target"), CopyrightTarget::of),
                                    rs.getObject("product_image_id", Long.class),
                                    rs.getObject("review_image_id", Long.class),
                                    thumbnailKey == null ? null : images.url(thumbnailKey),
                                    rs.getString("reporter_name"),
                                    rs.getString("reporter_email"),
                                    rs.getString("claimed_work"),
                                    rs.getObject("reported_at", OffsetDateTime.class),
                                    EnumValue.of(decision, CopyrightDecision::of),
                                    rs.getObject("decided_at", OffsetDateTime.class));
                        })
                .list();

        long total = jdbc.sql("select count(*) from copyright_report cr where {where}".replace("{where}", where))
                .query(Long.class)
                .single();
        return new Result(items, paging.page(), paging.size(), total);
    }
}
