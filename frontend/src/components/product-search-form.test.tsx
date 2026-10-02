import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { ProductSearchForm, SEARCH_TERM_MAX_LENGTH } from "./product-search-form";

/**
 * 상품 검색창(`61b`). 검색어가 주소의 `q` 로 가는지, 서버 상한과 같은 `maxLength` 를 쥐는지, 라벨이 붙었는지를 잰다.
 *
 * **주소로 가야 상태가 남는다**(`D24`) — 폼이 `post` 거나 다른 이름으로 보내면 목록이 검색어를 못 읽고,
 * 뒤로 가기·링크 공유가 검색 전으로 돌아간다.
 */
describe("상품 검색창", () => {
  it("검색어를 /products 의 q 로 GET 한다", () => {
    render(<ProductSearchForm />);

    const form = screen.getByRole("search");
    expect(form).toHaveAttribute("method", "get");
    expect(form).toHaveAttribute("action", "/products");
    expect(screen.getByRole("searchbox", { name: "상품 검색" })).toHaveAttribute("name", "q");
  });

  /** 서버와의 대조는 `ScreenLengthTest` 가, 목록 화면의 자르기와의 대조는 여기가 든다 */
  it("칸의 상한이 목록 화면이 자르는 상한과 같다", () => {
    render(<ProductSearchForm />);

    expect(screen.getByRole("searchbox", { name: "상품 검색" })).toHaveAttribute(
      "maxLength",
      String(SEARCH_TERM_MAX_LENGTH),
    );
  });

  it("주소에 있던 검색어를 칸에 채워 둔다", () => {
    render(<ProductSearchForm q="운동화" />);

    expect(screen.getByRole("searchbox", { name: "상품 검색" })).toHaveValue("운동화");
  });
});
