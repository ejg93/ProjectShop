/**
 * 주문 화면이 같이 쓰는 표기.
 *
 * <p><b>목록·상세·동작 버튼이 같은 값을 그린다.</b> 화면마다 적으면 한쪽만 고치는 날이 오고,
 * 그때 같은 상태가 화면에 따라 다른 말로 보인다.
 *
 * <p><b>모르는 값은 그대로 보여준다</b>(`D5` 「모르는 열거값은 무시한다」).
 * 서버가 상태를 하나 늘려도 화면이 안 깨지고, 배포를 기다리지 않아도 된다.
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

export function paymentStatusText(status: string): string {
  return PAYMENT_STATUS[status] ?? status;
}

export function shipmentStatusText(status: string): string {
  return SHIPMENT_STATUS[status] ?? status;
}

/**
 * 이력 한 줄의 상태. <b>두 층이 한 목록에 섞여 들어온다</b>(`OrderQuery.HistoryEntry`) —
 * 결제 층과 배송 층의 값이 안 겹치므로 둘을 합쳐 찾는다.
 */
export function statusText(status: string): string {
  return PAYMENT_STATUS[status] ?? SHIPMENT_STATUS[status] ?? status;
}

/**
 * 할 수 있는 동작(`8a`·`11c-3b`).
 *
 * @param label   버튼에 쓰는 말
 * @param confirm 누르기 전에 무엇이 사라지는지 묻는다(`D20` 「되돌릴 수 없는 조작」).
 *                <b>셋 다 되돌릴 수 없다</b> — 취소는 주문이 닫히고, 확정은 청약철회를 포기하는 것이며,
 *                반품 접수는 접수 자체를 무를 수 없다
 */
export type OrderAction = { label: string; confirm: string };

export const ORDER_ACTIONS: Record<string, OrderAction> = {
  // 환불을 약속하지 않는다. **환급 경로가 아직 없다**(`12a` 미착수, `D2` R5) —
  // 화면이 지금 없는 것을 말하면 그건 거짓이고, 그 청크가 서면 여기에 기한을 적는다.
  CANCEL: {
    label: "주문 취소",
    confirm: "취소하시면 이 판매자의 상품이 주문에서 빠집니다. 취소하시겠습니까?",
  },
  CONFIRM: {
    label: "구매 확정",
    confirm: "확정하시면 청약철회를 하실 수 없습니다. 상품을 확인하셨습니까?",
  },
  REQUEST_RETURN: {
    label: "반품 신청",
    confirm: "반품을 신청하시면 취소하실 수 없습니다. 신청하시겠습니까?",
  },
};

/**
 * 동작 이름을 경로로 바꾼다.
 *
 * <p><b>표를 따로 안 든다</b>(`D5` 「`allowed_actions` 는 밑줄이 없다」).
 * 소문자로 바꾸고 밑줄을 하이픈으로 바꾸면 경로가 된다 —
 * 표로 들면 서버가 동작을 늘릴 때 한쪽만 고쳐진다.
 */
export function actionPath(sellerOrderNumber: string, action: string): string {
  return `/api/shipments/${sellerOrderNumber}/${action.toLowerCase().replace(/_/g, "-")}`;
}

/** 부가세가 이미 포함된 값이다(`D8`). 화면이 다시 더하지 않는다 */
export function priceText(amount: number): string {
  return `${amount.toLocaleString("ko-KR")}원`;
}
