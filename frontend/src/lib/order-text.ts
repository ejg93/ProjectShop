/**
 * 주문의 상태·동작을 화면 문구로 바꾼다.
 *
 * <p><b>주문 화면군 안에 있던 것을 옮겨 왔다</b>(`13c-2`). 셀러 화면이 두 번째 사용자고,
 * 거기 두고 가져다 쓰면 <b>화면 하나가 다른 화면군의 조각을 부르는 모양</b>이 된다 —
 * {@link ./format} 이 `13e` 에서 같은 판단을 했고, `Field` 를 `components/` 로 옮긴 것도 같다.
 *
 * <p><b>목록·상세·동작 버튼이 같은 값을 그린다.</b> 화면마다 적으면 한쪽만 고치는 날이 오고,
 * 그때 같은 상태가 화면에 따라 다른 말로 보인다.
 *
 * <p><b>모르는 값은 그대로 보여준다</b>(`D5` 「모르는 열거값은 무시한다」).
 * 서버가 상태를 하나 늘려도 화면이 안 깨지고, 배포를 기다리지 않아도 된다.
 *
 * <p>동작의 <b>라벨</b>은 여기 없다. 무엇이라 부르고 무엇을 묻느냐가 관객마다 달라서다 —
 * 사는 사람의 「주문 취소」와 셀러의 「발송」은 같은 표에 못 들어간다. 그 표는 화면군이 든다.
 */

/** 결제 상태(`D7`). `shop_order` 에 붙는다 */
const PAYMENT_STATUS: Record<string, string> = {
  PAYMENT_PENDING: "결제 대기",
  PAID: "결제 완료",
  PAYMENT_EXPIRED: "결제 시간 만료",
  PAYMENT_FAILED: "결제 실패",
};

/** 배송 상태(`D7`). `seller_order` 에 붙는다 */
const SHIPMENT_STATUS: Record<string, string> = {
  PREPARING: "배송 준비 중",
  SHIPPING: "배송 중",
  DELIVERED: "배송 완료",
  CONFIRMED: "구매 확정",
  CANCELLED: "취소됨",
  RETURN_REQUESTED: "반품 접수",
  RETURNED: "반품 완료",
};

/**
 * 반품이 무엇으로 들어왔나(`V29`).
 *
 * <p><b>사유가 무엇이냐로 기한과 비용 부담이 갈린다</b> — 하자 반품은 3개월이고 반환 비용을
 * 셀러가 진다(전자상거래법 제17조제3항·제18조제9항, `D2` R3). 셀러 화면이 이것을 그려야
 * 단순 변심으로 보고 거절하는 일이 안 생긴다.
 */
const RETURN_REASON: Record<string, string> = {
  CHANGE_OF_MIND: "단순 변심",
  DEFECT: "표시·광고와 다름",
};

export function paymentStatusText(status: string): string {
  return PAYMENT_STATUS[status] ?? status;
}

export function shipmentStatusText(status: string): string {
  return SHIPMENT_STATUS[status] ?? status;
}

export function returnReasonText(reason: string): string {
  return RETURN_REASON[reason] ?? reason;
}

/**
 * 이력 한 줄의 상태. <b>두 층이 한 목록에 섞여 들어온다</b>({@code OrderQuery.HistoryEntry}) —
 * 결제 층과 배송 층의 값이 안 겹치므로 둘을 합쳐 찾는다.
 */
export function statusText(status: string): string {
  return PAYMENT_STATUS[status] ?? SHIPMENT_STATUS[status] ?? status;
}

/**
 * 동작 이름을 경로로 바꾼다.
 *
 * <p><b>표를 따로 안 든다</b>(`D5` 「`allowed_actions` 는 밑줄이 없다」, `D24` 「화면은 동작↔경로
 * 표를 안 든다」). 소문자로 바꾸고 밑줄을 하이픈으로 바꾸면 경로가 된다 —
 * 표로 들면 서버가 동작을 늘릴 때 한쪽만 고쳐진다.
 */
export function actionPath(sellerOrderNumber: string, action: string): string {
  return `/api/shipments/${sellerOrderNumber}/${action.toLowerCase().replace(/_/g, "-")}`;
}
