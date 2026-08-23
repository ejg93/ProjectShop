package com.projectshop.shop.inquiry;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.projectshop.shop.auth.PermissionEvaluator;
import com.projectshop.shop.auth.PermissionEvaluator.Target;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;

import com.projectshop.shop.support.ListQuery.Paging;

/**
 * 문의를 관객별로 읽는다(청크 59).
 *
 * <h2>입구가 관객마다 다르다</h2>
 *
 * <p><b>record 를 둘로 갈랐다.</b> 공개 목록이 쓰는 {@link PublicEntry} 에는 <b>낸 사람을 실을
 * 칸이 아예 없다</b> — 하나로 두고 마스킹으로 가리면 새 컬럼을 더할 때 그 규칙에 넣는 것을
 * 빠뜨려서 조용히 샌다(`D23` 「어느 쪽을 언제 쓰나」, `ProductQuery` 와 같은 판단).
 *
 * <p>여기서는 정도가 아니라 <b>행 자체</b>가 갈린다. 비공개 문의는 남에게 「있다」는 사실도
 * 안 보여야 해서, 숨기는 조건이 아니라 <b>고르는 조건</b>으로 쓴다 —
 * 조건을 빠뜨리면 결과가 더 나오는 것이 아니라 <b>빈다.</b>
 *
 * <h2>판정을 다시 쓰지 않는다</h2>
 *
 * <p>관객별 입구라 각 메서드가 스코프 하나만 쓴다. 판정 엔진에게 <b>그 스코프가 열렸나</b>만
 * 묻고, 열렸으면 그 스코프의 조건으로 고른다({@code RefundQuery.visibleFor} 와 같은 모양).
 */
@Component
public class InquiryQuery {

    private static final String RESOURCE = "inquiry";
    private static final String READ = "read";

    // 정렬을 안 받는다(`D5` 는 목록에 정렬을 열라고 하지 않는다).
    //
    // 문의는 최신순 말고 볼 순서가 없다 — 상태로 정렬하면 「답변됨」이 위로 몰려서
    // 아직 답 안 된 것이 아래로 밀린다. 필요해지면 그때 `ListQuery.orderBy` 를 태운다.

    private final JdbcClient jdbc;
    private final PermissionEvaluator evaluator;

    InquiryQuery(JdbcClient jdbc, PermissionEvaluator evaluator) {
        this.jdbc = jdbc;
        this.evaluator = evaluator;
    }

    /**
     * 상품 화면이 쓰는 한 줄. <b>낸 사람이 없다.</b>
     *
     * <p>공개 Q&A 는 아무나 보는 자리라 「누가 물었나」를 실으면 그 사람이 무엇을 샀는지가
     * 드러난다 — 질문 내용이 그 답을 하기 때문이다.
     */
    public record PublicEntry(String inquiryNumber, String question, String answer,
            String status, OffsetDateTime createdAt, OffsetDateTime answeredAt) {}

    /** 자기 것이거나 자기 셀러 것을 볼 때 쓰는 한 줄. 대상과 공개 여부가 같이 나간다 */
    public record Entry(String inquiryNumber, String kind, Long productId, String productName,
            String question, String answer, String status, boolean isPublic,
            OffsetDateTime createdAt, OffsetDateTime answeredAt,
            OffsetDateTime dueAt, boolean overdue) {}

    /** 목록 규약(`D5`). 셋 다 같은 봉투를 쓴다 */
    public record Page<T>(List<T> items, int page, int size, long total) {}

    /**
     * 상품 하나의 공개 문의.
     *
     * <p><b>판정을 안 지난다.</b> 비로그인도 보는 자리라 물을 사람이 없다 —
     * 상품 상세가 열려 있는 것과 같은 자리다(`D2` R1).
     *
     * <p><b>내려간 게시물이 빠진다</b>(`R34`, 정보통신망법 제50조의7). 거부하면 게시가
     * 중단돼야 하는데 조건에서 빼지 않으면 화면에만 안 보이고 이 API 로는 그대로 나간다.
     */
    public Page<PublicEntry> findPublic(long productId, int page, int size) {
        Paging paging = Paging.of(page, size);

        List<PublicEntry> items = jdbc.sql("""
                        select i.inquiry_number, i.question, i.answer, i.status,
                               i.created_at, i.answered_at
                          from inquiry i
                         where i.product_id = :productId
                           and i.is_public
                           and i.status in ('received', 'answered')
                         order by i.created_at desc, i.inquiry_id desc
                         limit :size offset :offset
                        """)
                .param("productId", productId)
                .param("size", paging.size())
                .param("offset", paging.offset())
                .query((rs, rowNum) -> new PublicEntry(
                        rs.getString("inquiry_number"),
                        rs.getString("question"),
                        rs.getString("answer"),
                        enumValue(rs.getString("status")),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("answered_at", OffsetDateTime.class)))
                .list();

        Long total = jdbc.sql("""
                        select count(*) from inquiry i
                         where i.product_id = :productId
                           and i.is_public
                           and i.status in ('received', 'answered')
                        """)
                .param("productId", productId)
                .query(Long.class)
                .single();

        return new Page<>(items, paging.page(), paging.size(), total);
    }

