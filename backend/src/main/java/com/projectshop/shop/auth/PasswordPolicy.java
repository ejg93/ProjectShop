package com.projectshop.shop.auth;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;

/**
 * 흔하거나 추측하기 쉬운 비밀번호를 거른다(`D14-2`, NIST SP 800-63B Rev 4 의 블록리스트 {@code SHALL}).
 *
 * <p><b>길이와 문자 집합은 {@link Password} 가 든다.</b> 여기는 <b>값 자체가 알려져 있나</b>를 본다 — 셋을 본다.
 *
 * <ol>
 *   <li><b>유출·흔한 목록</b> — {@code security/password-blocklist.txt}. 출처와 거른 방법은
 *       {@code external-references.md} 가 든다. <b>우리 규칙을 통과할 수 있는 것만 남겼다</b>(15~64자 ASCII) —
 *       나머지는 이미 {@link Password} 에서 막혀서 목록에 둬도 안 걸린다
 *   <li><b>문맥 단어</b> — 서비스 이름과 <b>그 사람의</b> 이메일 앞부분·이름. 목록은 모두에게 같지만 이것은
 *       사람마다 달라서 요청 검증({@link Password})이 못 보고, 비밀번호를 정하는 입구가 넘겨 준다
 *   <li><b>한 글자 되풀이</b> — {@code aaaaaaaaaaaaaaa} 는 15자를 채워도 추측에 한 번이다
 * </ol>
 *
 * <h2>외부를 안 부른다</h2>
 *
 * <p>목록을 저장소에 둔다(사용자 선택). 유출 조회 API 를 부르면 그 장애가 가입 장애가 되고, 장애 때 통과시킬지
 * 막을지를 또 정해야 한다 — 파일은 늘 같은 답을 준다. 대가는 <b>목록이 낡는 것</b>이고 갱신은 청크로 한다.
 *
 * <h2>대소문자를 안 가린다</h2>
 *
 * <p>목록은 소문자로 저장했고 비교도 소문자로 한다. {@code PASSWORD...} 가 목록의 그것과 다른 비밀번호라고
 * 보는 공격자는 없다.
 */
@Component
public class PasswordPolicy {

    /** 서비스 이름. 사람들이 가입하는 곳의 이름을 비밀번호에 넣는다 */
    private static final Set<String> SERVICE_WORDS = Set.of("projectshop");

    /** 이보다 짧은 문맥 단어는 안 본다 — 세 글자 이름이 들어간 비밀번호를 전부 막으면 막는 것이 너무 넓다 */
    private static final int MIN_CONTEXT_WORD = 4;

    private static final String BLOCKLIST = "security/password-blocklist.txt";

    private final Set<String> blocklist = load();

    /**
     * 받을 수 있는 비밀번호인가. 아니면 {@code PASSWORD_TOO_COMMON}(422)이다.
     *
     * @param contextWords 그 사람에게서 나온 단어 — 이메일 앞부분, 이름. {@code null} 은 건너뛴다
     */
    public void requireAcceptable(String password, Collection<String> contextWords) {
        String folded = password.toLowerCase(Locale.ROOT);

        if (blocklist.contains(folded)
                || SERVICE_WORDS.stream().anyMatch(folded::contains)
                || containsContext(folded, contextWords)
                || folded.chars().distinct().count() == 1) {
            throw new ShopException(ErrorCode.PASSWORD_TOO_COMMON);
        }
    }

    /** 이메일에서 문맥 단어로 쓸 앞부분. {@code @} 가 없으면 통째로 쓴다 */
    public static String localPartOf(String email) {
        if (email == null) {
            return null;
        }
        int at = email.indexOf('@');
        return at < 0 ? email : email.substring(0, at);
    }

    private static boolean containsContext(String folded, Collection<String> contextWords) {
        return contextWords.stream()
                .filter(word -> word != null && word.length() >= MIN_CONTEXT_WORD)
                .map(word -> word.toLowerCase(Locale.ROOT))
                .anyMatch(folded::contains);
    }

    private static Set<String> load() {
        InputStream in = PasswordPolicy.class.getClassLoader().getResourceAsStream(BLOCKLIST);
        if (in == null) {
            // 목록이 없는데 조용히 빈 집합으로 돌면 대조가 아무것도 안 막는다 — 뜨지 않는 편이 낫다.
            throw new IllegalStateException("비밀번호 블록리스트가 없다: " + BLOCKLIST);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return reader.lines()
                    .filter(line -> !line.isEmpty())
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
