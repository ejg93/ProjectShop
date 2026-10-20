package com.projectshop.shop.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 남의 개인정보 그룹을 여는 조회 입구가 열람 기록을 부르나(`Q276`, `D2` R44 — 안전성 확보조치 기준 제8조제1항).
 *
 * <p><b>새 조회 입구가 빠뜨리는 것을 막는다.</b> 기록은 입구마다 한 줄이라(앱 검증, 3위) 새 입구가 생기면 빠뜨린다 —
 * 그 입구가 배송지를 실어도 아무것도 안 깨진다. 그래서 <b>그룹을 여는 클래스와 기록을 부르는 클래스가 같은지</b>를
 * 글자로 잰다. 클래스 단위라 한 클래스 안의 두 번째 입구는 못 잡는다(그 자리는 그 클래스의 시험이 잰다).
 *
 * <p>개인정보 그룹은 다섯이다 — 주문의 배송지·결제, 계정의 생년월일·연락처, 문의의 본문. 셋째 줄({@code REFUND})은
 * 금액과 상태라 뺀다.
 */
class PersonalDataViewTest {

    private static final Path MAIN = Path.of("src/main/java");

    private static final Pattern OPENS_PERSONAL = Pattern.compile(
            "(canSee|opened)\\((OrderPersonalFields\\.(SHIPPING|PAYMENT)|UserPersonalFields\\.(BIRTH|CONTACT)"
                    + "|InquiryFields\\.BODY)\\)");

    private static final Pattern RECORDS_VIEW = Pattern.compile("auditLog\\.(personalData(Viewed|Listed)|open)\\(");

    /** 판정의 그룹 목록을 직접 묻는 자리. record 칸이 공개라 컴파일로 못 막는다(`Q282a`) */
    private static final Pattern ASKS_GROUPS_DIRECTLY = Pattern.compile("visibleFieldGroups\\(\\)\\.covers\\(");

    @Test
    @DisplayName("개인정보 그룹을 여는 클래스는 열람 기록을 부른다")
    void everyOpenerRecords() {
        List<String> openers = mainSources().filter(path -> OPENS_PERSONAL.matcher(read(path)).find())
                .map(PersonalDataViewTest::name)
                .toList();

        // 정규식이 성립하는지부터 — 아무것도 안 물면 아래 단언이 빈 목록끼리 초록이다(`Q145`·`Q152` 와 같은 자리).
        assertThat(openers)
                .as("개인정보 그룹을 여는 조회 입구가 넷이다(2026-10-19). 늘었으면 이 목록과 그 입구의 기록을 같이 본다")
                .containsExactlyInAnyOrder("OrderQuery", "SellerOrderQuery", "InquiryQuery", "AccountService");

        List<String> silent = openers.stream()
                .filter(name -> mainSources()
                        .filter(path -> name(path).equals(name))
                        .noneMatch(path -> RECORDS_VIEW.matcher(read(path)).find()))
                .toList();

        assertThat(silent)
                .as("남의 개인정보를 열고 열람 기록을 안 남기는 클래스 — AuditLog.personalDataViewed·personalDataListed 를 부른다")
                .isEmpty();
    }

    /**
     * 개인정보 그룹은 타입을 갈라서 {@code canSee} 로 못 묻는다(`Q282a`). 남은 우회 길은 판정의 그룹 목록을 코드 문자열로
     * 직접 묻는 것이다 — {@code Decision} 이 record 라 그 칸이 공개고 컴파일이 막지 못한다. 그래서 그렇게 묻는 main 클래스가
     * 증서 하나인지 잰다.
     */
    @Test
    @DisplayName("판정의 그룹 목록을 직접 묻는 main 클래스는 증서뿐이다")
    void onlyAccessAsksGroupsDirectly() {
        List<String> askers = mainSources().filter(path -> ASKS_GROUPS_DIRECTLY.matcher(read(path)).find())
                .map(PersonalDataViewTest::name)
                .toList();

        assertThat(askers)
                .as("기록 없이 개인정보 그룹을 여는 길이다 — AuditLog.open 이 돌려주는 PersonalDataAccess 로 묻는다")
                .containsExactly("PersonalDataAccess");
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
