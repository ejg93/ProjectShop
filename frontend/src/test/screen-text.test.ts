import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative } from "node:path";

import ts from "typescript";
import { describe, expect, it } from "vitest";

/**
 * 화면 문구가 **반말로 새지 않는지** 본다(`Q34`, `screen-rules.md` 「화면 문구는 존댓말이다」).
 *
 * <p><b>`doc-lint.sh` 의 거울이다.</b> 그쪽은 개발자가 읽는 글(문서·주석·커밋)에서 **존댓말**을
 * 막고, 이쪽은 사용자가 읽는 글에서 **반말**을 막는다. 같은 저장소에 정반대 규칙이 둘 있고
 * 섞이면 양쪽이 다 깨진다.
 *
 * <p><b>정규식으로는 못 잰다.</b> 파일을 글자로 훑으면 주석이 같이 걸려 341건이 뜬다
 * (2026-09-13 실측). 그래서 `typescript` 의 AST 로 걷는다 — <b>주석은 AST 에 문자열로 안 남아서
 * 저절로 빠진다.</b>
 *
 * <p><b>`.tsx` 는 한글이 든 문자열을 전부 걷는다.</b> 분할표는 JSX 안의 글과 속성 넷만
 * 걷기로 했는데, 재 보니 <b>화면 문구 대부분이 도우미 함수 안에 있었다</b> —
 * `login-form.tsx` 의 오류 문구가 그렇다. 좁게 걸으면 269개, 넓히면 575개고
 * <b>둘 다 위반이 0이라 넓히는 값이 공짜다</b>(2026-09-14 실측).
 *
 * <p><b>개발자용 한글 문자열이 생기면 오인한다.</b> `new Error("세션이 없다")` 같은 것은
 * 개발자가 읽는 글이라 평서형이 맞는데 이 테스트가 화면 문구로 본다. 지금 `.tsx` 에 0건이고,
 * 생기면 그 꼴을 빼는 것으로 고친다 — 없는 것을 미리 빼 두지 않는다.
 *
 * <p><b>`습니다`·`합니다` 는 `니다` 라 안 걸린다.</b> 판정이 「`다` 로 끝나는데 앞이 `니` 가
 * 아닌 것」이라 존댓말은 통과하고 평서형만 남는다.
 *
 * <h2>무엇을 못 보나</h2>
 *
 * <ul>
 *   <li><b>변수로 조립한 문장</b> — {@code `${name}님이 왔다`} 의 조각은 각각 보지만
 *       이어 붙인 결과는 못 본다</li>
 *   <li><b>서버가 준 문구</b> — 응답에 실려 오는 글은 여기 없다. 그쪽은 백엔드의
 *       화면 대조 테스트가 본다</li>
 *   <li><b>반말의 다른 꼴</b> — `~했음`·`~하기 바람` 은 안 본다. 실측으로 걸린 것이
 *       `~다` 하나뿐이라 어미 목록을 안 만들었다(사용자 결정 2026-09-14).
 *       하나 더 나오면 그때 목록으로 판다</li>
 * </ul>
 */
/** vitest 는 `frontend/` 에서 돈다 */
const SRC = join(process.cwd(), "src");

/** 화면 표를 문자열로 든 파일. JSX 가 없어서 문자열 리터럴을 통째로 걷는다 */
const TEXT_TABLES = ["lib/order-text.ts", "lib/product-text.ts", "lib/settlement-text.ts"];

/** 사용자에게 읽히는 속성 넷 */
const SCREEN_ATTRS = new Set(["placeholder", "aria-label", "title", "alt"]);

const HANGUL = /\p{Script=Hangul}/u;

/**
 * 반말 평서형. `(?<!니)` 가 `습니다`·`합니다` 를 비켜 간다.
 *
 * <p>문장 끝과 문장 중간을 따로 본다 — 한 문구에 문장이 둘일 때 앞 문장이 반말인 것을
 * 끝만 보면 놓친다.
 */
