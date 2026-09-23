package com.projectshop.shop.support;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.UUID;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;

import org.springframework.stereotype.Component;

import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.support.ObjectStorage.Visibility;

/**
 * 올라온 사진을 받을 수 있는 모양으로 바꾸고 저장소에 둔다(`Q159`, {@code media-rules.md}).
 *
 * <p><b>상품 사진(`27`)에서 뺐다.</b> 후기 사진이 같은 규칙을 받아야 해서다 — 자원마다 두면 판별과 재인코딩이
 * 두 벌이 되고, 한쪽만 고치는 날 EXIF 가 그쪽으로 샌다. <b>표와 판정은 자원 쪽에 남는다</b>(`D23`
 * 「자원 단위로 판다」) — 여기는 무엇의 사진인지 모른다.
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
@Component
public class ImagePipeline {

    /** 값의 출처는 {@code media-rules.md} 「받는 것 — 제한값」이다. 고칠 때 각 표의 {@code check} 도 같이 고친다 */
    public static final long MAX_BYTES = 5L * 1024 * 1024;

    /**
     * 주인 하나(상품 하나·후기 하나)당 장수. 후기도 상품과 <b>같은 값을 쓴다</b>(사용자 선택, `Q159`) —
     * 세지 않은 값을 하나 더 만들지 않았다. 고칠 때 {@code media-rules.md} 와 각 표의 트리거를 같이 고친다.
     */
    public static final int MAX_IMAGES_PER_OWNER = 10;

    /** 긴 변. 비율은 원본을 지키고 잘라내지 않는다 */
    private static final int THUMBNAIL_LONG_EDGE = 600;

    private final ObjectStorage storage;

    ImagePipeline(ObjectStorage storage) {
        this.storage = storage;
    }

    /**
     * 올라온 파일 하나. <b>웹 타입을 여기까지 안 들인다</b>({@code D23} 「계층」) —
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
     * 저장소에 둔 사진 하나. 행에 쓸 값만 든다.
     *
     * @param contentType 내용으로 판별한 저장값({@code image/jpeg}·{@code image/png})
     * @param byteSize    다시 인코딩한 원본의 크기. 받은 크기가 아니다
     */
    public record Stored(String objectKey, String thumbnailKey, String originalName,
            String contentType, long byteSize) {}

    /**
     * 받아서 판별하고, 다시 인코딩하고, 썸네일을 만들어 <b>공개 버킷</b>에 둔다.
     *
     * <p><b>저장소에 먼저 넣고 행을 나중에 쓴다</b> — 부르는 쪽의 순서다. 뒤집으면 넣기가 실패했을 때
     * 없는 파일을 가리키는 행이 남고, 그 행은 목록에서 깨진 그림이 된다. 반대 방향의 사고(행이 안 써져서
     * 주인 없는 객체가 남는 것)는 <b>화면에 안 보인다</b> — 둘 중 하나를 골라야 하면 안 보이는 쪽이 낫다.
     *
     * @param resource 열쇠의 앞머리({@code product}·{@code review}). <b>주인 번호를 열쇠에 안 넣는다</b> —
     *        서명 URL 에 그대로 실려 나가서, 순번을 넣으면 주소만 보고 총량과 증가 속도를 읽는다
     */
    public Stored store(String resource, Incoming file) {
        if (file.size() > MAX_BYTES) {
            throw new ShopException(ErrorCode.IMAGE_TOO_LARGE);
        }

        byte[] bytes = file.bytes();
        ImageContentType contentType = detect(bytes);
        requireMatchingExtension(file.originalName(), contentType);
        BufferedImage source = read(bytes);

        ImageContentType thumbnailType = ImageContentType.JPEG;
        String folder = resource + "/" + UUID.randomUUID();
        String objectKey = folder + "/original." + contentType.extension();
        // 썸네일은 원본 형식과 무관하게 JPEG 다 — 투명도를 버리는 대신 크기가 작다.
        // **열쇠의 꼬리도 그 형식에서 뽑는다** — 글자로 박아 두면 확장자를 바꾸는 날 열쇠만 옛 값으로 남는다.
        String thumbnailKey = folder + "/thumbnail." + thumbnailType.extension();

        byte[] original = encode(source, contentType);
        storage.put(Visibility.PUBLIC, objectKey, original, contentType.code());
        storage.put(Visibility.PUBLIC, thumbnailKey,
                encode(thumbnail(source), thumbnailType), thumbnailType.code());

        return new Stored(objectKey, thumbnailKey, file.originalName(), contentType.code(), original.length);
    }

    /**
     * 원본과 썸네일을 저장소에서 지운다. <b>행을 지우기 전에 부른다</b> — 행을 먼저 지우면 열쇠를 잃어서
     * 지울 대상을 못 찾는다({@code Q94} 의 게시 중단과 같은 순서).
     */
    public void delete(String objectKey, String thumbnailKey) {
        storage.delete(Visibility.PUBLIC, objectKey);
        storage.delete(Visibility.PUBLIC, thumbnailKey);
    }

    /** 공개 버킷의 열쇠를 만료 5분 서명 URL 로 바꾼다 */
    public String url(String key) {
        return storage.presignedUrl(key);
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
     * <p>내용이 PNG 인데 이름이 {@code .jpg} 면 우리는 PNG 로 저장하고 {@code original_name} 에는
     * {@code .jpg} 가 남는다 — <b>내려받은 사람이 연 파일과 이름이 어긋난다.</b>
     *
     * <p><b>이름을 검증의 입력으로 쓰는 것이 아니다.</b> 형식은 이미 내용이 정했고({@link #detect}),
     * 여기서는 <b>이름이 그것과 다른지</b>만 본다. DB 로 못 내리는 것은 두 칸의 <b>관계</b>라서다 —
     * {@code check} 로 쓰면 확장자 목록을 SQL 에 박게 된다.
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
}
