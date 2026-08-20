/**
 * 시각을 화면 문구로 바꾼다. <b>시간대를 박아서</b> 서버 설정과 무관하게 같은 날짜를 그린다.
 *
 * <p><b>주문 화면에 있던 것을 옮겨 왔다</b>(`13e`). 마이페이지가 두 번째 사용자고,
 * 거기 두고 가져다 쓰면 화면 하나가 다른 화면군의 조각을 부르는 모양이 된다 —
 * `Field` 를 `components/` 로 옮긴 것과 같은 판단이다.
 *
 * <p>값 표기(`priceText`)는 안 옮겼다. 그쪽은 아직 주문·상품 화면 안에서만 쓰인다.
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
