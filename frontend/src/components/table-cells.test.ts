import { readdirSync, readFileSync } from "node:fs";
import { join, relative, resolve } from "node:path";
import { describe, expect, it } from "vitest";

const SRC = resolve(import.meta.dirname, "..");

/** 화면 소스를 전부 모은다. 시험 파일은 뺀다 — 픽스처가 칸 이름을 흉내 낼 수 있다 */
function screenSources(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) {
      return screenSources(path);
    }
    return /\.tsx$/.test(entry.name) && !/\.test\.tsx$/.test(entry.name) ? [path] : [];
  });
}

/**
 * 표 조각은 한 벌이다(`Q189`).
 *
 * <p><b>사본이 넷이었고 간격이 셋으로 갈려 있었다</b> — 감사 기록·받은 주문·내 상품·정산서가 저마다 {@code Th}·{@code Td}
 * 를 두고 있어서 다음 표가 어느 사본을 베낄지 고르는 자리가 생겼다. 모은 뒤에 다섯째가 생기면 여기서 빨개진다.
 */
describe("표 조각", () => {
  it("화면이 머리 칸·몸 칸을 따로 정의하지 않는다", () => {
    const copies = screenSources(SRC)
      .filter((path) => !path.endsWith(join("components", "table-cells.tsx")))
      .filter((path) => /^function T[hd]\(/m.test(readFileSync(path, "utf8")))
      .map((path) => relative(SRC, path));

    expect(copies).toEqual([]);
  });
});
