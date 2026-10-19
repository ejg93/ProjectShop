import { readdirSync, readFileSync } from "node:fs";
import { join, relative, resolve } from "node:path";
import { describe, expect, it } from "vitest";

const SRC = resolve(import.meta.dirname, "..");
const TOKENS = join(SRC, "app", "globals.css");

function cssFiles(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) {
      return cssFiles(path);
    }
    return /\.css$/.test(entry.name) ? [path] : [];
  });
}

/**
 * 선언 값(`:` 뒤)의 색. `#add` 같은 id 선택자는 색이 아니다.
 * 개행을 안 넘는다 — `[^;{}]` 가 개행을 먹어 `a:hover,` 다음 줄 선택자의 `#add` 를 색으로 봤다(PR #102 리뷰 봇).
 */
export const COLOR_IN_DECLARATION = /:[^;{}\n]*(#[0-9a-fA-F]{3,8}\b|\b(rgba?|hsla?|oklch)\()/;

/**
 * 색 값은 `globals.css` 한 곳에 있다(`Q278`, frontend-rules.md 「색·크기는 토큰으로」).
 *
 * <p><b>린트는 `.css` 를 안 본다</b> — 화면 소스의 클래스 문자열만 본다. 다른 css 파일에 색을 적으면 다크 값이 없는 색이
 * 그 그물 밖으로 들어온다. 지금 `globals.css` 밖의 css 파일이 0개라 생기는 순간 여기서 빨개진다.
 */
describe("토큰", () => {
  it("globals.css 밖의 css 파일에 색 값이 없다", () => {
    const offenders = cssFiles(SRC)
      .filter((path) => path !== TOKENS)
      .filter((path) => COLOR_IN_DECLARATION.test(readFileSync(path, "utf8")))
      .map((path) => relative(SRC, path));

    expect(offenders).toEqual([]);
  });

  it("선택자 줄의 id 는 색으로 안 본다", () => {
    expect(COLOR_IN_DECLARATION.test("a:hover,\n#add {\n  display: block;\n}")).toBe(false);
  });

  it("선언 값의 hex 는 색으로 본다", () => {
    expect(COLOR_IN_DECLARATION.test(".x { color: #add; }")).toBe(true);
  });
});
