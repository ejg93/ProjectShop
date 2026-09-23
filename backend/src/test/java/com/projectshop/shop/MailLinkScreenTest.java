package com.projectshop.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 백엔드가 메일에 싣는 화면 링크가 <b>실제로 있는 화면</b>을 가리키는지 본다(`Q181`).
 *
 * <p><b>두 번 났다.</b> 비밀번호 재설정(`5c-1`)과 이메일 변경(`5e-1`)이 입구와 메일을 세우고 화면을 안 세워서,
 * 메일의 링크가 없는 화면을 가리켰다 — 각각 `Q180`·`Q181` 이 닫았다. 백엔드 시험은 링크 문자열만 보고
 * 화면 시험은 링크를 모른다 — <b>두 층 사이라 어느 쪽 시험에도 안 걸리는 자리</b>다(`testing-strategy.md`
 * 「두 층에 흩어진 문자열의 대조」).
 *
 * <p>링크는 {@code @Value("${app.….url-template:http://localhost:3000/<경로>?…}")} 의 기본값에서 뽑고,
 * 화면은 {@code frontend/src/app/<경로>/page.tsx} 가 있는지로 본다.
 */
class MailLinkScreenTest {

    private static final Path SOURCE = Path.of("src", "main", "java");
    private static final Path SCREENS = Path.of("..", "frontend", "src", "app");

    /** {@code url-template:http://localhost:3000/password-reset?token=} 에서 경로만 */
    private static final Pattern LINK = Pattern.compile("url-template:https?://[^/\"]+/([a-z0-9/-]+)");

    @Test
    @DisplayName("메일 링크가 가리키는 화면이 전부 있다")
    void everyMailLinkHasAScreen() {
        TreeSet<String> paths = linkPaths();
        assertThat(paths).as("링크를 하나도 못 찾았다. 정규식이나 경로를 의심한다").isNotEmpty();

        List<String> missing = paths.stream()
                .filter(path -> !Files.exists(SCREENS.resolve(path).resolve("page.tsx")))
                .toList();

        assertThat(missing)
                .as("메일이 여는 화면이 없다 — 받은 사람이 링크를 눌러도 404 다. 화면을 세우거나 링크를 고친다")
                .isEmpty();
    }

    private static TreeSet<String> linkPaths() {
        TreeSet<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.walk(SOURCE)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = LINK.matcher(Files.readString(file));
                while (matcher.find()) {
                    found.add(matcher.group(1));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return found;
    }
}
