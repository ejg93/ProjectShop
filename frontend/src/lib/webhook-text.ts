/**
 * 웹훅 사건·발송 상태를 화면 문구로 바꾼다(`Q175`).
 *
 * <p><b>모르는 값은 그대로 보여준다</b>(`D5` 「모르는 열거값은 무시한다」) — 서버가 사건을 하나 늘려도 화면이 안 깨진다.
 */

/** 구독할 수 있는 사건 넷(`WebhookEventType`). 등록 폼이 이 순서로 그린다 */
export const WEBHOOK_EVENT_TYPES: Record<string, string> = {
  SELLER_ORDER_STATUS_CHANGED: "주문 묶음의 상태가 바뀜",
  REFUND_STATUS_CHANGED: "환불 상태가 바뀜",
  RETURN_REQUEST_STATUS_CHANGED: "반품 상태가 바뀜",
  SETTLEMENT_PAYOUT_CHANGED: "정산 지급 상태가 바뀜",
};

const DELIVERY_STATUS: Record<string, string> = {
  PENDING: "보낼 차례를 기다림",
  SENT: "보냄",
  FAILED: "실패함 — 자동으로 다시 보내지 않음",
  EXHAUSTED: "여러 번 실패해 멈춤",
};

export function webhookEventText(type: string): string {
  return WEBHOOK_EVENT_TYPES[type] ?? type;
}

export function deliveryStatusText(status: string): string {
  return DELIVERY_STATUS[status] ?? status;
}
