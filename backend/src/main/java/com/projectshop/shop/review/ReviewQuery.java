package com.projectshop.shop.review;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
 * 상품에 달린 후기를 읽는다(`Q160`).
 *
 * <p><b>판정을 안 지난다.</b> 후기는 공개 글이고 {@code review:read} 가 {@code all} 이라
 * 누구에게나 같은 것이 나간다(`47`) — 로그인도 필요 없다. 무엇이 안 나가느냐가 대신 중요하다.
 *
 * <h2>안 나가는 것 둘</h2>
 *
 * <p><b>내려간 후기</b>({@code blocked_at})와 <b>지운 후기</b>({@code deleted_at})는 목록에
 * 안 든다. 인덱스도 그 조건으로 서 있다(`48`).
 *
 * <p><b>쓴 사람의 주소를 안 싣는다.</b> 이름만으로 충분하고, 공개 글에 연락처를 붙이면
 * <b>그 글이 곧 주소록</b>이 된다(`D14`).
 */
@Service
public class ReviewQuery {

    private final JdbcClient jdbc;
    private final PermissionEvaluator evaluator;
    private final ImagePipeline images;

    ReviewQuery(JdbcClient jdbc, PermissionEvaluator evaluator, ImagePipeline images) {
        this.jdbc = jdbc;
        this.evaluator = evaluator;
        this.images = images;
    }

    /**
     * 후기 한 줄.
     *
     * @param mine 지금 보는 사람이 쓴 것인가. <b>안 로그인이면 전부 거짓</b>이다.
     *        <b>지금 이 값을 쓰는 화면은 상품 상세의 라벨 하나뿐이고</b>, 그 화면은
     *        {@code apiPublic} 으로 부르므로 쿠키가 안 실려 <b>언제나 거짓</b>이다 —
     *        고치기·지우기 자리는 세션을 싣는 {@link #findMine}(`Q167`)이 든다.
     *        <b>여기서 계약을 안 지운다</b>: 로그인한 채로 부르는 입구가 생기면 그대로 쓴다
     */
    @Schema(name = "ProductReview")
    public record Item(long reviewId, String writerName, int rating, String body,
            String reply, OffsetDateTime createdAt, boolean mine, List<Photo> photos) {

        Item withPhotos(List<Photo> attached) {
            return new Item(reviewId, writerName, rating, body, reply, createdAt, mine, attached);
        }
    }

    /**
     * 후기 사진 한 장(`Q159`). <b>목록은 썸네일로 그리고 누르면 원본을 연다</b>(`media-rules.md` 「썸네일」).
     * 둘 다 만료 5분 서명 URL 이다.
     *
     * @param reviewImageId <b>번호를 싣는다</b>(`Q196`) — 저작권 신고가 이 번호로 받는다. 상품 사진의 번호가
     *                      공개 상세에 이미 나가는 것(`Q183`)과 같은 판단이다: 바깥 시스템이 부르는 단위가 아니다(`D9`)
     */
    @Schema(name = "ReviewPhoto")
    public record Photo(long reviewImageId, String thumbnailUrl, String originalUrl) {}

    /** 평점 요약. <b>목록과 같이 나간다</b> — 따로 부르면 쪽을 넘길 때마다 다시 센다 */
    @Schema(name = "ProductReviewSummary")
    public record Summary(long count, Double average) {}

    @Schema(name = "ProductReviewPage")
    public record Result(List<Item> items, int page, int size, long total, Summary summary) {}

    /**
     * 그 상품의 후기.
     *
     * @param viewerUserId 로그인 안 했으면 {@code null}. {@code mine} 을 가르는 데만 쓴다
     */
    public Result findByProduct(Long viewerUserId, long productId, Paging paging) {
        List<Item> items = jdbc.sql("""
                                select r.review_id, u.display_name, r.rating, r.body,
                                       rr.body as reply, r.created_at, r.user_id
                                  from review r
                                  join app_user u on u.user_id = r.user_id
                                  left join review_reply rr
                                         on rr.review_id = r.review_id and rr.deleted_at is null
                                 where r.product_id = :productId
                                   and r.deleted_at is null and r.blocked_at is null
                                 order by r.created_at desc, r.review_id desc
                                 limit :size offset :offset
                                """)
                        .param("productId", productId)
                        .param("size", paging.size())
                        .param("offset", paging.page() * paging.size())
                        .query((rs, rowNum) -> new Item(
                                rs.getLong("review_id"),
                                rs.getString("display_name"),
                                rs.getInt("rating"),
                                rs.getString("body"),
                                rs.getString("reply"),
                                rs.getObject("created_at", OffsetDateTime.class),
                                viewerUserId != null && rs.getLong("user_id") == viewerUserId, List.of()))
                .list();
        items = attachPhotos(items);

        Summary summary = summaryOf(productId);
        return new Result(items, paging.page(), paging.size(), summary.count(), summary);
    }

