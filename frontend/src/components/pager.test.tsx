import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { Pager, pageNumberOf } from "./pager";

/**
 * 쪽 넘김이 조건을 잃지 않나(`13c-1`).
 *
 * <p><b>목록 화면이 늘 때마다 사본이 늘던 것을 하나로 모았다.</b> 모으면 어긋날 자리는
 * 사라지는데, 대신 <b>한 곳이 틀리면 목록 전부가 틀린다</b> — 그래서 여기가 검증 자리다.
 *
 * <p><b>데모 데이터로는 이 컴포넌트를 못 밟는다.</b> 상품도 주문도 한 쪽에 다 들어가서
 * 화면에서는 {@code null} 만 나온다. 눈으로 볼 수 없는 것은 테스트가 본다(`D15`).
 */
describe("목록 쪽 넘김", () => {
  it("쪽이 하나뿐이면 아무것도 안 그린다", () => {
    const { container } = render(
      <Pager page={0} lastPage={0} total={3} basePath="/products" label="상품 목록" unit="개" />,
    );

    // 쪽이 하나인데 「1 / 1쪽」을 그리면 넘길 수 있는 것처럼 보인다.
    expect(container).toBeEmptyDOMElement();
  });

  it("첫 쪽 링크에는 page 가 안 붙는다", () => {
    render(
      <Pager page={1} lastPage={2} total={50} basePath="/orders" label="주문 목록" unit="건" />,
    );

    // 같은 목록이 주소 둘을 가지면 링크를 공유했을 때 어느 쪽이 정본인지가 안 갈린다.
    expect(screen.getByRole("link", { name: "1쪽" })).toHaveAttribute("href", "/orders");
    expect(screen.getByRole("link", { name: "3쪽" })).toHaveAttribute("href", "/orders?page=2");
  });

  it("거른 조건이 쪽을 넘겨도 남는다", () => {
    render(
      <Pager
        page={0}
        lastPage={2}
        total={50}
        basePath="/seller/orders"
        label="주문 목록"
        unit="건"
        params={{ sellerId: "7", sort: undefined }}
      />,
    );

    // **이것이 이 컴포넌트를 만든 이유다.** 조건을 안 실으면 셀러 하나로 걸러 놓고
    // 2쪽을 누른 사람에게 거름이 풀린 목록이 나간다.
    expect(screen.getByRole("link", { name: "2쪽" })).toHaveAttribute(
      "href",
      "/seller/orders?sellerId=7&page=1",
    );

    // 값이 없는 조건은 안 싣는다. 빈 값을 실으면 주소가 조건마다 길어지고 캐시 키가 갈린다.
    expect(screen.getByRole("link", { name: "1쪽" })).toHaveAttribute(
      "href",
      "/seller/orders?sellerId=7",
    );
  });

  it("지금 쪽을 색이 아니라 값으로도 알린다", () => {
    render(
      <Pager page={1} lastPage={3} total={80} basePath="/orders" label="주문 목록" unit="건" />,
    );

    // 강조 색만으로 알리면 그 색을 못 보는 사람에게는 아무 표시가 없다(`D20`·WCAG 1.4.1).
    expect(screen.getByRole("link", { name: "2쪽" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByText(/전체 80건 중 2 \/ 4쪽/)).toBeInTheDocument();
  });

  it("갈 곳이 없는 방향은 링크가 아니다", () => {
    render(
      <Pager page={0} lastPage={2} total={50} basePath="/orders" label="주문 목록" unit="건" />,
    );

    // 비활성 링크로 두면 보조기술이 읽고 만질 수 없다고 말한다 — 안 그리면 없는 것이다.
    expect(screen.queryByRole("link", { name: "이전 쪽" })).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "다음 쪽" })).toBeInTheDocument();
  });

  it("목록마다 다른 이름으로 불린다", () => {
    render(
      <Pager page={0} lastPage={2} total={50} basePath="/products" label="상품 목록" unit="개" />,
    );

    // 한 쪽에 목록이 둘이면 보조기술이 둘을 못 가른다.
    expect(screen.getByRole("navigation", { name: "상품 목록 쪽 넘기기" })).toBeInTheDocument();
  });
});

/**
 * 주소에서 온 쪽 번호.
 *
 * <p>사람이 주소를 직접 고치고 오래된 링크가 남는다. <b>이상한 값에 오류를 내지 않는다</b> —
 * 링크를 잘못 받은 사람에게 오류 화면을 주는 것보다 목록을 주는 편이 낫다.
 */
describe("주소의 쪽 번호", () => {
  it("쓸 수 있는 값만 통과한다", () => {
    expect(pageNumberOf("2")).toBe(2);
  });

  it("나머지는 전부 첫 쪽이다", () => {
    // 음수는 서버에 그대로 넘기면 목록 조회가 깨진다.
    expect(pageNumberOf("-1")).toBe(0);
    expect(pageNumberOf("1.5")).toBe(0);
    expect(pageNumberOf("셋")).toBe(0);
    expect(pageNumberOf(undefined)).toBe(0);
  });
});
