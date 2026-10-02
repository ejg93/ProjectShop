package com.projectshop.shop.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.FixedLocaleResolver;

import com.projectshop.shop.PostgresTestBase;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 검증 칸 문구(`Q238`). <b>요청 언어와 무관하게 한국어 존댓말 한 벌이다</b>(`D5` 「검증 실패는」·`D20`).
 *
 * <p>화면은 서버 문구를 칸 옆에 그대로 그린다({@code field-errors.ts}). 그래서 이 문구가 곧 화면 문구고,
 * 화면 문구는 존댓말이다. 전에는 Hibernate Validator 의 기본 문구가 요청 언어를 따라 영어 브라우저에
 * 「must not be blank」를 냈고, 손으로 단 문구 하나가 평서형이었다.
 *
 * <p><b>글이라 더 못 내린다</b>(축 2의 4위). 문구 파일에 열쇠가 빠지거나 평서형이 들어오는 것을 여기서 잡는다.
 */
@DisplayName("검증 칸 문구")
class ValidationMessageTest extends PostgresTestBase {

    private static final Path MAIN = Path.of("src", "main", "java");
    private static final Path MESSAGES = Path.of("src", "main", "resources", "ValidationMessages.properties");
    private static final Pattern CONSTRAINT_IMPORT =
            Pattern.compile("(?m)^import jakarta\\.validation\\.constraints\\.([A-Za-z]+);");
    /** 애너테이션 속성({@code (…, message = "…")})과 제약 정의의 기본값({@code message() default "…"})만 — 지역 변수는 아니다 */
    private static final Pattern LITERAL_MESSAGE =
            Pattern.compile("(?:[(,]\\s*message\\s*=|message\\(\\)\\s+default)\\s*\"([^\"]*)\"");
    private static final Pattern POLITE = Pattern.compile("(?s).*(니다|세요)$");
    private static final Pattern KEY_REFERENCE = Pattern.compile("^\\{[^{}]+\\}$");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private Validator validator;

    @Autowired
    private LocaleResolver localeResolver;

    /**
     * 언어를 한국어로 굳힌 설정(`application.yml` 의 {@code spring.web.locale-resolver: fixed}).
     *
     * <p><b>문구 파일만으로는 이 설정을 못 잰다</b> — 파일이 기본 파일 하나라 영어 요청도 그 파일로 떨어져서,
     * 설정을 지워도 위 시험은 초록이다(마무리 57차 독립 리뷰). 그래서 설정이 세운 것을 직접 본다.
     */
    @Test
    @DisplayName("요청 언어를 한국어로 굳힌다")
    void fixesTheLocaleToKorean() {
        assertThat(localeResolver).isInstanceOf(FixedLocaleResolver.class);
        assertThat(localeResolver.resolveLocale(new MockHttpServletRequest())).isEqualTo(Locale.KOREAN);
    }

