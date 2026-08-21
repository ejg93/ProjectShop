/**
 * 시각을 화면 문구로 바꾼다. <b>시간대를 박아서</b> 서버 설정과 무관하게 같은 날짜를 그린다.
 *
 * <p><b>주문 화면에 있던 것을 옮겨 왔다</b>(`13e`). 마이페이지가 두 번째 사용자고,
 * 거기 두고 가져다 쓰면 화면 하나가 다른 화면군의 조각을 부르는 모양이 된다 —
 * `Field` 를 `components/` 로 옮긴 것과 같은 판단이다.
 *
 * <p><b>값 표기도 뒤따라 왔다</b>(`13c-2`). 「아직 주문·상품 화면 안에서만 쓰인다」고
 * 적어 뒀던 것이 틀린 말이 됐다 — 장바구니·주문서까지 <b>여덟 자리</b>가 각자 적고 있었고,
 * 그중 둘은 함수도 없이 본문에 박혀 있었다.
 */

/**
 * 날짜와 시각. 사건이 일어난 시점처럼 <b>시각이 뜻을 갖는 값</b>에 쓴다.
 *
 * <p>시간대를 박는 이유가 `D10` 이다 — 서버 컴포넌트는 서버의 시간대로 그리므로,
 * 안 박으면 서버 설정에 따라 날짜가 하루 밀린다.
 */
export function dateTimeText(value: string): string {
  return new Date(value).toLocaleString("ko-KR", {
    timeZone: "Asia/Seoul",
    dateStyle: "medium",
    timeStyle: "short",
  });
}

/** 날짜만. 기한이나 가입일처럼 시각이 읽는 사람에게 뜻이 없는 값에 쓴다 */
export function dateText(value: string): string {
  return new Date(value).toLocaleDateString("ko-KR", {
    timeZone: "Asia/Seoul",
    dateStyle: "medium",
  });
}

/**
 * 값. <b>부가세가 이미 포함된 값이다</b>(`D8`) — 화면이 다시 더하지 않는다.
 *
 * <p><b>그 전제가 여덟 자리에 흩어져 있었다</b>(`13c-2`). 흩어져 있으면 `D8` 이 바뀔 때
 * 여덟을 다 찾아야 하고, 하나를 빠뜨리면 그 화면만 다른 금액을 그린다 —
 * 그리고 <b>금액이 틀린 화면은 아무것도 안 깨뜨린 채로 틀린다.</b>
 */
export function priceText(amount: number): string {
  return `${amount.toLocaleString("ko-KR")}원`;
}
