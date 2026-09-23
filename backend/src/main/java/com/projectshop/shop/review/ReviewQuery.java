package com.projectshop.shop.review;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

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

    ReviewQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 후기 한 줄.
     *
     * @param mine 지금 보는 사람이 쓴 것인가. <b>안 로그인이면 전부 거짓</b>이다.
     *        <b>지금 이 값을 쓰는 화면은 상품 상세의 라벨 하나뿐이고</b>, 그 화면은
     *        {@code apiPublic} 으로 부르므로 쿠키가 안 실려 <b>언제나 거짓</b>이다 —
     *        고치기·지우기 자리는 {@code Q167} 이 세우고 그때 세션을 싣는 입구로 옮긴다.
     *        <b>여기서 계약을 안 지운다</b>: 지우면 그 청크가 서버부터 다시 연다
     */
    @Schema(name = "ProductReview")
    public record Item(long reviewId, String writerName, int rating, String body,
            String reply, OffsetDateTime createdAt, boolean mine) {}

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
                                viewerUserId != null && rs.getLong("user_id") == viewerUserId))
                .list();

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
}