const BANMAL_END = /(?<!니)다[.!?]?$/;
const BANMAL_MIDDLE = /(?<!니)다[.!?]\s/;

type Phrase = { where: string; text: string };

function screenFilesUnder(dir: string): string[] {
  const found: string[] = [];
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) {
      found.push(...screenFilesUnder(path));
    } else if (entry.endsWith(".tsx") && !entry.endsWith(".test.tsx")) {
      found.push(path);
    }
  }
  return found;
}

/** 식 안에 든 문자열을 전부 꺼낸다 — `{"…"}` · 삼항 · 템플릿 조각 */
function stringsIn(node: ts.Node, take: (node: ts.Node, raw: string) => void): void {
  if (
    ts.isStringLiteral(node) ||
    ts.isNoSubstitutionTemplateLiteral(node) ||
    ts.isTemplateHead(node) ||
    ts.isTemplateMiddle(node) ||
    ts.isTemplateTail(node)
  ) {
    take(node, node.text);
  }
  ts.forEachChild(node, (child) => stringsIn(child, take));
}

function phrasesIn(file: string): Phrase[] {
  const text = readFileSync(file, "utf8");
  const source = ts.createSourceFile(
    file,
    text,
    ts.ScriptTarget.Latest,
    true,
    file.endsWith(".tsx") ? ts.ScriptKind.TSX : ts.ScriptKind.TS,
  );
  const found = new Map<string, Phrase>();

  const take = (node: ts.Node, raw: string) => {
    const phrase = raw.replace(/\s+/g, " ").trim();
    if (!HANGUL.test(phrase)) {
      return;
    }
    const { line } = source.getLineAndCharacterOfPosition(node.getStart(source));
    const where = `${relative(SRC, file).replace(/\\/g, "/")}:${line + 1}`;
    // 같은 자리를 두 경로로 걸을 수 있다(속성의 식은 JsxExpression 으로 한 번 더 온다).
    found.set(`${where}\u0000${phrase}`, { where, text: phrase });
  };

  const visit = (node: ts.Node) => {
    if (ts.isJsxText(node)) {
      take(node, node.text);
    } else if (ts.isJsxAttribute(node) && ts.isIdentifier(node.name) && SCREEN_ATTRS.has(node.name.text)) {
      if (node.initializer) {
        stringsIn(node.initializer, take);
      }
    } else if (ts.isJsxExpression(node) && node.expression) {
      stringsIn(node.expression, take);
    } else {
      // 도우미 함수·상수에 든 문구까지 본다(위 「.tsx 는 한글이 든 문자열을 전부 걷는다」)
      stringsIn(node, take);
    }
    ts.forEachChild(node, visit);
  };
  visit(source);

  return [...found.values()];
}

describe("화면 문구는 존댓말이다", () => {
  const files = [...screenFilesUnder(SRC), ...TEXT_TABLES.map((f) => join(SRC, f))];
  const phrases = files.flatMap(phrasesIn);

  it("훑을 화면이 있다", () => {
    // 경로가 틀리면 0개를 읽고 조용히 통과한다. 그쪽이 규칙이 깨진 것보다 나쁘다.
    expect(files.length).toBeGreaterThan(10);
  });

  it("걷은 문구가 실제 화면 수만큼 있다", () => {
    // AST 를 걷는 방식이라 조용히 아무것도 못 걷는 고장이 난다.
    // 2026-09-14 실측이 575개였고, 그 절반 아래로 떨어지면 걷는 쪽이 고장난 것이다.
    expect(phrases.length).toBeGreaterThan(280);
  });

  it("화면 문구가 반말로 끝나지 않는다", () => {
    const hits = phrases
      .filter(({ text }) => BANMAL_END.test(text) || BANMAL_MIDDLE.test(text))
      .map(({ where, text }) => `${where}  ${text}`);

    expect(
      hits,
      "화면은 사용자가 읽는 것이라 존댓말이다(screen-rules.md). 개발자가 읽는 글의 평서형 규칙은 여기 안 걸린다",
    ).toEqual([]);
  });
});
