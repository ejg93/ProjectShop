import { fireEvent, render, screen } from "@testing-library/react";
import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ErrorScreen } from "@/components/error-screen";
import { expectNoAxeViolations } from "@/test/axe";

import RouteError from "./error";
import GlobalError from "./global-error";

/**
 * 레이아웃까지 터져도 우리 오류 화면이 뜨나(`Q263`).
 *
 * <p>이 파일이 없을 때 백엔드가 죽으면 <b>모든 화면이 본문 없는 500</b> 이었다(`68` 실험 C).
 * 문서 전체를 대신 그리는 것과 본문이 할 일을 같이 잰다 — 본문은 {@code error.tsx} 도 쓴다.
 */
describe("오류 화면", () => {
  // 화면이 원인을 콘솔에 남긴다. 시험 출력을 덮지 않게 막는다.
  beforeEach(() => {
    vi.spyOn(console, "error").mockImplementation(() => {});
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("문서를 직접 그리고 한국어라고 밝힌다", () => {
    // 루트 레이아웃이 빠지므로 lang 이 없으면 화면낭독기가 한글을 영어 엔진으로 읽는다(`D20`).
    const html = renderToStaticMarkup(<GlobalError error={new Error("레이아웃")} retry={() => {}} />);

    expect(html).toMatch(/^<html lang="ko"/);
    expect(html).toContain("화면을 여는 데 실패했습니다");
  });

  it("오류 번호를 보여 주고 「다시 시도」가 다시 불러온다", async () => {
    const retry = vi.fn();
    const { container } = render(
      <ErrorScreen error={Object.assign(new Error("서버"), { digest: "1234567890" })} retry={retry} />,
    );

    expect(screen.getByRole("heading", { name: "화면을 여는 데 실패했습니다" })).toBeInTheDocument();
    expect(screen.getByText("1234567890")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    expect(retry).toHaveBeenCalledOnce();

    await expectNoAxeViolations(container);
  });

  it("셸 안의 오류 경계도 「다시 시도」에 retry 를 넘긴다", () => {
    // `reset` 은 서버에서 다시 안 불러와서 백엔드가 살아난 뒤에도 같은 화면이 남는다(Next 16.3, `Q263`).
    // 본문만 재면 경계가 무엇을 넘기는지 모른다(PR #99 리뷰).
    const retry = vi.fn();
    const reset = vi.fn();
    const props = { error: new Error("서버"), retry, reset } as Parameters<typeof RouteError>[0];
    render(<RouteError {...props} />);

    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));

    expect(retry).toHaveBeenCalledOnce();
    expect(reset).not.toHaveBeenCalled();
  });

  it("원인 문구를 화면에 안 적는다", () => {
    // 서버가 준 문구에 SQL 이나 경로가 섞일 수 있다(`D14`).
    render(<ErrorScreen error={new Error("select * from app_user")} retry={() => {}} />);

    expect(screen.queryByText(/select/)).toBeNull();
  });
});