    /**
     * 개수와 평균.
     *
     * <p><b>평균을 반올림하지 않는다.</b> 어떻게 보여 줄지는 화면이 정하고, 여기서 자르면
     * <b>4.5 와 4.45 가 같은 값으로</b> 내려간다 — 표시 규칙이 서버에 박히는 것이다.
     *
     * <p>후기가 없으면 평균이 {@code null} 이다. 0 으로 두면 <b>「별 0점」과 「후기 없음」이
     * 같은 값</b>이 된다(`D23` 「빈 값에 뜻을 싣지 않는다」).
     */
    private Summary summaryOf(long productId) {
        return jdbc.sql("""
                        select count(*) as review_count, avg(rating)::float8 as average
                          from review
                         where product_id = :productId
                           and deleted_at is null and blocked_at is null
                        """)
                .param("productId", productId)
                .query((rs, rowNum) -> new Summary(
                        rs.getLong("review_count"),
                        (Double) rs.getObject("average")))
                .single();
    }

    /**
     * 내가 쓴 후기 한 줄(`Q167`).
     *
     * <p><b>내려간 것도 나온다.</b> 공개한 운영정책이 「내려가면 그 사실과 사유를 확인할 수 있다」고
     * 알리는 자리가 여기다(`D2` `R27`) — 상품 목록에서는 빠지지만 쓴 사람에게서 감출 이유는 없다.
     *
     * @param blockedAt     내려간 시각. 안 내려갔으면 {@code null}
     * @param blockedReason 내린 사유(대문자). {@code blockedAt} 과 같이 비거나 같이 찬다({@code review_block_check})
     */
    @Schema(name = "MyReview")
    public record MyItem(long reviewId, long productId, String productName, int rating, String body,
            String reply, OffsetDateTime createdAt, OffsetDateTime blockedAt, String blockedReason,
            List<MyPhoto> photos) {}

    /** 내 후기의 사진 한 장. <b>번호가 같이 간다</b> — 떼는 입구가 그것을 받는다 */
    @Schema(name = "MyReviewPhoto")
    public record MyPhoto(long reviewImageId, String thumbnailUrl) {}

    @Schema(name = "MyReviewPage")
    public record MyResult(List<MyItem> items, int page, int size, long total) {}

    /** 내가 쓴 후기. 지운 것은 안 나온다 — 쓴 사람이 거둔 것이다 */
    public MyResult findMine(long userId, Paging paging) {
        List<MyItem> items = jdbc.sql("""
                                select r.review_id, r.product_id, p.name as product_name, r.rating,
                                       r.body, rr.body as reply, r.created_at,
                                       r.blocked_at, r.blocked_reason
                                  from review r
                                  join product p on p.product_id = r.product_id
                                  left join review_reply rr
                                         on rr.review_id = r.review_id and rr.deleted_at is null
                                 where r.user_id = :userId and r.deleted_at is null
                                 order by r.created_at desc, r.review_id desc
                                 limit :size offset :offset
                                """)
                        .param("userId", userId)
                        .param("size", paging.size())
                        .param("offset", paging.page() * paging.size())
                        .query((rs, rowNum) -> new MyItem(
                                rs.getLong("review_id"),
                                rs.getLong("product_id"),
                                rs.getString("product_name"),
                                rs.getInt("rating"),
                                rs.getString("body"),
                                rs.getString("reply"),
                                rs.getObject("created_at", OffsetDateTime.class),
                                rs.getObject("blocked_at", OffsetDateTime.class),
                                EnumValue.of(rs.getString("blocked_reason"), ReviewReason::of),
                                List.<MyPhoto>of()))
                .list();
        Map<Long, List<PhotoRow>> photos = photosOf(items.stream().map(MyItem::reviewId).toList());
        items = items.stream()
                .map(item -> new MyItem(item.reviewId(), item.productId(), item.productName(), item.rating(),
                        item.body(), item.reply(), item.createdAt(), item.blockedAt(), item.blockedReason(),
                        photos.getOrDefault(item.reviewId(), List.of()).stream()
                                .map(photo -> new MyPhoto(photo.reviewImageId(), images.url(photo.thumbnailKey())))
                                .toList()))
                .toList();

        long total = jdbc.sql("select count(*) from review where user_id = :userId and deleted_at is null")
                .param("userId", userId)
                .query(Long.class)
                .single();
        return new MyResult(items, paging.page(), paging.size(), total);
    }

