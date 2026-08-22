/**
 * 상품의 검수 상태를 화면 문구로 바꾼다(`13f`).
 *
 * <p><b>`order-text` 와 갈라 뒀다.</b> 상품과 주문은 다른 자원이고,
 * 한 파일에 모으면 주문 화면이 상품 문구를 같이 들고 다닌다.
 *
 * <p><b>모르는 값은 그대로 보여준다</b>(`D5` 「모르는 열거값은 무시한다」).
 * 서버가 상태를 하나 늘려도 화면이 안 깨진다.
 */

/** 상품 상태(`V13`). 검수를 지나야 팔린다 */
const PRODUCT_STATUS: Record<string, string> = {
  draft: "작성 중",
  pending_review: "검수 대기",
  on_sale: "판매 중",
  sold_out: "품절",
  suspended: "판매 중지",
  blocked: "판매 차단",
};

/**
 * 셀러가 무엇을 해야 하는지.
 *
 * <p><b>상태 이름만으로는 안 읽힌다.</b> 「판매 차단」이 우리가 막은 것인지
 * 셀러가 내린 것인지가 이름에 없어서, 무엇을 할 차례인지를 따로 적는다.
 */
const PRODUCT_STATUS_HINT: Record<string, string> = {
  draft: "검수를 신청하면 판매가 시작됩니다",
  pending_review: "검수 결과를 기다리는 중입니다",
  blocked: "표시·광고 검수에서 막혔습니다. 고객센터로 문의해 주세요",
  suspended: "판매를 멈춘 상태입니다",
};

export function productStatusText(status: string): string {
  return PRODUCT_STATUS[status] ?? status;
}

export function productStatusHint(status: string): string | null {
  return PRODUCT_STATUS_HINT[status] ?? null;
}
