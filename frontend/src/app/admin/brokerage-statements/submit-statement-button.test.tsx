import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ApiError, api } from "@/lib/api";
import { expectNoAxeViolations } from "@/test/axe";

vi.mock("@/lib/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api")>()),
  api: vi.fn(),
}));
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: () => {} }) }));

import { SubmitStatementButton } from "./submit-statement-button";

const HASH = "b".repeat(64);

afterEach(() => {
  vi.mocked(api).mockReset();
});

/**
 * 「제출 기록」이 본 파일의 해시를 그 분기 주소로 보내고 서버 문구를 옮기는지 본다(`Q457`).
 */
describe("제출 기록 버튼", () => {
  it("누르면 그 주소와 해시로 부른다", async () => {
    vi.mocked(api).mockResolvedValue(undefined);
    render(<SubmitStatementButton statementId={7} fileSha256={HASH} label="2026년 1분기" />);

    fireEvent.click(screen.getByRole("button", { name: /제출 기록/ }));

    await waitFor(() => expect(api).toHaveBeenCalledWith("/api/admin/brokerage-statements/7/submission", {
      method: "POST",
      body: { fileSha256: HASH },
    }));
  });

  it("파일이 바뀌었으면 다시 내려받으라고 알린다", async () => {
    vi.mocked(api).mockRejectedValue(
      new ApiError(409, "tag:projectshop.example,2026:error:brokerage-statement-file-changed", "changed"),
    );
    const { container } = render(
      <SubmitStatementButton statementId={7} fileSha256={HASH} label="2026년 1분기" />,
    );

    fireEvent.click(screen.getByRole("button", { name: /제출 기록/ }));

    expect(await screen.findByRole("alert")).toHaveTextContent("명세가 바뀌었습니다. 다시 내려받아 주세요.");
    await expectNoAxeViolations(container);
  });
});
