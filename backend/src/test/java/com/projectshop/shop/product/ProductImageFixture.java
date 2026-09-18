package com.projectshop.shop.product;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/** 시험이 올릴 진짜 JPEG 를 만든다({@code 27}) — 바이트가 이미지여야 판별을 지난다 */
final class ProductImageFixture {

    private ProductImageFixture() {
    }

    /** PNG 로 쓴다. 이름과 내용이 어긋나는 경우를 만들 때 쓴다({@code Q96}) */
    static byte[] pngBytes(int width, int height) {
        return bytes(width, height, "png");
    }

    static byte[] jpegBytes(int width, int height) {
        return bytes(width, height, "jpeg");
    }

    private static byte[] bytes(int width, int height, String format) {
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
