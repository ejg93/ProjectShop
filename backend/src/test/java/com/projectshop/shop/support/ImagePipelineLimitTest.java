package com.projectshop.shop.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;

/**
 * 사진 디코딩 화소 상한(`Q351`, {@code media-rules.md} 「받는 것 — 제한값」).
 *
 * <p><b>파일 크기 상한은 디코딩 메모리를 못 막는다</b> — 단색 PNG 는 5 MiB 안에 십억 화소가 든다.
 * 입력은 <b>헤더만 든 PNG</b>다. 상한이 {@code ImageIO.read} 앞에 서 있으면 풀 픽셀이 없어도 413 이고,
 * 상한이 없으면 디코딩까지 가서 다른 오류가 난다 — 그 차이가 이 시험이 재는 것이다.
 *
 * <p>DB·저장소를 안 띄운다. 저장소는 아무것도 안 하는 하위 클래스다.
 */
@DisplayName("사진 디코딩 화소 상한")
class ImagePipelineLimitTest {

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private final ImagePipeline pipeline = new ImagePipeline(new ObjectStorage(null, null, "public", "private") {
        @Override
        public void put(Visibility visibility, String key, byte[] body, String contentType) {
            // 저장하지 않는다 — 판정만 본다
        }
    });

    @ParameterizedTest(name = "{0}×{1}")
    @CsvSource({"6000, 6000", "1, 30000001", "65535, 65535"})
    @DisplayName("폭×높이가 3천만을 넘으면 디코딩 전에 413 이다")
    void rejectsOverPixelCapBeforeDecoding(int width, int height) {
        byte[] header = pngHeader(width, height);

        assertThatThrownBy(() -> pipeline.store("product", new ImagePipeline.Incoming("big.png", header)))
                .isInstanceOfSatisfying(ShopException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.IMAGE_TOO_LARGE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"png", "jpeg"})
    @DisplayName("작은 진짜 PNG·JPEG 는 받는다")
    void acceptsSmallImages(String format) {
        String name = "small." + ("jpeg".equals(format) ? "jpg" : format);

        ImagePipeline.Stored stored = pipeline.store("product", new ImagePipeline.Incoming(name, image(40, 30, format)));

        assertThat(stored.objectKey()).startsWith("product/");
    }

    @Test
    @DisplayName("헤더가 잘린 PNG 는 서버 오류가 아니라 받을 수 없는 형식이다")
    void truncatedHeaderIsNotAllowedType() {
        byte[] truncated = Arrays.copyOf(pngHeader(6000, 6000), PNG_SIGNATURE.length + 10);

        assertThatThrownBy(() -> pipeline.store("product", new ImagePipeline.Incoming("cut.png", truncated)))
                .isInstanceOfSatisfying(ShopException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.IMAGE_TYPE_NOT_ALLOWED));
    }

    @Test
    @DisplayName("헤더는 멀쩡하고 몸통이 깨진 PNG 도 서버 오류가 아니라 받을 수 없는 형식이다")
    void brokenBodyIsNotAllowedType() {
        byte[] headerOnly = pngHeader(100, 100);

        assertThatThrownBy(() -> pipeline.store("product", new ImagePipeline.Incoming("body.png", headerOnly)))
                .isInstanceOfSatisfying(ShopException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.IMAGE_TYPE_NOT_ALLOWED));
    }

    /** 시그니처 8 바이트와 IHDR 청크 하나. IDAT 가 없어서 픽셀은 하나도 없다 */
    private static byte[] pngHeader(int width, int height) {
        byte[] type = "IHDR".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer data = ByteBuffer.allocate(13)
                .putInt(width).putInt(height)
                .put((byte) 8)   // 비트 깊이
                .put((byte) 2)   // 색 형식: RGB
                .put((byte) 0).put((byte) 0).put((byte) 0);
        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(data.array());

        return ByteBuffer.allocate(PNG_SIGNATURE.length + 4 + 4 + 13 + 4)
                .put(PNG_SIGNATURE)
                .putInt(13)
                .put(type)
                .put(data.array())
                .putInt((int) crc.getValue())
                .array();
    }

    private static byte[] image(int width, int height, String format) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, format, out);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }
}
