package com.projectshop.shop.product;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectshop.shop.auth.PermissionEvaluator;
import com.projectshop.shop.auth.PermissionEvaluator.Target;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.support.ImagePipeline;

/**
 * 셀러가 자기 상품에 사진을 올린다({@code 27}, {@code media-rules.md}).
 *
 * <p><b>받은 바이트를 어떻게 다루나는 {@link ImagePipeline} 이 든다</b>(`Q159`) — 판별·재인코딩·썸네일·저장이
 * 후기 사진과 같은 규칙이라 거기로 뺐다. 여기 남은 것은 <b>누가 어느 상품에 올리나</b>(판정)와 <b>행</b>이다.
 */
@Service
public class ProductImageService {

    /**
     * 상품 하나당 장수. 값은 공용 상한과 같다 — {@code V80} 의 트리거가 이 이름을 부른다.
     * 고칠 때 {@code media-rules.md} 와 그 트리거를 같이 고친다.
     */
    private static final int MAX_IMAGES_PER_PRODUCT = ImagePipeline.MAX_IMAGES_PER_OWNER;

    private static final String INSERT_IMAGE = """
            insert into product_image
                (product_id, object_key, thumbnail_key, original_name,
                 content_type, byte_size, sort_no)
            values (:productId, :objectKey, :thumbnailKey, :originalName,
                    :contentType, :byteSize,
                    coalesce((select max(sort_no) + 1 from product_image
                              where product_id = :productId), 0))
            returning product_image_id
            """;

    private final JdbcClient jdbc;
    private final PermissionEvaluator evaluator;
    private final ImagePipeline images;

    ProductImageService(JdbcClient jdbc, PermissionEvaluator evaluator, ImagePipeline images) {
        this.jdbc = jdbc;
        this.evaluator = evaluator;
        this.images = images;
    }

    public record Uploaded(long productImageId, String objectKey, String thumbnailKey) {
    }

    /**
     * <b>저장소에 먼저 넣고 행을 나중에 쓴다</b> — 까닭은 {@link ImagePipeline#store} 가 든다.
     */
    @Transactional
    public Uploaded upload(long actorUserId, long productId, ImagePipeline.Incoming file) {
        if (!evaluator.decide(actorUserId, "product", "update", targetOf(productId)).allowed()) {
            throw new ShopException(ErrorCode.PRODUCT_FORBIDDEN);
        }
        if (countOf(productId) >= MAX_IMAGES_PER_PRODUCT) {
            throw new ShopException(ErrorCode.IMAGE_LIMIT_REACHED);
        }

        ImagePipeline.Stored stored = images.store("product", file);

        long id = jdbc.sql(INSERT_IMAGE)
                .param("productId", productId)
                .param("objectKey", stored.objectKey())
                .param("thumbnailKey", stored.thumbnailKey())
                .param("originalName", stored.originalName())
                .param("contentType", stored.contentType())
                .param("byteSize", stored.byteSize())
                .query(Long.class)
                .single();

        return new Uploaded(id, stored.objectKey(), stored.thumbnailKey());
    }


    /**
     * 판매자가 자기 상품의 사진을 훑는다(`Q139`).
     *
     * <p><b>공개 상세와 주는 것이 다르다.</b> 저쪽은 서명 URL 목록만 주는데(`ProductQuery.findImageUrls`)
     * 그것으로는 <b>지울 수가 없다</b> — 지우는 입구가 {@code productImageId} 를 받고, 그 번호를
     * 판매자에게 내주는 자리가 어디에도 없었다. 그래서 사진을 올리는 화면을 만들 수가 없었다.
     *
     * <p><b>판정은 {@link #delete} 와 같은 규칙이다</b> — `product:update` 를 그 셀러 범위로 가졌나.
     * 사진을 보는 것과 지우는 것을 다른 권한으로 가르면, 보이는데 못 지우는 줄이 화면에 생긴다.
     *
     * <p><b>내린 상품은 안 준다.</b> {@link #targetOf} 를 그대로 쓰므로 {@code deleted_at} 이
     * 찬 상품은 {@code PRODUCT_NOT_FOUND} 다 — {@code upload} 와 {@code ProductQuery.findForSeller}
     * 가 이미 그 규칙이고, 여기만 열어 두면 <b>올리지도 못하는 상품의 사진이 목록에만 뜬다.</b>
     *
     * <p><b>처음엔 열어 뒀다가 독립 리뷰가 짚어서 닫았다</b>(마무리 39차) — 「내린 상품의 사진을
     * 정리한다」는 이유를 댔는데 <b>거기 닿는 화면 경로가 없었다.</b> 정리할 자리가 생기면
     * 그때 세 자리를 같이 연다.
     */
    @Transactional(readOnly = true)
    public List<Image> find(long actorUserId, long productId) {
        if (!evaluator.decide(actorUserId, "product", "update", targetOf(productId)).allowed()) {
            throw new ShopException(ErrorCode.PRODUCT_FORBIDDEN);
        }

        return jdbc.sql("""
                        select product_image_id, thumbnail_key, original_name, sort_no
                          from product_image
                         where product_id = :id
                         order by sort_no, product_image_id
                        """)
                .param("id", productId)
                .query((rs, n) -> new Image(
                        rs.getLong("product_image_id"),
                        images.url(rs.getString("thumbnail_key")),
                        rs.getString("original_name"),
                        rs.getInt("sort_no")))
                .list();
    }

