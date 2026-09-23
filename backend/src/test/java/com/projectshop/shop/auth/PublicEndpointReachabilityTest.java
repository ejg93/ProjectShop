package com.projectshop.shop.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * 화면이 <b>로그인 없이</b> 부르는 경로가 전부 열려 있는지 본다(`Q170`).
 *
 * <p>{@code apiPublic} 은 쿠키를 안 싣는다({@code frontend/src/lib/api.ts}). 그 경로가
 * {@link SecurityConfig#PUBLIC_PATHS}·{@link SecurityConfig#PUBLIC_READS} 어디에도 없으면 401 이고,
 * 서버 컴포넌트가 그것을 받으면 <b>페이지가 통째로 죽는다.</b> 후기 목록이 그렇게 빠져서
 * 상품 상세가 죽었고 e2e 둘이 거기서 멈췄다(PR #73) — 백엔드 테스트는 전부 초록이었다.
 * 그 경로를 부르는 시험이 로그인한 채로만 불러서다.
 *
 * <p><b>빠른 레인이다.</b> MockMvc 로 재면 DB 를 타서 느린 레인으로 가는데, 그 레인은 화면 소스가
 * 입력이 아니라 화면만 고친 청크에서 안 돈다. 대신 {@code requestMatchers} 에 들어가는
 * 목록 그 자체를 읽는다 — 목록이 곧 실물이다.
 *
 * <p>{@code ${…}} 는 {@code 1} 로 바꾼다. 화면이 넣는 값은 번호·코드라 별 하나에 걸리는 조각이면 된다.
 * 쿼리 문자열은 매처가 안 보므로 뗀다.
 */
class PublicEndpointReachabilityTest {

    private static final Path SCREEN_ROOT = Path.of("..", "frontend", "src");

    /** {@code apiPublic<T>(`/api/...`)} — 제네릭은 없을 수도 있고, 여는 괄호 뒤에 줄바꿈이 올 수 있다. */
    private static final Pattern CALL = Pattern.compile(
            "apiPublic(?:<[^(]*?>)?\\(\\s*[`\"']([^`\"']+)[`\"']");

    private static final Pattern TEMPLATE = Pattern.compile("\\$\\{[^}]*}");

    @Test
    @DisplayName("화면이 로그인 없이 부르는 경로는 전부 공개 목록에 있다")
    void everyAnonymousScreenCallIsPermitted() {
        List<PathPattern> permitted = Stream.concat(
                SecurityConfig.PUBLIC_PATHS.stream(), SecurityConfig.PUBLIC_READS.stream())
                .map(PathPatternParser.defaultInstance::parse)
                .toList();

        List<ScreenCall> calls = screenCalls();
        // 정규식이 삭으면 목록이 비어서 아무것도 안 잰다 — 그 상태를 초록으로 안 둔다.
        assertThat(calls).as("apiPublic 호출을 하나도 못 찾았다. 정규식이나 경로를 의심한다").isNotEmpty();

        List<String> unreachable = calls.stream()
                .filter(call -> permitted.stream()
                        .noneMatch(pattern -> pattern.matches(PathContainer.parsePath(call.path()))))
                .map(ScreenCall::toString)
                .toList();

        assertThat(unreachable)
                .as("비로그인이 부르는데 SecurityConfig 두 목록 어디에도 없다 — 그 화면은 401 로 죽는다")
                .isEmpty();
    }

    private record ScreenCall(Path file, String path) {
        @Override
        public String toString() {
            return SCREEN_ROOT.relativize(file) + "  " + path;
        }
    }

    private static List<ScreenCall> screenCalls() {
        List<ScreenCall> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SCREEN_ROOT)) {
            for (Path file : files
                    .filter(path -> path.toString().endsWith(".ts") || path.toString().endsWith(".tsx"))
                    // 화면 테스트의 가짜 경로는 계약이 아니다.
                    .filter(path -> !path.toString().contains(".test."))
                    .toList()) {
                Matcher matcher = CALL.matcher(Files.readString(file));
                while (matcher.find()) {
                    found.add(new ScreenCall(file, normalize(matcher.group(1))));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("화면 소스를 못 읽었다: " + SCREEN_ROOT.toAbsolutePath(), e);
        }
        return found;
    }

    private static String normalize(String raw) {
        String withoutQuery = raw.contains("?") ? raw.substring(0, raw.indexOf('?')) : raw;
        return TEMPLATE.matcher(withoutQuery).replaceAll("1");
    }
}
