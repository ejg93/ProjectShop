package com.projectshop.shop.product;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * 상품 사진으로 받는 형식(`Q120`, {@code media-rules.md}).
 *
 * <p><b>DB 가 이미 값을 닫아 뒀다</b>({@code product_image_content_type_check}). 그런데 Java 가
 * 그 집합을 다섯 자리에 흩어 들고 있었다 — 허용 목록, 검출 {@code switch}, 확장자를 고르는 삼항,
 * 이름 대조 {@code switch}, 썸네일 리터럴. <b>한 자리를 고치고 다른 자리를 빠뜨리면 insert 순간에야
 * 걸린다</b>(`D23` 축 2: 타입 1위, 제약 2위). 여기로 모아서 형식을 하나 더 받는 날 고칠 자리를 하나로 만든다.
 *
 * <p><b>미디어 타입과 확장자를 짝으로 든다.</b> 그 둘이 갈리면 내려받은 사람이 연 파일과 이름이
 * 어긋나고, 이름을 믿는 다음 코드가 그 어긋남을 물려받는다({@code Q96}).
 *
 * <p>WebP 가 없는 것은 JDK 가 읽지도 쓰지도 못해서다 — 네이티브 라이브러리를 안 들였다
 * (사용자 결정 2026-09-18).
 */
enum ImageContentType {

    /** 이름은 {@code .jpg} 와 {@code .jpeg} 둘 다 정당하다. 저장할 때는 앞엣것으로 쓴다 */
    JPEG("image/jpeg", "jpg", Set.of("jpg", "jpeg")),
    PNG("image/png", "png", Set.of("png"));

    private final String code;
    private final String extension;
    private final Set<String> names;

    ImageContentType(String code, String extension, Set<String> names) {
        this.code = code;
        this.extension = extension;
        this.names = names;
    }

    /**
     * DB 에 들어가는 값. {@code EnumConstraintTest} 가 이 이름의 메서드를 리플렉션으로 읽어
     * 제약 목록과 대조한다 — 그래서 이름을 바꾸지 않는다.
     */
    String code() {
        return code;
    }

    /** 저장할 때 쓰는 확장자. {@code ImageIO} 에 넘기는 형식 이름이기도 하다 */
    String extension() {
        return extension;
    }

    /** 올린 파일 이름의 확장자가 이 형식과 짝인가. 대소문자는 부른 쪽이 맞춰서 준다 */
    boolean matchesName(String nameExtension) {
        return names.contains(nameExtension);
    }

    /**
     * {@link javax.imageio.ImageIO} 가 읽어 낸 형식 이름으로 고른다.
     *
     * <p><b>모르면 {@code null} 이다.</b> 여기서 던지지 않는 이유는 부르는 쪽이 이미
     * 「받을 수 있는 형식이 아니다」라는 이름의 오류를 들고 있어서다 — 던지면 같은 뜻의 오류가 둘이 된다.
     */
    static ImageContentType ofFormat(String format) {
        if (format == null) {
            return null;
        }
        String normalized = format.toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(type -> type.names.contains(normalized))
                .findFirst()
                .orElse(null);
    }

    /**
     * DB 에서 읽은 값으로 고른다.
     *
     * <p>제약이 값을 닫아 뒀으므로 <b>모르는 값은 표가 깨진 것</b>이다. 그래서 {@code null} 이 아니라 던진다 —
     * 다른 열거형의 {@code of()} 와 같은 약속이다.
     */
    static ImageContentType of(String code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(type -> type.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("모르는 이미지 형식이다: " + code));
    }
}
