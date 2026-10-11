import { existsSync, readFileSync, readdirSync, statSync } from "node:fs";
import { dirname, join, relative, resolve } from "node:path";

import ts from "typescript";
import { describe, expect, it } from "vitest";

/**
 * 서버 컴포넌트가 `"use client"` 모듈의 **함수·상수**를 안 가져오는지 본다(`Q249`, `frontend-rules.md`
 * 「서버 컴포넌트가 기본이다」).
 *
 * <p>`"use client"` 모듈의 export 는 서버에서 **값이 아니라 클라이언트 참조**다. 컴포넌트는 그려 줄 수
 * 있지만 함수를 부르면 Next 가 그리는 순간에 「Attempted to call … from the server」로 화면을 깬다.
 * `lint`·`tsc`·`npm test`·`next build` 가 다 초록이라 정산서 상세가 3주 깨져 있었다(`Q244b` 가 찾았다).
 *
 * <p><b>eslint 규칙으로 안 짰다.</b> 가져온 모듈의 파일을 열어 지시문을 봐야 하는데 린트 규칙은 파일 하나만 본다 —
 * `NO_USE_CLIENT_ROUTE`(`Q228`)가 지시문만 보는 것과 같은 한계다.
 *
 * <p>판정: 지시문이 없는 파일(서버 쪽에서 쓰일 수 있는 것)이 상대 경로·`@/` 로 가져온 모듈이 `"use client"` 로
 * 시작하면, 가져온 이름 중 **소문자로 시작하는 것**(함수·상수)과 `* as` 를 모은다. 대문자(컴포넌트)·`import type` 은 허용.
 *
 * <h2>무엇을 못 보나</h2>
 *
 * <ul>
 *   <li><b>재수출</b> — 배럴(`index.ts`)이 클라이언트 모듈을 다시 내보내면 그 너머를 안 따라간다. 지금 `src` 에 배럴이 없다</li>
 *   <li><b>기본 가져오기</b> — `import x from` 의 이름은 가져오는 쪽이 짓는다. 컴포넌트가 아닌 기본 export 는 안 잡는다</li>
 *   <li><b>대문자로 시작하는 상수·값</b> — 이름으로 컴포넌트와 못 가른다. `STATUS_LABELS` 나 PascalCase 값 객체를 클라이언트 모듈에서
 *       내보내 서버가 가져오면 지나간다. 지금 클라이언트 모듈에 그런 export 는 0건이다</li>
 *   <li><b>동적 `import()`</b> — 가져오기 선언만 걷는다</li>
 *   <li><b>클라이언트에서만 쓰이는 지시문 없는 파일</b> — 그 파일은 사실 클라이언트라 함수를 불러도 되는데 여기서는 걸린다.
 *       지금 0건이고 생기면 그 파일에 지시문을 단다</li>
 * </ul>
 */
/** vitest 는 `frontend/` 에서 돈다 */
const SRC = join(process.cwd(), "src");

/** 확장자를 적은 가져오기(`"./x.tsx"`)도 풀리게 빈 문자열을 먼저 본다 */
const EXTENSIONS = ["", ".ts", ".tsx", "/index.ts", "/index.tsx"];

function sourceFilesUnder(dir: string): string[] {
  const found: string[] = [];
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) {
      if (path !== join(SRC, "test")) {
        found.push(...sourceFilesUnder(path));
      }
    } else if (/\.tsx?$/.test(entry) && !/\.test\.tsx?$/.test(entry) && !entry.endsWith(".d.ts")) {
      found.push(path);
    }
  }
  return found;
}

/** 메시지에 쓰는 경로. Windows 에서도 `/` 로 적는다 */
function shown(path: string): string {
  return relative(SRC, path).split("\\").join("/");
}

function parse(path: string): ts.SourceFile {
  return ts.createSourceFile(path, readFileSync(path, "utf8"), ts.ScriptTarget.Latest, true);
}

/** 파일 머리의 지시문 묶음에 `"use client"` 가 있나 */
function isClientModule(source: ts.SourceFile): boolean {
  for (const statement of source.statements) {
    if (!ts.isExpressionStatement(statement) || !ts.isStringLiteral(statement.expression)) {
      return false;
    }
    if (statement.expression.text === "use client") {
      return true;
    }
  }
  return false;
}

/** 상대 경로·`@/` 만 푼다. `@/` 는 `tsconfig.json` 을 안 읽고 `src/` 로 고정한다 */
function resolveImport(from: string, specifier: string): string | null {
  let base: string;
  if (specifier.startsWith("@/")) {
    base = join(SRC, specifier.slice(2));
  } else if (specifier.startsWith(".")) {
    base = resolve(dirname(from), specifier);
  } else {
    return null;
  }
  for (const extension of EXTENSIONS) {
    if (existsSync(base + extension) && statSync(base + extension).isFile()) {
      return base + extension;
    }
  }
  return null;
}

/** 서버 쪽 파일이 클라이언트 모듈에서 가져온 함수·상수 */
function clientValueImports(): string[] {
  const clientCache = new Map<string, boolean>();
  const isClient = (path: string) => {
    if (!clientCache.has(path)) {
      clientCache.set(path, isClientModule(parse(path)));
    }
    return clientCache.get(path)!;
  };

  const violations: string[] = [];
  for (const path of sourceFilesUnder(SRC)) {
    const source = parse(path);
    if (isClientModule(source)) {
      continue;
    }
    for (const statement of source.statements) {
      if (!ts.isImportDeclaration(statement) || !ts.isStringLiteral(statement.moduleSpecifier)) {
        continue;
      }
      const clause = statement.importClause;
      if (!clause || clause.isTypeOnly) {
        continue;
      }
      const target = resolveImport(path, statement.moduleSpecifier.text);
      if (!target || !isClient(target)) {
        continue;
      }
      const names: string[] = [];
      const bindings = clause.namedBindings;
      if (bindings && ts.isNamespaceImport(bindings)) {
        names.push(`* as ${bindings.name.text}`);
      } else if (bindings) {
        for (const element of bindings.elements) {
          const imported = (element.propertyName ?? element.name).text;
          if (!element.isTypeOnly && /^[a-z]/.test(imported)) {
            names.push(imported);
          }
        }
      }
      for (const name of names) {
        violations.push(`${shown(path)} → ${shown(target)}: ${name}`);
      }
    }
  }
  return violations;
}

describe("서버·클라이언트 경계", () => {
  it("서버 쪽 파일이 \"use client\" 모듈에서 함수·상수를 안 가져온다", () => {
    expect(clientValueImports()).toEqual([]);
  });

  it("클라이언트 모듈을 실제로 찾는다 — 0개면 위 시험이 아무것도 안 잰다", () => {
    const clients = sourceFilesUnder(SRC).filter((path) => isClientModule(parse(path)));
    expect(clients.length).toBeGreaterThan(0);
  });
});
