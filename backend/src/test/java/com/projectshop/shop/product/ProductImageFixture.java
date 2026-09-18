package com.projectshop.shop.product;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/** 시험이 올릴 진짜 JPEG 를 만든다({@code 27}) — 바이트가 이미지여야 판별을 지난다 */
final class ProductImageFixture {

    private ProductImageFixture() {
    }

    static byte[] jpegBytes(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "jpeg", out);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }
}