    @ParameterizedTest(name = "Accept-Language: {0}")
    @ValueSource(strings = {"en-US", "ko"})
    @DisplayName("요청 언어와 무관하게 같은 한국어 문구다")
    void sameTextForEveryLanguage(String language) throws Exception {
        mvc.perform(post("/api/auth/signup").with(csrf())
                        .header(HttpHeaders.ACCEPT_LANGUAGE, language)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"q238@test.local","password":"long-enough-password-1",
                                 "display_name":"","consents":{}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'display_name')].message").value(hasItem("필수 입력입니다")));
    }

    @Test
    @DisplayName("main 이 쓰는 제약 종류마다 문구 파일에 열쇠가 있다")
    void everyConstraintKindHasAKey() throws IOException {
        Properties messages = messages();

        assertThat(constraintKindsInMain())
                .as("열쇠가 없으면 그 종류만 Hibernate Validator 의 기본 문구로 돌아가 요청 언어를 따른다")
                .allSatisfy(kind -> assertThat(messages)
                        .containsKey("jakarta.validation.constraints." + kind + ".message"));
    }

    /**
     * 문구 파일의 값을 <b>풀린 채로</b> 본다 — {@code Size} 는 식이라 파일 글자만 보면 끝이 {@code ')}} 다.
     * 영어 로케일로 돌려서 우리 파일이 Hibernate Validator 의 영어 문구를 이기는지도 같이 본다.
     */
    @Test
    @DisplayName("풀린 문구가 영어 로케일에서도 존댓말로 끝난다")
    void renderedMessagesArePolite() throws IOException {
        Locale before = LocaleContextHolder.getLocale();
        Set<ConstraintViolation<Probe>> violations;
        try {
            LocaleContextHolder.setLocale(Locale.US);
            violations = validator.validate(Probe.failing());
        } finally {
            LocaleContextHolder.setLocale(before);
        }

        Set<String> probed = new TreeSet<>();
        for (ConstraintViolation<Probe> violation : violations) {
            probed.add(violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName());
        }
        assertThat(probed)
                .as("시험 틀이 main 이 쓰는 종류를 다 덮어야 이 시험이 잰 것이다")
                .containsAll(constraintKindsInMain());
        List<String> messages = violations.stream().map(ConstraintViolation::getMessage).toList();
        assertThat(messages)
                .allSatisfy(message -> assertThat(message)
                        .as("식이 안 풀리면 「$」·「{」가 그대로 남는다")
                        .doesNotContain("${", "{")
                        .matches(POLITE));
    }

    @Test
    @DisplayName("main 에 손으로 단 문구도 존댓말로 끝난다")
    void literalMessagesArePolite() throws IOException {
        List<String> literals = new java.util.ArrayList<>();
        for (Path file : javaFiles()) {
            Matcher matcher = LITERAL_MESSAGE.matcher(Files.readString(file, StandardCharsets.UTF_8));
            while (matcher.find()) {
                // 문구 전체가 「{열쇠}」 하나면 문구 파일을 가리킨다 — 그쪽은 위 시험이 본다.
                // 「{max}개까지 …」처럼 자리표시자가 섞인 손 문구는 여기서 본다(마무리 57차 독립 리뷰).
                if (!KEY_REFERENCE.matcher(matcher.group(1)).matches()) {
                    literals.add(file.getFileName() + ": " + matcher.group(1));
                }
            }
        }

        assertThat(literals)
                .as("손으로 단 문구가 하나도 안 잡히면 정규식이 낡은 것이다")
                .isNotEmpty()
                .allSatisfy(literal -> assertThat(literal).matches(POLITE));
    }

    /** main 이 쓰는 종류마다 하나씩 어기는 틀. {@code Size} 는 식의 세 갈래(하한만·상한만·둘 다)를 다 지난다 */
    private record Probe(
            @NotBlank String notBlank,
            @NotNull String notNull,
            @NotEmpty List<String> notEmpty,
            @Size(max = 3) String sizeMax,
            @Size(min = 5) String sizeMin,
            @Size(min = 5, max = 9) String sizeBoth,
            @Min(10) Integer min,
            @Max(1) Integer max,
            @Positive Integer positive,
            @PositiveOrZero Integer positiveOrZero,
            @Email String email,
            @jakarta.validation.constraints.Pattern(regexp = "^[0-9]+$") String pattern,
            @Past LocalDate past) {

        static Probe failing() {
            return new Probe("", null, List.of(), "abcd", "ab", "ab", 1, 5, 0, -1,
                    "not-an-email", "abc", LocalDate.now().plusDays(1));
        }
    }

    private static Set<String> constraintKindsInMain() throws IOException {
        Set<String> kinds = new TreeSet<>();
        for (Path file : javaFiles()) {
            Matcher matcher = CONSTRAINT_IMPORT.matcher(Files.readString(file, StandardCharsets.UTF_8));
            while (matcher.find()) {
                kinds.add(matcher.group(1));
            }
        }
        return kinds;
    }

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(file -> file.toString().endsWith(".java")).toList();
        }
    }

    private static Properties messages() throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(MESSAGES, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }
}
