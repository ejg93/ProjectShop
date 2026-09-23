package com.projectshop.shop.product;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectshop.shop.auth.PermissionEvaluator;
import com.projectshop.shop.auth.PermissionEvaluator.Target;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.support.ObjectStorage;
import com.projectshop.shop.support.ObjectStorage.Visibility;

/**
 * 셀러가 자기 상품에 사진을 올린다({@code 27}, {@code media-rules.md}).
 *
 * <h2>받은 바이트를 그대로 저장하지 않는다</h2>
 *
 * <p>읽어서 <b>다시 인코딩한다.</b> 그래야 EXIF 가 안 따라온다 — 촬영 위치와 시각은 개인정보고,
 * 지울 항목을 열거하는 대신 다시 쓰면 <b>새 항목이 생겨도 안 샌다</b>.
 *
 * <p>덤으로 <b>형식 판별이 내용으로 된다.</b> 요청 헤더의 {@code Content-Type} 은 보내는 쪽이
 * 자유롭게 적는 값이라 검증의 입력이 될 수 없다(OWASP).
 *
 * <h2>썸네일을 못 만들면 업로드가 통째로 실패한다</h2>
 *
 * <p>원본만 남기면 「썸네일 없는 이미지」라는 상태가 생기고 목록 화면이 그것을 또 다뤄야 한다.
 * <b>실패를 한 곳에 모으면</b> 화면이 다룰 것은 이미 있는 「업로드 실패」 하나뿐이다.
 */
@Service
public class ProductImageService {

    /** 값의 출처는 {@code media-rules.md} 「받는 것 — 제한값」이다. 고칠 때 DB 제약도 같이 고친다 */
    private static final long MAX_BYTES = 5L * 1024 * 1024;

    private static final int MAX_IMAGES_PER_PRODUCT = 10;

    /** 긴 변. 비율은 원본을 지키고 잘라내지 않는다 */
    private static final int THUMBNAIL_LONG_EDGE = 600;

    // 받는 것과 저장하는 것이 같은 형식이고, 그 목록은 `ImageContentType` 이 든다(`Q120`).

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
    private final ObjectStorage storage;

    ProductImageService(JdbcClient jdbc, PermissionEvaluator evaluator, ObjectStorage storage) {
        this.jdbc = jdbc;
        this.evaluator = evaluator;
        this.storage = storage;
    }

    public record Uploaded(long productImageId, String objectKey, String thumbnailKey) {
    }

    /**
     * 올라온 파일 하나. <b>웹 타입을 여기까지 안 들인다</b>({ D23} 「계층」) —
     * {@code MultipartFile} 을 서비스가 받으면 이 규칙을 HTTP 가 아닌 자리(배치·이관)에서
     * 다시 쓸 수 없고, 계층 검사가 그것을 막는다.
     *
     * @param originalName 사람에게 보여 줄 이름. 표시에만 쓰고 저장 위치를 정하는 데 안 쓴다
     */
    public record Incoming(String originalName, byte[] bytes) {

        public long size() {
            return bytes.length;
        }
    }

