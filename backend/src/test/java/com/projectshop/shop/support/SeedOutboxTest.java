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
