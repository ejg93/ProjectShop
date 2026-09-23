/**
 * 후기를 신고하고 내리는 사유의 화면 문구(`Q171`).
 *
 * <p><b>서버의 `ReviewReason` 대문자 값과 짝이다</b>(`Q167`). 신고할 때 고르는 목록과 내려간 사유를
 * 알리는 문구가 같은 표를 써야 한다 — 따로 두면 「광고로 신고했는데 다른 말로 내려갔다」가 된다.
 *
 * <p>넷 다 공개한 운영정책(`D2` `R27`)의 「삭제 기준」 넷이다. 문구도 그 문서와 같은 말을 쓴다.
 */
export type ReviewReason = "ADVERTISEMENT" | "ABUSE" | "UNRELATED" | "PRIVACY";

export const REVIEW_REASONS: readonly ReviewReason[] = [
  "ADVERTISEMENT",
  "ABUSE",
  "UNRELATED",
  "PRIVACY",
];

export const REVIEW_REASON_TEXT: Record<ReviewReason, string> = {
  ADVERTISEMENT: "광고 또는 홍보 목적",
  ABUSE: "욕설·비방 등 타인을 해치는 표현",
  UNRELATED: "구매한 상품과 관련 없는 내용",
  PRIVACY: "다른 사람의 개인정보가 포함된 내용",
};