    /**
     * 셀러가 답할 후기 한 줄(`Q167`). <b>답이 없으면 {@code reply} 가 {@code null} 이다</b> —
     * 화면이 「답하기」와 「고치기」를 그것으로 가른다.
     */
    @Schema(name = "SellerReview")
    public record SellerItem(long reviewId, long productId, String productName, String writerName,
            int rating, String body, String reply, OffsetDateTime createdAt) {}

    @Schema(name = "SellerReviewPage")
    public record SellerResult(List<SellerItem> items, int page, int size, long total) {}

    /**
     * 내가 답할 수 있는 셀러들의 상품 후기.
     *
     * <p><b>판정에서 범위를 읽는다</b> — 소속된 셀러마다 {@code review:reply} 를 실제로 돌려서
     * 열리는 셀러만 고른다({@code SellerOrderQuery} 와 같은 방법). 하나도 없으면
     * 0건이 아니라 거부다 — 0건과 못 봄이 갈려야 개수로 정보가 새지 않는다.
     *
     * <p><b>내려간 후기는 안 나온다.</b> 공개 목록과 같다 — 셀러가 답할 대상이 아니다.
     */
    public SellerResult findForSeller(long viewerId, Paging paging) {
        Set<Long> sellers = jdbc.sql("select seller_id from seller_member where user_id = :id")
                .param("id", viewerId)
                .query(Long.class)
                .set()
                .stream()
                .filter(sellerId -> evaluator
                        .decide(viewerId, "review", "reply", Target.ofSeller(sellerId)).allowed())
                .collect(Collectors.toUnmodifiableSet());
        if (sellers.isEmpty()) {
            throw new ShopException(ErrorCode.REVIEW_FORBIDDEN, "답할 수 있는 셀러가 없다");
        }

        List<SellerItem> items = jdbc.sql("""
                                select r.review_id, r.product_id, p.name as product_name,
                                       u.display_name, r.rating, r.body, rr.body as reply, r.created_at
                                  from review r
                                  join product p on p.product_id = r.product_id
                                  join app_user u on u.user_id = r.user_id
                                  left join review_reply rr
                                         on rr.review_id = r.review_id and rr.deleted_at is null
                                 where p.seller_id in (:sellers)
                                   and r.deleted_at is null and r.blocked_at is null
                                 order by r.created_at desc, r.review_id desc
                                 limit :size offset :offset
                                """)
                        .param("sellers", sellers)
                        .param("size", paging.size())
                        .param("offset", paging.page() * paging.size())
                        .query((rs, rowNum) -> new SellerItem(
                                rs.getLong("review_id"),
                                rs.getLong("product_id"),
                                rs.getString("product_name"),
                                rs.getString("display_name"),
                                rs.getInt("rating"),
                                rs.getString("body"),
                                rs.getString("reply"),
                                rs.getObject("created_at", OffsetDateTime.class)))
                .list();

        long total = jdbc.sql("""
                        select count(*)
                          from review r
                          join product p on p.product_id = r.product_id
                         where p.seller_id in (:sellers)
                           and r.deleted_at is null and r.blocked_at is null
                        """)
                .param("sellers", sellers)
                .query(Long.class)
                .single();
        return new SellerResult(items, paging.page(), paging.size(), total);
    }

    /**
     * 처리할 신고 한 줄(`Q167`).
     *
     * <p><b>신고한 사람을 안 싣는다.</b> 판단할 것은 글이지 누가 신고했나가 아니다 — 실으면 관리자 화면이
     * 신고자 명부가 된다.
     *
     * @param reviewBlockedReason 그 후기가 지금 내려가 있으면 그 사유(대문자), 아니면 {@code null}.
     *        되살리기 버튼이 이것을 본다
     * @param reviewDeleted       쓴 사람이 지웠나. 지운 후기는 되살릴 수 없다
     */
    @Schema(name = "ReviewReport")
    public record ReportItem(long reviewReportId, long reviewId, long productId, String productName,
            int rating, String body, String reason, String status, OffsetDateTime createdAt,
            OffsetDateTime resolvedAt, String reviewBlockedReason, boolean reviewDeleted) {}

    @Schema(name = "ReviewReportPage")
    public record ReportResult(List<ReportItem> items, int page, int size, long total) {}

