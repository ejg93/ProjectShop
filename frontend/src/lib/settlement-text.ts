/**
 * 정산의 열거값을 화면 문구로 바꾼다.
 *
 * <p><b>목록과 상세가 같은 값을 그린다</b>(`20-1`). 화면마다 적으면 한쪽만 고치는 날이 오고,
 * 그때 같은 정산서가 화면에 따라 다른 말로 보인다 — {@link ./order-text} 와 같은 판단이다.
 *
 * <p><b>모르는 값은 그대로 보여준다</b>(`D5` 「모르는 열거값은 무시한다」).
 * 서버가 값을 하나 늘려도 화면이 안 깨지고, 배포를 기다리지 않아도 된다.
 *
 * <p><b>동작의 라벨은 여기 없다.</b> 무엇이라 부르고 무엇을 묻느냐는 되돌릴 수 없는 조작에
 * 붙는 말이라 버튼을 그리는 자리가 든다({@link ../components/payout-actions}).
 */

/** 지급 상태(`V57`). {@code settlement.payout_status} 에 붙는다 */
const PAYOUT_STATUS: Record<string, string> = {
  PENDING: "지급 대기",
  REQUESTED: "승인 대기",
  PAID: "지급 완료",
  REJECTED: "반려됨",
};

/**
 * 정산 줄의 종류(`V52`).
 *
 * <p><b>되돌림을 「환불」이라 안 적는다.</b> 환불은 고객에게 돈을 돌려준 사건이고
 * 여기 실리는 것은 <b>그것 때문에 정산에서 빼는 금액</b>이라, 같은 말로 적으면
 * 셀러가 자기 정산서에서 환불 건수를 세게 된다.
 */
const ITEM_KIND: Record<string, string> = {
  SALE: "상품 대금",
  SHIPPING_FEE: "배송비",
  COMMISSION: "중개수수료",
  SALE_REVERSAL: "상품 대금 차감",
  COMMISSION_REVERSAL: "수수료 환입",
  CARRYOVER: "이월 조정",
};

/**
 * 누가 공급했나(`V52`). <b>부가가치세법이 요구하는 값이다</b>(`D2` R17).
 *
 * <p>수수료 줄만 공급자가 뒤집힌다 — 그 줄은 플랫폼이 셀러에게 공급한 용역이다.
 */
const SUPPLIER: Record<string, string> = {
  SELLER: "판매자",
  PLATFORM: "프로젝트샵",
};

export function payoutStatusText(status: string): string {
  return PAYOUT_STATUS[status] ?? status;
}

export function settlementItemKindText(kind: string): string {
  return ITEM_KIND[kind] ?? kind;
}

/** 이월 줄은 공급이 아니라 정산끼리의 조정이라 공급자가 없다 */
export function settlementSupplierText(supplier: string | null): string {
  if (supplier === null) {
    return "해당 없음";
  }
  return SUPPLIER[supplier] ?? supplier;
}

/**
 * 수수료율. 서버는 <b>베이시스 포인트</b>로 준다(`D3`) — 1bp 가 0.01% 다.
 *
 * <p><b>퍼센트로 미리 바꿔 받지 않는다.</b> 바꿔 받으면 소수 둘째 자리에서 반올림한 값이
 * 응답에 실리고, 그 값으로 다시 곱하면 정산서의 금액과 안 맞는 수가 나온다.
 */
export function commissionRateText(basisPoints: number): string {
  return `${(basisPoints / 100).toLocaleString("ko-KR")}%`;
}