    /**
     * 내가 낸 문의 전부. 비공개도 보이고 내려간 것도 보인다.
     *
     * <p><b>내려간 것을 숨기지 않는다.</b> 자기 글이 왜 안 보이는지를 본인이 알아야 하고,
     * 정보통신망법 제50조의7 은 <b>게시 중단</b>을 요구하지 작성자에게서 감추라고 하지 않는다.
     */
    public Page<Entry> findMine(long viewerId, int page, int size) {
        requireScope(viewerId, Target.ownedBy(viewerId), "자기 문의를 볼 권한이 없다");

        return find("i.user_id = :viewerId", Map.of("viewerId", viewerId), page, size);
    }

    /**
     * 내 셀러 상품에 달린 문의.
     *
     * <p><b>계정에 붙는 요구는 여기 안 나온다.</b> 조건이 상품의 셀러라 처리정지·이의제기·분쟁은
     * 애초에 안 걸린다 — 그 요구는 우리에게 온 것이라 셀러가 볼 것이 아니고,
     * 그 사실이 조건 하나로 성립한다.
     */
    public Page<Entry> findForSeller(long viewerId, int page, int size) {
        Set<Long> sellers = sellersOpenTo(viewerId);
        if (sellers.isEmpty()) {
            throw new ShopException(ErrorCode.INQUIRY_FORBIDDEN, "셀러 문의를 볼 권한이 없다");
        }

        return find("coalesce(p.seller_id, so.seller_id) = any(:sellers)",
                Map.of("sellers", sellers.toArray(Long[]::new)), page, size);
    }

    /**
     * 조건만 갈아 끼우고 나머지는 같다.
     *
     * <p>조건 문자열은 <b>이 파일 안의 리터럴</b>이라 바깥에서 오는 값이 없다(`D23` 「SQL」).
     * 값은 전부 이름 붙은 파라미터로 간다.
     */
    private Page<Entry> find(String condition, Map<String, Object> params, int page, int size) {
        Paging paging = Paging.of(page, size);

        var listing = jdbc.sql("""
                        select i.inquiry_number, i.kind, i.product_id, p.name as product_name,
                               i.question, i.answer, i.status, i.is_public,
                               i.created_at, i.answered_at, i.due_at,
                               (i.status = 'received' and i.due_at < now()) as overdue
                          from inquiry i
                          left join product p on p.product_id = i.product_id
                          left join seller_order so on so.seller_order_id = i.seller_order_id
                         where %s
                         order by i.created_at desc, i.inquiry_id desc
                         limit :size offset :offset
                        """.formatted(condition))
                .param("size", paging.size())
                .param("offset", paging.offset());

        var counting = jdbc.sql("""
                        select count(*)
                          from inquiry i
                          left join product p on p.product_id = i.product_id
                          left join seller_order so on so.seller_order_id = i.seller_order_id
                         where %s
                        """.formatted(condition));

        for (Map.Entry<String, Object> param : params.entrySet()) {
            listing = listing.param(param.getKey(), param.getValue());
            counting = counting.param(param.getKey(), param.getValue());
        }

        List<Entry> items = listing
                .query((rs, rowNum) -> new Entry(
                        rs.getString("inquiry_number"),
                        enumValue(rs.getString("kind")),
                        (Long) rs.getObject("product_id"),
                        rs.getString("product_name"),
                        rs.getString("question"),
                        rs.getString("answer"),
                        enumValue(rs.getString("status")),
                        rs.getBoolean("is_public"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("answered_at", OffsetDateTime.class),
                        rs.getObject("due_at", OffsetDateTime.class),
                        rs.getBoolean("overdue")))
                .list();

        return new Page<>(items, paging.page(), paging.size(), counting.query(Long.class).single());
    }

    /**
     * 그 스코프가 열렸나만 묻는다.
     *
     * <p><b>못 봄이 0건이 아니다</b> — 0건과 못 봄이 갈려야 개수로 정보가 안 샌다
     * ({@code RefundQuery} 와 같은 판단).
     */
    private void requireScope(long viewerId, Target target, String message) {
        if (!evaluator.decide(viewerId, RESOURCE, READ, target).allowed()) {
            throw new ShopException(ErrorCode.INQUIRY_FORBIDDEN, message);
        }
    }

    /** 소속이면서 조회가 열린 셀러. 소속만으로는 안 되고 판정이 열어 줘야 한다 */
    private Set<Long> sellersOpenTo(long viewerId) {
        Set<Long> memberOf = jdbc.sql("select seller_id from seller_member where user_id = :id")
                .param("id", viewerId)
                .query(Long.class)
                .set();

        return memberOf.stream()
                .filter(sellerId -> evaluator
                        .decide(viewerId, RESOURCE, READ, Target.ofSeller(sellerId)).allowed())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** 열거값은 대문자 스네이크로 올린다(`D5`). 저장값과 다르다 */
    private static String enumValue(String stored) {
        return stored == null ? null : stored.toUpperCase(Locale.ROOT);
    }
}
