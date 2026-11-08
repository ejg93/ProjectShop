package com.projectshop.shop.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 개인정보 그룹을 기록 없이 여는 길이 타입 밖에 남았나(`Q282a`·`Q282b`, `D2` R44 — 안전성 확보조치 기준 제8조제1항).
 *
 * <p><b>막는 것은 타입이다.</b> 개인정보 그룹({@code PersonalFieldGroup})은 {@code canSee} 가 안 받고, 건 하나는
 * {@link PersonalDataAccess}, 목록은 {@link ListAccess} 로만 열린다. 둘 다 {@link AuditLog} 가 기록과 함께 만들고,
 * 목록 한 쪽은 {@link Unrecorded#record} 로만 꺼내진다. 타입이 못 막는 틈은 셋이었다.
 *
 * <p><b>둘은 ArchUnit 으로 갔다</b>(`Q386`, 2026-11-08 `점검 AI`) — 증서를 {@code AuditLog} 밖에서 만드는 것과
 * 판정의 그룹 목록을 직접 묻는 것은 「누가 무엇을 부르나」라 {@code ArchitectureTest} 가 바이트코드로 잰다.
 * <b>여기는 SQL 글자 하나다</b> — 문의 목록 SQL 을 {@code find} 밖에서 도는 것. 거기서 만든 쪽은 {@code Unrecorded} 를
 * 안 거치는데, 어느 메서드가 그 SQL 을 드는지는 문자열이라 ArchUnit 이 못 본다.
 */
class PersonalDataViewTest {

    private static final Path MAIN = Path.of("src/main/java");

    private static final Path INQUIRY_QUERY = MAIN.resolve("com/projectshop/shop/inquiry/InquiryQuery.java");

    /** 문의 표를 읽는 SQL */
    private static final Pattern READS_INQUIRY = Pattern.compile("from inquiry\\b");

    /** 메서드 머리. 들여쓰기 네 칸이 클래스 몸통이다 — 텍스트 블록과 주석은 더 들어가 있거나 {@code *} 로 시작한다 */
    private static final Pattern METHOD_HEAD = Pattern.compile(
            "^    (?:(?:public|private|protected|static|final)\\s+)*[\\w<>\\[\\]?][\\w<>\\[\\], .?]*\\s+(\\w+)\\(",
            Pattern.MULTILINE);

    /**
     * 문의 목록 SQL 을 도는 자리가 {@code find} 하나라야 쪽이 {@code Unrecorded} 를 거친다(`Q282b`).
     * {@code findPublic} 은 공개 문의라 낸 사람 칸이 없고 남의 개인정보 그룹을 안 연다.
     */
    @Test
    @DisplayName("InquiryQuery 에서 문의 표를 읽는 SQL 은 find·findPublic 안뿐이다")
    void inquiryListSqlStaysInFind() {
        String source = read(INQUIRY_QUERY);
        List<String> owners = new ArrayList<>();
        Matcher reads = READS_INQUIRY.matcher(source);
        while (reads.find()) {
            owners.add(enclosingMethod(source, reads.start()));
        }

        assertThat(owners.stream().distinct().toList())
                .as("새 자리가 문의를 읽으면 그 쪽은 기록을 안 거친다 — find 를 부르거나 이 목록을 근거와 함께 넓힌다")
                .containsExactlyInAnyOrder("find", "findPublic");
    }

    private static String enclosingMethod(String source, int index) {
        Matcher head = METHOD_HEAD.matcher(source.substring(0, index));
        String name = null;
        while (head.find()) {
            name = head.group(1);
        }
        return name;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
