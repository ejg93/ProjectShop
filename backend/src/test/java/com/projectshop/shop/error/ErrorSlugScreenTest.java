package com.projectshop.shop.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 화면이 분기하는 오류 슬러그가 <b>실제로 나가는 슬러그인지</b> 대조한다.
 *
 * <p><b>어긋나도 아무것도 안 터진다.</b> 백엔드가 슬러그를 하나 바꾸면 화면의 그 {@code case} 는
 * 죽은 가지가 되고, 사용자는 그 상황에서 영영 {@code default} 문구만 본다 — 빌드도 타입 검사도
 * 린트도 통과하고, 화면 테스트조차 <b>같은 틀린 문자열</b>을 쓰면 초록이다.
 *
 * <p>같은 수법의 선례가 있다({@code OrderRecordTextTest.StatusLabels}) — 거기는 상태 코드 표를
 * 대조하고 여기는 오류 슬러그를 대조한다. <b>두 층에 흩어진 문자열은 대조 말고 막을 방법이 없다.</b>
 *
 * <p>한쪽만 본다. 화면이 쓰는 것이 서버에 있어야 하고, <b>그 반대는 아니다</b> —
 * 서버 오류를 화면이 다 나눠 적을 이유가 없고 안 적은 것은 {@code default} 로 간다.
 */
class ErrorSlugScreenTest {

    private static final Path SCREEN_ROOT = Path.of("..", "frontend", "src");

    /** {@code switch (error.slug) {} 의 시작. 변수 이름은 파일마다 다르다. */
    private static final Pattern SWITCH_HEAD = Pattern.compile("switch\\s*\\(\\s*\\w+\\.slug\\s*\\)\\s*\\{");
    /** 그 안의 {@code case "이름":} */
    private static final Pattern CASE_SLUG = Pattern.compile("case\\s+\"([a-z][a-z0-9-]*)\"");

    @Test
    @DisplayName("화면이 분기하는 오류 슬러그가 전부 실물이다")
    void everySlugTheScreensBranchOnExists() throws IOException {
        Set<String> onScreens = slugsOnScreens();
        Set<String> real = Arrays.stream(ErrorCode.values())
                .map(ErrorSlugScreenTest::slugOf)
                .collect(TreeSet::new, Set::add, Set::addAll);

        // 정규식이 상하면 0개를 읽고 조용히 통과한다. 그쪽이 어긋난 것보다 나쁘다.
        assertThat(onScreens)
                .describedAs("화면에서 오류 분기를 한 건도 못 읽었다. `switch (error.slug)` 모양이 바뀌었나")
                .isNotEmpty();

        assertThat(onScreens)
                .describedAs("화면이 없는 슬러그로 분기한다. 그 가지는 영영 안 타고 사용자는 기본 문구만 본다")
                .isSubsetOf(real);
    }

    /** {@code tag:...:슬러그} 에서 마지막 칸만. 슬러그에는 {@code :} 가 없다 */
    private static String slugOf(ErrorCode code) {
        String type = code.type();
        return type.substring(type.lastIndexOf(':') + 1);
    }

    /**
     * 화면 소스에서 {@code switch (error.slug)} 블록만 골라 {@code case} 를 모은다.
     *
     * <p><b>블록 밖의 {@code case} 는 안 센다.</b> 화면에는 오류가 아닌 switch 도 있고,
     * 그것까지 세면 이 테스트가 남의 문자열을 오류 슬러그라고 우긴다.
     */
    private static Set<String> slugsOnScreens() throws IOException {
        Set<String> found = new TreeSet<>();
        for (Path file : screenSources()) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            Matcher head = SWITCH_HEAD.matcher(source);
            while (head.find()) {
                // switch 는 두 칸 들여쓴 자리에서 닫힌다. 못 찾으면 파일 끝까지 본다.
                int close = source.indexOf("\n  }", head.end());
                String body = source.substring(head.end(), close < 0 ? source.length() : close);

                Matcher slug = CASE_SLUG.matcher(body);
                while (slug.find()) {
                    found.add(slug.group(1));
                }
            }
        }
        return found;
    }

    private static List<Path> screenSources() throws IOException {
        try (Stream<Path> walk = Files.walk(SCREEN_ROOT)) {
            List<Path> files = new ArrayList<>();
            walk.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.endsWith(".tsx") || name.endsWith(".ts");
                    })
                    .forEach(files::add);
            return files;
        }
    }
}
