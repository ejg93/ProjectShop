package com.projectshop.shop.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 로그 인자에 개인정보 접근자가 섞이는 것을 막는다(`Q71`, `D16` 「개인정보는 안 찍는다」).
 *
 * <p><b>지금 위반은 0이고 그것이 이 테스트를 세운 이유다.</b> 점검 O 가 물었을 때 규칙을 어긴 로그가
 * 하나도 없었는데, 막는 것이 <b>문서와 습관</b>뿐이었다 — 강제 지점 5위다(`D23` 축 2).
 * 로그는 빌드도 테스트도 안 깨뜨리며 새고, 샌 뒤에는 이미 파일과 수집기에 남는다.
 *
 * <p><b>이 그물이 성기다는 것을 같이 적어 둔다.</b> {@code D16} 이 마스킹을 안 쓴 이유가
 * 「금지 목록은 빠뜨리면 조용히 샌다」인데 <b>이 테스트도 금지 목록</b>이다. 그래서 규칙 자체는
 * 여전히 「애초에 안 찍는다」이고, 여기는 <b>이름이 뻔한 것만</b> 잡는 4순위 그물이다.
 * 다음 둘은 글자로 못 본다:
 * <ul>
 *   <li><b>객체를 통째로 찍는 것</b> — {@code log.info("{}", order)} 는 타입을 알아야 판정된다</li>
 *   <li><b>지역 변수로 한 번 받아서 찍는 것</b> — 대입을 따라가야 한다</li>
 * </ul>
 *
 * <p><b>빠른 레인이다.</b> 글자만 읽으므로 컨테이너도 컨텍스트도 필요 없다(`D15`).
 */
@DisplayName("로그 인자")
class LogArgumentTest {

    private static final Path SOURCE_ROOT = Path.of("src", "main", "java");

    /** {@code log.info(} 처럼 로거를 부르는 자리. 여는 괄호까지 잡고 인자는 아래에서 센다. */
    private static final Pattern LOG_CALL = Pattern.compile("\\blog\\.(trace|debug|info|warn|error)\\s*\\(");

    /**
     * 로그에 실리면 안 되는 접근자 이름. {@code D16} 이 든 넷(이름·이메일·연락처·주소)을 옮긴 것이다.
     *
     * <p><b>{@code name()} 은 안 넣는다.</b> 열거형의 {@code name()} 이 같은 글자라 전부 걸린다 —
     * 사람 이름을 담는 칸은 이 저장소에서 {@code displayName}·{@code receiverName}·{@code senderName} 이고
     * 그것만 적는다. {@code batchName}·{@code templateName} 은 사람이 아니라 안 넣는다.
     */
    private static final List<String> FORBIDDEN = List.of(
            "email",
            "displayName",
            "phone",
            "senderPhone",
            "receiverName",
            "senderName",
            "address1",
            "address2",
            "postalCode",
            "deliveryMemo",
            "pickupMemo");

    @Test
    @DisplayName("로그 인자에 개인정보 접근자가 없다")
    void logCallsDoNotCarryPersonalData() {
        List<String> offenders = new ArrayList<>();

        for (Path file : sourceFiles()) {
            String text = readString(file);
            Matcher matcher = LOG_CALL.matcher(text);
            while (matcher.find()) {
                String arguments = argumentsAt(text, matcher.end() - 1);
                FORBIDDEN.stream()
                        .filter(token -> mentions(arguments, token))
                        .forEach(token -> offenders.add(
                                SOURCE_ROOT.relativize(file).toString().replace('\\', '/') + "  " + token));
            }
        }

        assertThat(offenders)
                .describedAs("로그에 개인정보가 실린다(`D16` 「개인정보는 안 찍는다」). "
                        + "식별자만 남기고 내용이 필요하면 그 id 로 DB 를 조회한다")
                .isEmpty();
    }

    /** 여는 괄호 자리에서 시작해 짝이 맞는 닫는 괄호까지의 인자 부분. */
    private static String argumentsAt(String text, int openParen) {
        int depth = 0;
        for (int i = openParen; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return text.substring(openParen + 1, i);
                }
            }
        }
        return text.substring(openParen + 1);
    }

    /**
     * 인자 안에 그 이름이 <b>식별자로</b> 나오나. 앞뒤가 글자·숫자면 다른 이름의 일부다 —
     * {@code emailChangeRequestId} 가 {@code email} 로 걸리면 안 된다.
     */
    private static boolean mentions(String arguments, String token) {
        Matcher matcher = Pattern.compile("(?<![A-Za-z0-9_])" + token + "(?![A-Za-z0-9_])").matcher(arguments);
        return matcher.find();
    }

    private static List<Path> sourceFiles() {
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            return files.filter(path -> path.toString().endsWith(".java")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("소스를 못 읽었다: " + SOURCE_ROOT.toAbsolutePath(), e);
        }
    }

    private static String readString(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("못 읽었다: " + path.toAbsolutePath(), e);
        }
    }
}
