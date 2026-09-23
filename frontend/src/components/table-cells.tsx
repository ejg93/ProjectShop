/**
 * 표의 머리 칸과 몸 칸(`41a`).
 *
 * <p><b>화면마다 사본이 하나씩 있었다</b> — 감사 기록·받은 주문·내 상품·정산서가 저마다 {@code Th}·{@code Td} 를 두고
 * 간격과 굵기가 셋으로 갈려 있었다. 넷을 여기로 모았고(`Q189`) 사본이 다시 서면 {@code table-cells.test.ts} 가 막는다.
 *
 * <p>{@code scope="col"} 을 여기서 박는다 — 머리 칸마다 적게 하면 한 표가 빠뜨리고, 그 표는 화면 읽기 프로그램이
 * 칸 이름을 못 읽는다(WCAG 1.3.1).
 */
export function Th({ children, align }: { children: React.ReactNode; align?: "right" }) {
  return (
    <th scope="col" className={`py-2 pr-3 font-normal ${align === "right" ? "text-right" : ""}`}>
      {children}
    </th>
  );
}

export function Td({
  children,
  align,
  muted = false,
}: {
  children: React.ReactNode;
  align?: "right";
  muted?: boolean;
}) {
  return (
    <td
      className={`
        py-2 pr-3 align-top
        ${align === "right" ? "text-right tabular-nums" : ""}
        ${muted ? "text-text-muted" : ""}
      `}
    >
      {children}
    </td>
  );
}
