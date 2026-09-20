package com.projectshop.shop.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 시드가 아웃박스를 비우고 끝나나(`Q67`).
 *
 * <p><b>시드는 「없던 일을 있었던 것처럼」 만드는 것이라 그 사건은 사실이 아니다.</b>
 * 데모 시드가 {@code sku_stock} 에 넣으면 트리거 둘을 지나 <b>SKU 수만큼 미발행
 * {@code shop.sku.stock_moved}</b> 가 쌓이고, 발행기가 서면 진짜 재고 이동과 구별 없이 나간다.
 *
 * <p><b>도는 것을 못 잰다.</b> 시드는 {@code local} 프로필에서만 돌고 테스트는 마이그레이션만 태운다 —
 * 컨테이너를 띄워도 시드가 안 도니까 「비었나」를 실물로 물을 자리가 없다.
 * 그래서 <b>글자로 잰다</b>: 시드 묶음 어딘가에 아웃박스를 비우는 문장이 있나.
 *
 * <p><b>그물이 성기다.</b> 그 문장이 <b>가장 나중</b>에 도는지는 번호로만 보장되고,
 * 시드가 그 뒤에 또 사건을 낳으면 안 걸린다. 강제 지점을 더 못 내리는 자리라 그것을 여기 적어 둔다.
 *
 * <p><b>마지막 자리는 옮겨 다닌다.</b> 지금은 {@code V906} 이고 그전에는 {@code V905} 였다(`Q141`).
 * <b>{@code V905} 의 머리 주석은 아직 「시드 중 마지막이다」라고 말한다</b> — 배포 기준점 뒤라
 * {@code migration-immutable.sh} 가 그 파일을 지켜서 못 고친다(`Q51`). 시드를 더할 때는
 * 그 주석이 아니라 <b>번호가 가장 큰 파일</b>을 본다.
 */
@DisplayName("시드와 아웃박스")
class SeedOutboxTest {

    private static final Path SEEDS = Path.of("src", "main", "resources", "db", "seed");

    @Test
    @DisplayName("시드가 아웃박스를 비우고 끝난다")
    void seedClearsTheOutbox() {
        List<Path> files = seedFiles();

        assertThat(files)
                .as("시드가 하나도 없으면 이 대조가 아무것도 안 잰다")
                .isNotEmpty();

        Path last = files.get(files.size() - 1);

        assertThat(readString(last).replace(" ", ""))
                .as("시드가 남긴 사건은 사실이 아니다. 마지막 시드가 outbox_event 를 비운다 (`Q67`)")
                .contains("deletefromoutbox_event");
    }

    /**
     * <b>Flyway 시드만 비우면 반쪽이다</b>(마무리 23차 독립 리뷰).
     * {@code DemoOrderSeeder} 가 그 뒤에 {@code ApplicationRunner} 로 돌면서 진짜 서비스를 태우고,
     * 전이마다 트리거가 아웃박스를 다시 채운다 — <b>처음 쟀을 때 이 자리를 안 봤다.</b>
     *
     * <p>가르는 것은 <b>어떻게 만들었나</b>(SQL 이냐 서비스 호출이냐)가 아니라
     * <b>진짜 일어난 일인가</b>다. 데모가 만든 주문은 둘 다 아니다.
     */
    @Test
    @DisplayName("데모 주문 러너도 아웃박스를 비운다")
    void demoRunnerClearsTheOutbox() {
        Path runner = Path.of("src", "main", "java", "com", "projectshop", "shop",
                "demo", "DemoOrderSeeder.java");

        assertThat(readString(runner).replace(" ", ""))
                .as("이 러너는 Flyway 시드 뒤에 돌면서 사건을 다시 낳는다 (`Q67`)")
                .contains("deletefromoutbox_event");
    }

    /** 번호순. Flyway 가 그 순서로 돌리므로 마지막 파일이 마지막에 돈다. */
    private static List<Path> seedFiles() {
        try (Stream<Path> files = Files.list(SEEDS)) {
            return files.filter(path -> path.toString().endsWith(".sql")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("시드를 못 읽었다: " + SEEDS.toAbsolutePath(), e);
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
