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

    /**
     * 받는 것과 저장하는 것이 같은 형식이다. <b>WebP 가 없는 것은 JDK 가 읽지도 쓰지도 못해서다</b>
     * (네이티브 라이브러리를 안 들였다 — 사용자 결정 2026-09-18).
     */
    private static final List<String> ALLOWED = List.of("image/jpeg", "image/png");

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
        long sellerId = sellerIdOf(productId);
        if (!evaluator.decide(actorUserId, "product", "update", Target.ofSeller(sellerId)).allowed()) {
            throw new ShopException(ErrorCode.PRODUCT_FORBIDDEN);
        }
        if (file.size() > MAX_BYTES) {
            throw new ShopException(ErrorCode.IMAGE_TOO_LARGE);
        }
        if (countOf(productId) >= MAX_IMAGES_PER_PRODUCT) {
            throw new ShopException(ErrorCode.IMAGE_LIMIT_REACHED);
        }

        byte[] bytes = file.bytes();
        String contentType = detect(bytes);
        BufferedImage source = read(bytes);

        String extension = contentType.equals("image/png") ? "png" : "jpg";
        // **키에 상품 번호를 안 넣는다**(media-rules.md 「두는 곳」). 이 키는 서명 URL 에
        // 그대로 실려 나가므로, 순번을 넣으면 사는 사람이 주소만 보고 상품 총량과
        // 증가 속도를 읽는다(identifier-rules.md 와 같은 이유). 어느 상품의 사진인지는
        // product_image 행이 답한다 — 키가 답할 일이 아니다.
        String folder = "product/" + UUID.randomUUID();
        String objectKey = folder + "/original." + extension;
        String thumbnailKey = folder + "/thumbnail.jpg";

        byte[] original = encode(source, extension);
        storage.put(Visibility.PUBLIC, objectKey, original, contentType);
        storage.put(Visibility.PUBLIC, thumbnailKey, encode(thumbnail(source), "jpg"), "image/jpeg");

        long id = jdbc.sql(INSERT_IMAGE)
                .param("productId", productId)
                .param("objectKey", objectKey)
                .param("thumbnailKey", thumbnailKey)
                .param("originalName", file.originalName())
                .param("contentType", contentType)
                .param("byteSize", (long) original.length)
                .query(Long.class)
                .single();

        return new Uploaded(id, objectKey, thumbnailKey);
    }

    /**
     * <b>내용으로 판별한다.</b> {@link ImageIO} 가 읽어 낸 형식 이름이 답이고,
     * 요청이 뭐라고 적었는지는 안 본다.
     */
    private String detect(byte[] bytes) {
        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes);
                ImageInputStream stream = ImageIO.createImageInputStream(in)) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                throw new ShopException(ErrorCode.IMAGE_TYPE_NOT_ALLOWED);
            }
            String format = readers.next().getFormatName().toLowerCase(Locale.ROOT);
            String contentType = switch (format) {
                case "jpeg", "jpg" -> "image/jpeg";
                case "png" -> "image/png";
                default -> null;
            };
            if (contentType == null || !ALLOWED.contains(contentType)) {
                throw new ShopException(ErrorCode.IMAGE_TYPE_NOT_ALLOWED);
            }
            return contentType;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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
    private byte[] encode(BufferedImage image, String extension) {
        boolean jpeg = extension.equals("jpg");
        BufferedImage target = image;
        if (jpeg && image.getColorModel().hasAlpha()) {
            BufferedImage opaque = new BufferedImage(
                    image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = opaque.createGraphics();
            g.drawImage(image, 0, 0, Color.WHITE, null);
            g.dispose();
            target = opaque;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(target, jpeg ? "jpeg" : extension, out)) {
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

    private long sellerIdOf(long productId) {
        return jdbc.sql("select seller_id from product where product_id = :id and deleted_at is null")
                .param("id", productId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new ShopException(ErrorCode.PRODUCT_NOT_FOUND));
    }
}
