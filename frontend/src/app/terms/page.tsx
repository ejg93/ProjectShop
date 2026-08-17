import type { Metadata } from "next";

import { PolicyDocument } from "@/components/policy-document";
import { apiPublic } from "@/lib/api";

export const metadata: Metadata = { title: "이용약관 · ProjectShop" };

/**
 * 동의받는 항목의 지금 효력 있는 판(`5k`).
 *
 * <p>정형 넷(`purpose`·`collectedItems`·…)은 개인정보 고지용이라 약관에는 null 이다.
 */
type Notice = {
  code: string;
  title: string;
  version: number;
  body: string | null;
  effectiveAt: string;
};

/**
 * 이용약관.
 *
 * <p><b>출처가 다른 둘과 갈린다.</b> 약관은 사람이 <b>동의하는</b> 것이라 `consent_item` 에 있고
 * (`user_consent` 가 그 판을 가리킨다), 처리방침·청약철회 안내는 알리기만 해서
 * `policy_document` 에 있다. 경로가 갈린 이유가 그것이다.
 *
 * <p><b>여기 나가는 것은 지금 효력 있는 판이다.</b> 내가 동의한 판의 사본은 다른 경로가 준다
 * (`GET /api/me/consents/{code}`) — 개정되면 둘이 달라지고, 그때 최신판을 내주면
 * 그 사이 우리가 고친 것을 들이미는 꼴이 된다(약관규제법 제3조제2항).
 *
 * <p>`14b` 상품 상세의 「이용약관 보기」가 닿는 곳이다.
 */
export default async function TermsPage() {
  const notice = await apiPublic<Notice>("/api/consent-items/terms_of_service");

  return (
    <PolicyDocument
      title={notice.title}
      // 약관은 본문이 있어야 한다. 없으면 동의받을 내용이 없는 것이라 빈 화면이 정답이 아니다.
      body={notice.body ?? "약관 본문을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요."}
      version={notice.version}
      effectiveAt={notice.effectiveAt}
    />
  );
}