    /**
     * <b>저장소에 먼저 넣고 행을 나중에 쓴다.</b> 순서를 뒤집으면 넣기가 실패했을 때
     * 없는 파일을 가리키는 행이 남는다 — 그 행은 목록에서 깨진 그림이 된다.
     *
     * <p>반대 방향의 사고(행이 안 써져서 주인 없는 객체가 남는 것)는 <b>화면에 안 보인다</b>.
     * 둘 중 하나를 골라야 하면 안 보이는 쪽이 낫다.
     */
    @Transactional
    public Uploaded upload(long actorUserId, long productId, Incoming file) {
        if (!evaluator.decide(actorUserId, "product", "update", targetOf(productId)).allowed()) {
            throw new ShopException(ErrorCode.PRODUCT_FORBIDDEN);
        }
        if (file.size() > MAX_BYTES) {
            throw new ShopException(ErrorCode.IMAGE_TOO_LARGE);
        }
        if (countOf(productId) >= MAX_IMAGES_PER_PRODUCT) {
            throw new ShopException(ErrorCode.IMAGE_LIMIT_REACHED);
        }

        byte[] bytes = file.bytes();
        ImageContentType contentType = detect(bytes);
        requireMatchingExtension(file.originalName(), contentType);
        BufferedImage source = read(bytes);

        ImageContentType thumbnailType = ImageContentType.JPEG;
        // **키에 상품 번호를 안 넣는다**(media-rules.md 「두는 곳」). 이 키는 서명 URL 에
        // 그대로 실려 나가므로, 순번을 넣으면 사는 사람이 주소만 보고 상품 총량과
        // 증가 속도를 읽는다(identifier-rules.md 와 같은 이유). 어느 상품의 사진인지는
        // product_image 행이 답한다 — 키가 답할 일이 아니다.
        String folder = "product/" + UUID.randomUUID();
        String objectKey = folder + "/original." + contentType.extension();
        // 썸네일은 원본 형식과 무관하게 JPEG 다 — 투명도를 버리는 대신 크기가 작다.
        // **열쇠의 꼬리도 그 형식에서 뽑는다**(마무리 34차 독립 리뷰) — 글자로 박아 두면
        // 확장자를 바꾸는 날 열쇠만 옛 값으로 남는다.
        String thumbnailKey = folder + "/thumbnail." + thumbnailType.extension();

        byte[] original = encode(source, contentType);
        storage.put(Visibility.PUBLIC, objectKey, original, contentType.code());
        storage.put(Visibility.PUBLIC, thumbnailKey,
                encode(thumbnail(source), thumbnailType), thumbnailType.code());

        long id = jdbc.sql(INSERT_IMAGE)
                .param("productId", productId)
                .param("objectKey", objectKey)
                .param("thumbnailKey", thumbnailKey)
                .param("originalName", file.originalName())
                .param("contentType", contentType.code())
                .param("byteSize", (long) original.length)
                .query(Long.class)
                .single();

        return new Uploaded(id, objectKey, thumbnailKey);
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
     * <p><b>내린 상품은 안 준다.</b> {@link #sellerIdOf} 를 그대로 쓰므로 {@code deleted_at} 이
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
                        storage.presignedUrl(rs.getString("thumbnail_key")),
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
     *
     * <p><b>저장소를 먼저 지운다.</b> 행을 먼저 지우면 열쇠를 잃어서 지울 대상을 못 찾는다 —
     * {@code Q94} 의 게시 중단과 같은 순서다.
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

        storage.delete(Visibility.PUBLIC, owned.objectKey());
        storage.delete(Visibility.PUBLIC, owned.thumbnailKey());

        jdbc.sql("delete from product_image where product_image_id = :id")
                .param("id", productImageId)
                .update();
    }

    /**
     * <b>내용으로 판별한다.</b> {@link ImageIO} 가 읽어 낸 형식 이름이 답이고,
     * 요청이 뭐라고 적었는지는 안 본다.
     */
    private ImageContentType detect(byte[] bytes) {
        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes);
                ImageInputStream stream = ImageIO.createImageInputStream(in)) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                throw new ShopException(ErrorCode.IMAGE_TYPE_NOT_ALLOWED);
            }
            ImageContentType contentType =
                    ImageContentType.ofFormat(readers.next().getFormatName());
            if (contentType == null) {
                throw new ShopException(ErrorCode.IMAGE_TYPE_NOT_ALLOWED);
            }
            return contentType;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 이름의 확장자가 <b>내용에서 나온 형식과 짝인가</b>({@code Q96}, {@code media-rules.md} 검사 2).
     *
     * <h2>왜 이름까지 보나</h2>
     *
     * <p>내용이 PNG 인데 이름이 {@code .jpg} 면 우리는 PNG 로 저장하고 {@code original_name} 에는
     * {@code .jpg} 가 남는다 — <b>내려받은 사람이 연 파일과 이름이 어긋난다.</b> 그리고
     * <b>이름을 믿는 다음 코드</b>(내려받기 헤더·이관 스크립트)가 그 어긋남을 물려받는다.
     *
     * <p><b>이름을 검증의 입력으로 쓰는 것이 아니다.</b> 형식은 이미 내용이 정했고
     * ({@link #detect}), 여기서는 <b>이름이 그것과 다른지</b>만 본다.
     *
     * <h2>DB 로 못 내린다</h2>
     *
     * <p>{@code original_name} 과 {@code content_type} 두 칸의 <b>관계</b>라
     * {@code check} 로 쓰면 확장자 목록을 SQL 에 박게 된다 — 형식을 하나 더 받는 날
     * 고칠 자리가 하나 는다.
     */
    private void requireMatchingExtension(String originalName, ImageContentType contentType) {
        int dot = originalName == null ? -1 : originalName.lastIndexOf('.');
        if (dot < 0 || dot == originalName.length() - 1) {
            throw new ShopException(ErrorCode.IMAGE_TYPE_NOT_ALLOWED);
        }

        String extension = originalName.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!contentType.matchesName(extension)) {
            throw new ShopException(ErrorCode.IMAGE_TYPE_NOT_ALLOWED);
        }
    }

    private BufferedImage read(byte[] bytes) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                throw new ShopException(ErrorCode.IMAGE_TYPE_NOT_ALLOWED);
            }
            return image;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private BufferedImage thumbnail(BufferedImage source) {
        int longEdge = Math.max(source.getWidth(), source.getHeight());
        double scale = Math.min(1.0, (double) THUMBNAIL_LONG_EDGE / longEdge);
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));

        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(source, 0, 0, width, height, Color.WHITE, null);
        g.dispose();
        return scaled;
    }

    /**
     * <b>JPEG 에는 알파 채널이 없다.</b> 알파가 있는 이미지를 그대로 JPEG 로 쓰면
     * 색이 뒤집힌 그림이 나온다 — 흰 바탕에 눌러 두고 쓴다.
     */
    private byte[] encode(BufferedImage image, ImageContentType type) {
        BufferedImage target = image;
        if (type.opaqueOnly() && image.getColorModel().hasAlpha()) {
            BufferedImage opaque = new BufferedImage(
                    image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = opaque.createGraphics();
            g.drawImage(image, 0, 0, Color.WHITE, null);
            g.dispose();
            target = opaque;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(target, type.imageIoName(), out)) {
                throw new ShopException(ErrorCode.IMAGE_TYPE_NOT_ALLOWED);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
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
