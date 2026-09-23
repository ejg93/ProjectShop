/**
 * 환불의 열거값을 화면 문구로 바꾼다(`Q185`).
 *
 * <p><b>관리자 대기열과 구매자 주문 상세가 같은 값을 그린다</b> — 화면마다 적으면 한쪽만 고치는 날이 오고,
 * 그때 같은 환불이 화면에 따라 다른 말로 보인다({@link ./settlement-text} 와 같은 판단).
 *
 * <p><b>모르는 값은 그대로 보여준다</b>(`D5` 「모르는 열거값은 무시한다」).
 */

/** 환불 상태(`V23`). {@code refund.status} 에 붙는다 */
const REFUND_STATUS: Record<string, string> = {
  REQUESTED: "승인 대기",
  APPROVED: "환불 완료",
  REJECTED: "반려됨",
};

/**
 * 환불 사유(`V23`). <b>사유마다 환급 기한을 세는 날이 다르다</b>(`RefundService`) — 대기열에서 사유가 보여야
 * 기한이 왜 그날인지 읽힌다.
 */
const REFUND_REASON: Record<string, string> = {
  CANCELLED: "주문 취소",
  SUPPLY_FAILED: "공급 불가",
  ADMIN_CANCELLED: "관리자 취소",
  WITHDRAWAL: "청약철회(반품)",
  PAYMENT_ERROR: "결제 오류",
};

export function refundStatusText(status: string): string {
  return REFUND_STATUS[status] ?? status;
}

export function refundReasonText(reason: string): string {
  return REFUND_REASON[reason] ?? reason;
}