    /**
     * 목록 한 줄. <b>원본이 아니라 썸네일 URL 이다</b> — 관리 화면은 격자로 훑는 자리라
     * 원본을 열 장 내려받을 이유가 없다({@code media-rules.md} 「썸네일 — 업로드 때 한 장」).
     *
     * @param thumbnailUrl 만료 5분 서명 URL. 버킷은 비공개다
     */
    public record Image(long productImageId, String thumbnailUrl, String originalName, int sortNo) {
    }

    /**
     * 셀러가 자기 상품의 사진을 지운다({@code Q95}).
     *
     * <h2>저장소까지 간다</h2>
     *
     * <p>행만 지우고 객체를 두면 <b>주인 없는 파일</b>이 남고, 그 열쇠를 아는 사람에게는
     * 서명 URL 이 계속 나온다({@code media-rules.md} 「남의 것이 올라오면」과 같은 자리).
     * 저장소를 먼저 지우는 까닭은 {@link ImagePipeline#delete} 가 든다.
     *
     * <h2>상품 삭제가 이것을 안 대신한다</h2>
     *
     * <p>{@code ProductService.delete} 는 <b>소프트 삭제</b>({@code deleted_at})라 상품 행이
     * 안 사라지고, 따라서 {@code product_image} 의 {@code cascade} 도 안 돈다.
     * <b>파기 배치가 상품을 물리적으로 지우게 되는 날</b> 그쪽도 이 경로를 불러야 한다.
     */
    @Transactional
    public void delete(long actorUserId, long productImageId) {
        record Owned(long productId, long sellerId, long createdByUserId, String objectKey,
                String thumbnailKey) {
        }

        Owned owned = jdbc.sql("""
                        select i.product_id, p.seller_id, p.created_by_user_id,
                               i.object_key, i.thumbnail_key
                          from product_image i
                          join product p on p.product_id = i.product_id
                         where i.product_image_id = :id
                        """)
                .param("id", productImageId)
                .query(Owned.class)
                .optional()
                .orElseThrow(() -> new ShopException(ErrorCode.PRODUCT_NOT_FOUND));

        if (!evaluator.decide(actorUserId, "product", "update",
                new Target(owned.createdByUserId(), owned.sellerId(), null)).allowed()) {
            throw new ShopException(ErrorCode.PRODUCT_FORBIDDEN);
        }

        images.delete(owned.objectKey(), owned.thumbnailKey());

        jdbc.sql("delete from product_image where product_image_id = :id")
                .param("id", productImageId)
                .update();
    }

    private int countOf(long productId) {
        return jdbc.sql("select count(*) from product_image where product_id = :id")
                .param("id", productId)
                .query(Integer.class)
                .single();
    }

    /**
     * 판정에 필요한 이 상품의 두 값.
     *
     * <p><b>등록자를 같이 읽는다</b>(`Q161`). 셀러만 읽으면 {@code ownerUserId} 가 {@code null}
     * 이라 <b>{@code own} 스코프가 아무것도 안 덮는다</b> — 담당자가 자기가 등록한 상품의
     * 사진도 못 만진다.
     */
    private Target targetOf(long productId) {
        return jdbc.sql("""
                        select seller_id, created_by_user_id from product
                         where product_id = :id and deleted_at is null
                        """)
                .param("id", productId)
                .query((rs, rowNum) -> new Target(
                        rs.getLong("created_by_user_id"), rs.getLong("seller_id"), null))
                .optional()
                .orElseThrow(() -> new ShopException(ErrorCode.PRODUCT_NOT_FOUND));
    }
}