    /**
     * 그 상태의 신고. <b>접수된 것은 오래된 것부터</b> 나온다 — 먼저 들어온 것이 먼저 처리된다.
     * 처리된 것은 최근 것부터다.
     */
    public ReportResult findReports(long viewerId, ReviewReportStatus status, Paging paging) {
        // 남의 후기 하나. all 스코프에서만 덮인다 — 관리자만 연다(`V85`).
        if (!evaluator.decide(viewerId, "review", "moderate", Target.ownedBy(-1L)).allowed()) {
            throw new ShopException(ErrorCode.REVIEW_FORBIDDEN, "신고를 볼 권한이 없다");
        }

        // 정렬은 바인딩 변수로 못 넘긴다. 두 값 중 하나라 사용자 입력이 섞일 자리가 없다 —
        // `formatted` 가 아니라 `replace` 인 것은 텍스트 블록의 줄바꿈을 서식 문자열로 읽지 않게 하려는 것이다.
        String order = status == ReviewReportStatus.PENDING
                ? "rp.created_at asc, rp.review_report_id asc"
                : "rp.resolved_at desc, rp.review_report_id desc";

        List<ReportItem> items = jdbc.sql("""
                                select rp.review_report_id, rp.review_id, r.product_id,
                                       p.name as product_name, r.rating, r.body, rp.reason, rp.status,
                                       rp.created_at, rp.resolved_at, r.blocked_reason,
                                       (r.deleted_at is not null) as review_deleted
                                  from review_report rp
                                  join review r on r.review_id = rp.review_id
                                  join product p on p.product_id = r.product_id
                                 where rp.status = :status
                                 order by {order}
                                 limit :size offset :offset
                                """.replace("{order}", order))
                        .param("status", status.code())
                        .param("size", paging.size())
                        .param("offset", paging.page() * paging.size())
                        .query((rs, rowNum) -> new ReportItem(
                                rs.getLong("review_report_id"),
                                rs.getLong("review_id"),
                                rs.getLong("product_id"),
                                rs.getString("product_name"),
                                rs.getInt("rating"),
                                rs.getString("body"),
                                EnumValue.of(rs.getString("reason"), ReviewReason::of),
                                EnumValue.of(rs.getString("status"), ReviewReportStatus::of),
                                rs.getObject("created_at", OffsetDateTime.class),
                                rs.getObject("resolved_at", OffsetDateTime.class),
                                EnumValue.of(rs.getString("blocked_reason"), ReviewReason::of),
                                rs.getBoolean("review_deleted")))
                .list();

        long total = jdbc.sql("select count(*) from review_report where status = :status")
                .param("status", status.code())
                .query(Long.class)
                .single();
        return new ReportResult(items, paging.page(), paging.size(), total);
    }

    /** 사진 행 하나. 열쇠를 들고 있고 URL 은 부르는 쪽이 만든다 */
    private record PhotoRow(long reviewImageId, String objectKey, String thumbnailKey) {}

    /** 공개 목록의 후기에 사진을 붙인다. 원본과 썸네일 URL 을 둘 다 만든다 */
    private List<Item> attachPhotos(List<Item> items) {
        Map<Long, List<PhotoRow>> photos = photosOf(items.stream().map(Item::reviewId).toList());
        return items.stream()
                .map(item -> item.withPhotos(photos.getOrDefault(item.reviewId(), List.of()).stream()
                        .map(photo -> new Photo(photo.reviewImageId(), images.url(photo.thumbnailKey()),
                                images.url(photo.objectKey())))
                        .toList()))
                .toList();
    }

    /**
     * 그 쪽의 후기들에 달린 사진. <b>후기마다 부르지 않고 한 번에 읽는다</b> — 한 쪽이 열 개면 조회가 열한 번이 되고,
     * 그 모양은 쪽 크기만큼 는다.
     */
    private Map<Long, List<PhotoRow>> photosOf(List<Long> reviewIds) {
        if (reviewIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<PhotoRow>> grouped = new LinkedHashMap<>();
        jdbc.sql("""
                        select review_id, review_image_id, object_key, thumbnail_key
                          from review_image
                         where review_id in (:ids)
                         order by review_id, sort_no, review_image_id
                        """)
                .param("ids", reviewIds)
                .query((rs, rowNum) -> {
                    grouped.computeIfAbsent(rs.getLong("review_id"), id -> new ArrayList<>())
                            .add(new PhotoRow(rs.getLong("review_image_id"),
                                    rs.getString("object_key"), rs.getString("thumbnail_key")));
                    return null;
                })
                .list();
        return grouped;
    }

    /** 후기가 달린 상품. 후기 사진을 올린 응답의 `Location` 이 그 상품의 후기 목록을 가리킨다(`Q227`) */
    public long productIdOf(long reviewId) {
        return jdbc.sql("select product_id from review where review_id = :reviewId")
                .param("reviewId", reviewId)
                .query(Long.class)
                .single();
    }
}
