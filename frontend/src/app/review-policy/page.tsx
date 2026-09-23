import type { Metadata } from "next";

import { PolicyDocument } from "@/components/policy-document";
import { apiPublic } from "@/lib/api";

export const metadata: Metadata = { title: "후기 운영정책 · ProjectShop" };

type Policy = {
  code: string;
  title: string;
  version: number;
  body: string;
  effectiveAt: string;
};

/**
 * 후기 운영정책(전자상거래법 제21조의4, `D2` `R27`).
 *
 * <p><b>문서만 있고 그리는 자리가 없었다</b>(`48` 이 `V85` 로 넣었다). 조문이 요구하는 것은
 * 기준을 정하는 것이 아니라 <b>알리는 것</b>이라, `/api/policies/review_policy` 로만 꺼낼 수
 * 있는 상태는 공개가 아니다 — 소비자가 API 를 부르지 않는다.
 *
 * <p><b>후기를 읽는 자리에서 여기로 온다</b>(상품 상세의 후기 절). 발에만 두면 후기와
 * 그 규칙이 다른 화면에 흩어지고, 읽는 사람은 규칙이 있다는 것을 모른다.
 */
export default async function ReviewPolicyPage() {
  const policy = await apiPublic<Policy>("/api/policies/review_policy");

  return (
    <PolicyDocument
      title={policy.title}
      body={policy.body}
      version={policy.version}
      effectiveAt={policy.effectiveAt}
    />
  );
}
