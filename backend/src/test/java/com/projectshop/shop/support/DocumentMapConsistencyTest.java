package com.projectshop.shop.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code document-map.md} 와 {@code domain-model.md} 가 실물과 같은지 대조한다. 갈리면 실패한다.
 *
 * <p><b>2026-09-14 설계 점검이 문서 넷에서 사실과 다른 서술을 찾았다.</b> 파일이 이미 있는데
 * 「없으면 생기는 일」 칸이 그대로인 행이 다섯, 표 일곱을 한 번도 안 부르는 도메인 문서,
 * 없는 통제를 현재형으로 적은 보안 문서, 닫힌 구멍을 열린 것으로 적은 권한 문서다.
 * <b>넷은 그 자리에서 고쳤고 이 테스트는 재발을 막는다.</b>
 *
 * <p>고치는 것과 안 갈리게 하는 것은 다른 일이다. 문서는 강제 지점 5위라
 * <b>사람이 읽어야만 걸린다</b> — {@code 11-6} 이 함정을 이력에만 적었더니 다음 날 다시 밟았고,
 * {@code Q2} 가 같은 것을 대조 테스트로 내렸더니 그다음 마이그레이션에서 바로 잡혔다.
 * {@code D23} 축 2 가 말하는 「기억에 맡길 것을 강제 지점으로 내린다」가 이것이다.
 *
 * <p><b>{@code StackVersionConsistencyTest} 와 보는 것이 다르다.</b> 그쪽은 버전 표 한 절이고
 * 이쪽은 <b>목록 자체가 폴더·마이그레이션과 같은가</b>다. 대조하는 방향도 반대다 —
 * 버전은 문서에서 파일로 내려가고, 여기 둘째·셋째는 <b>실물에서 문서로 올라간다.</b>
 * 빠진 것은 문서를 훑어서 안 나온다({@code coding-rules.md} 「지켜지는지는 위에서 내려가며 확인한다」).
 */
class DocumentMapConsistencyTest {

    private static final Path REFERENCE_DIR = Path.of("..", "doc", "reference");
    private static final Path DOCUMENT_MAP = REFERENCE_DIR.resolve("document-map.md");
    private static final Path DOMAIN_MODEL = REFERENCE_DIR.resolve("domain-model.md");
    private static final Path MIGRATION_DIR =
            Path.of("src", "main", "resources", "db", "migration");

    /** 목록 칸의 「완료 — `x.md`」. 뒤에 괄호 주석이 붙는 행이 있어 줄 끝을 안 묶는다. */
    private static final Pattern COMPLETED_FILE =
            Pattern.compile("완료[^|`]*`([A-Za-z0-9._-]+\\.md)`");

    /**
     * {@code create table} 의 표 이름. {@code if not exists} 와 따옴표 감싼 이름을 같이 받는다.
     * 마이그레이션은 손으로 쓰는 SQL 이라 꼴이 한 가지가 아니다.
     */
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?\"?([A-Za-z_][A-Za-z0-9_]*)\"?",
            Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("document-map.md 의 완료 행이 가리키는 파일이 실재한다")
    void documentMapCompletedRowsPointToExistingFiles() throws IOException {
        String map = Files.readString(DOCUMENT_MAP, StandardCharsets.UTF_8);

        List<String> named = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        Matcher rows = COMPLETED_FILE.matcher(map);
        while (rows.find()) {
            String name = rows.group(1);
            named.add(name);
            if (!Files.isRegularFile(REFERENCE_DIR.resolve(name))) {
                missing.add(name);
            }
        }

        assertThat(named)
                .describedAs("document-map.md 에서 「완료 — `x.md`」 를 한 줄도 못 읽었다. "
                        + "칸의 표기가 바뀌었으면 이 테스트의 정규식을 같이 고친다")
                .isNotEmpty();

        assertThat(missing)
                .describedAs("document-map.md 가 완료로 적은 파일이 doc/reference/ 에 없다. "
                        + "파일을 지웠거나 이름을 바꿨으면 지도도 같이 고친다")
                .isEmpty();
    }

    @Test
    @DisplayName("doc/reference 의 모든 문서가 document-map.md 에 적혀 있다")
    void referenceDocsAreMentionedInDocumentMap() throws IOException {
        String map = Files.readString(DOCUMENT_MAP, StandardCharsets.UTF_8);

        List<String> unmapped = new ArrayList<>();
        List<String> checked = new ArrayList<>();
        try (Stream<Path> files = Files.list(REFERENCE_DIR)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".md") || name.equals(DOCUMENT_MAP.getFileName().toString())) {
                    continue;
                }
                checked.add(name);
                if (!map.contains(name)) {
                    unmapped.add(name);
                }
            }
        }

        assertThat(checked)
                .describedAs("doc/reference/ 에서 문서를 하나도 못 읽었다. 경로가 바뀌었다")
                .isNotEmpty();

        // **예외 목록을 안 만든다.** 번호를 안 받은 문서는 지도의 「번호 없는 참고 자료」로 간다 —
        // 여기에 예외를 쌓기 시작하면 이 테스트가 무엇을 지키는지 흐려진다.
        assertThat(unmapped)
                .describedAs("doc/reference/ 에 있는데 document-map.md 가 안 부르는 문서다. "
                        + "번호를 받는 것이면 기준 문서 목록에, 아니면 「번호 없는 참고 자료」에 적는다")
                .isEmpty();
    }

    @Test
    @DisplayName("마이그레이션이 만든 모든 표가 domain-model.md 에 적혀 있다")
    void domainModelMentionsEveryTable() throws IOException {
        String model = Files.readString(DOMAIN_MODEL, StandardCharsets.UTF_8);

        // TreeSet 이라 이름이 정렬돼서 나온다 — 빨갰을 때 무엇이 빠졌는지 읽기 쉽다.
        var tables = new TreeSet<String>();
        try (Stream<Path> files = Files.list(MIGRATION_DIR)) {
            for (Path file : files.toList()) {
                if (!file.getFileName().toString().endsWith(".sql")) {
                    continue;
                }
                Matcher created = CREATE_TABLE.matcher(
                        Files.readString(file, StandardCharsets.UTF_8));
                while (created.find()) {
                    tables.add(created.group(1).toLowerCase(Locale.ROOT));
                }
            }
        }

        assertThat(tables)
                .describedAs("마이그레이션에서 create table 을 하나도 못 읽었다. "
                        + MIGRATION_DIR + " 경로가 바뀌었다")
                .isNotEmpty();

        // **백틱까지 본다.** 표 이름이 산문에 그냥 섞여 있으면 「다룬다」고 볼 수 없고,
        // `seller` 처럼 짧은 이름은 다른 낱말 안에서 우연히 맞는다.
        List<String> unmentioned = tables.stream()
                .filter(table -> !model.contains("`" + table + "`"))
                .toList();

        assertThat(unmentioned)
                .describedAs("마이그레이션이 만든 표를 domain-model.md 가 안 부른다. "
                        + "표를 더한 청크는 그 문서도 같이 고친다 — 이름은 백틱으로 감싼다")
                .isEmpty();
    }
}
