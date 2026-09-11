/**
 * 모든 화면 테스트가 같이 쓰는 준비(`Q9`).
 *
 * <p>`jest-dom` 의 단언을 들인다 — `toBeInTheDocument` 처럼 <b>깨진 이유가 문장으로 나오는</b>
 * 단언이 여기서 온다. 없으면 `expect(el).not.toBeNull()` 로 쓰게 되고,
 * 실패했을 때 「무엇이 없었나」가 안 드러난다(`D15`).
 */
import "@testing-library/jest-dom/vitest";

/**
 * axe 단언을 들인다(`Q21`). `jsx-a11y` 는 정적이라 <b>JSX 에 적힌 것</b>만 본다 —
 * 조건부로 생긴 DOM, 상태에 따라 바뀌는 `aria-*`, 실제로 이어진 이름은 못 본다.
 * 그 자리를 실행해서 보는 것이 axe 다.
 */
import { expect } from "vitest";
import * as matchers from "vitest-axe/matchers";

expect.extend(matchers);
