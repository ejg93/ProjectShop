package com.projectshop.shop.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 역할을 주거나 회수하는 <b>모든 경로</b>가 판정 캐시를 버리나(`16`).
 *
 * <p>판정 규칙이 사용자마다 60초 캐시된다({@code PermissionCacheConfig}). <b>회수하고 캐시를
 * 안 버리면 그 60초 동안 회수가 안 먹는다</b> — 화면은 「뺐다」고 하고 서버는 아직 허용한다.
 * <b>권한을 준 것보다 못 뺀 것이 사고다.</b>
 *
 * <p><b>새 경로가 생기는 것을 막는 것이 이 시험의 일이다.</b> 지금 있는 둘이 부르는지는
 * 그 클래스의 시험이 보고, 여기서는 <b>원천에 `user_role` 을 쓰는 코드가 또 생겼나</b>를 센다 —
 * 생기면 {@link #EXEMPT} 에 근거를 적거나 캐시를 버려야 지나간다.
 *
 * <p><b>못 보는 것</b>: 부르긴 부르는데 <b>엉뚱한 사람의 캐시</b>를 버리는 것. 그것까지 보려면
 * 판정을 실제로 지나야 하고, 그 층은 {@code UserRoleServiceTest} 다(`D15`).
 */
@DisplayName("역할을 바꾸는 경로가 캐시를 버린다")
class UserRoleWriteTest {

    private static final Path MAIN = Path.of("src/main/java");

    /** 쓰는데 캐시를 안 버려도 되는 자리와 그 근거 */
    private static final Map<String, String> EXEMPT = Map.of(
            "SignupService.java",
            "방금 만든 계정에 기본 역할을 준다. 그 사용자의 캐시가 아직 없어서 버릴 것이 없다");

    /** 이 글자가 있으면 `user_role` 에 쓰는 코드다 */
    private static final List<String> WRITES = List.of("insert into user_role", "delete from user_role");

    @Test
    @DisplayName("`user_role` 에 쓰는 모든 파일이 캐시를 버리거나 면제에 적혀 있다")
    void everyWriterEvictsOrIsExempt() throws IOException {
        List<Path> writers = sourcesContainingWrite();

        assertThat(writers)
                .as("`user_role` 에 쓰는 자리가 하나도 없을 리 없다. 0 이면 이 대조가 아무것도 안 막는다")
                .isNotEmpty();

        List<String> missing = writers.stream()
                .filter(path -> !EXEMPT.containsKey(path.getFileName().toString()))
                .filter(path -> !evicts(path))
                .map(path -> path.getFileName().toString())
                .toList();

        assertThat(missing)
                .as("역할을 바꾸면서 `PermissionRuleLoader.evict` 를 안 부른다. "
                        + "안 부르면 TTL 60초 동안 회수가 안 먹는다 — 부르거나 EXEMPT 에 근거를 적는다")
                .isEmpty();
    }

    /** 면제 목록이 **실재하는 파일**을 가리키나. 이름이 바뀌면 면제가 조용히 넓어진다 */
    @Test
    @DisplayName("면제에 적힌 파일이 실재한다")
    void exemptFilesExist() throws IOException {
        List<String> writers = sourcesContainingWrite().stream()
                .map(path -> path.getFileName().toString())
                .toList();

        assertThat(writers)
                .as("면제에 적힌 파일이 이제 `user_role` 에 안 쓴다. 그러면 그 줄을 지운다")
                .containsAll(EXEMPT.keySet());
    }

    private static List<Path> sourcesContainingWrite() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(UserRoleWriteTest::writesUserRole)
                    .toList();
        }
    }

    private static boolean writesUserRole(Path path) {
        String source = read(path);
        return WRITES.stream().anyMatch(source::contains);
    }

    private static boolean evicts(Path path) {
        return read(path).contains(".evict(");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("원천을 못 읽었다: " + path, e);
        }
    }
}
