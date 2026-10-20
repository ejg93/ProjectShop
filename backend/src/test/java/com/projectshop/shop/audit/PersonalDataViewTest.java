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
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 개인정보 그룹을 기록 없이 여는 길이 타입 밖에 남았나(`Q282a`·`Q282b`, `D2` R44 — 안전성 확보조치 기준 제8조제1항).
 *
 * <p><b>막는 것은 타입이다.</b> 개인정보 그룹({@code PersonalFieldGroup})은 {@code canSee} 가 안 받고, 건 하나는
 * {@link PersonalDataAccess}, 목록은 {@link ListAccess} 로만 열린다. 둘 다 {@link AuditLog} 가 기록과 함께 만들고,
 * 목록 한 쪽은 {@link Unrecorded#record} 로만 꺼내진다. 이 시험은 <b>타입이 못 막는 틈 셋</b>을 글자로 잰다.
 *
 * <ul>
 *   <li>증서를 {@code AuditLog} 밖에서 만드는 것 — 생성자가 패키지 안이라 {@code audit} 패키지 안의 다른 클래스만 할 수 있다
 *   <li>판정의 그룹 목록을 코드 문자열로 직접 묻는 것 — {@code Decision} 이 record 라 그 칸이 공개다
 *   <li>문의 목록 SQL 을 {@code find} 밖에서 도는 것 — 거기서 만든 쪽은 {@code Unrecorded} 를 안 거친다
 * </ul>
 */
class PersonalDataViewTest {

    private static final Path MAIN = Path.of("src/main/java");

    private static final Path INQUIRY_QUERY = MAIN.resolve("com/projectshop/shop/inquiry/InquiryQuery.java");

    /** 증서를 만드는 자리 */
    private static final Pattern CREATES_ACCESS = Pattern.compile("new (PersonalDataAccess|ListAccess)\\(");

    /** 판정의 그룹 목록을 직접 묻는 자리. record 칸이 공개라 컴파일로 못 막는다(`Q282a`) */
    private static final Pattern ASKS_GROUPS_DIRECTLY = Pattern.compile("visibleFieldGroups\\(\\)\\.covers\\(");

    /** 문의 표를 읽는 SQL */
    private static final Pattern READS_INQUIRY = Pattern.compile("from inquiry\\b");

    /** 메서드 머리. 들여쓰기 네 칸이 클래스 몸통이다 — 텍스트 블록과 주석은 더 들어가 있거나 {@code *} 로 시작한다 */
    private static final Pattern METHOD_HEAD = Pattern.compile(
            "^    (?:(?:public|private|protected|static|final)\\s+)*[\\w<>\\[\\]?][\\w<>\\[\\], .?]*\\s+(\\w+)\\(",
            Pattern.MULTILINE);

    @Test
    @DisplayName("증서를 만드는 자리는 AuditLog 하나다")
    void onlyAuditLogCreatesAccess() {
        List<String> creators = mainSources().filter(path -> CREATES_ACCESS.matcher(read(path)).find())
                .map(PersonalDataViewTest::name)
                .toList();

        assertThat(creators)
                .as("증서를 쥔 코드는 기록을 이미 남긴 코드여야 한다 — AuditLog.open·openList 만 만든다")
                .containsExactly("AuditLog");
    }

    /**
     * 개인정보 그룹은 타입을 갈라서 {@code canSee} 로 못 묻는다(`Q282a`). 남은 우회 길은 판정의 그룹 목록을 코드 문자열로
     * 직접 묻는 것이다 — {@code Decision} 이 record 라 그 칸이 공개고 컴파일이 막지 못한다. 그래서 그렇게 묻는 main 클래스가
     * {@code PersonalDataAccess.allows} 하나인지 잰다 — {@code ListAccess} 도 그것을 부른다.
     */
    @Test
    @DisplayName("판정의 그룹 목록을 직접 묻는 main 클래스는 증서뿐이다")
    void onlyAccessAsksGroupsDirectly() {
        List<String> askers = mainSources().filter(path -> ASKS_GROUPS_DIRECTLY.matcher(read(path)).find())
                .map(PersonalDataViewTest::name)
                .toList();

        assertThat(askers)
                .as("기록 없이 개인정보 그룹을 여는 길이다 — AuditLog.open·openList 가 돌려주는 증서로 묻는다")
                .containsExactly("PersonalDataAccess");
    }

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

    private static Stream<Path> mainSources() {
        try {
            return Files.walk(MAIN).filter(path -> path.toString().endsWith(".java")).toList().stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String name(Path path) {
        String file = path.getFileName().toString();
        return file.substring(0, file.length() - ".java".length());
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
