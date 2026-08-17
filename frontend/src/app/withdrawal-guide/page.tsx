import type { Metadata } from "next";

import { PolicyDocument } from "@/components/policy-document";
import { apiPublic } from "@/lib/api";

export const metadata: Metadata = { title: "청약철회 안내 · ProjectShop" };

type Policy = {
  code: string;
  title: string;
  version: number;
  body: string;
  effectiveAt: string;
};

/**
 * 청약철회 안내(전자상거래법 제13조제2항).
 *
 * <p><b>여기 적힌 것은 우리 정책이 아니라 법 조문이다.</b> 기간·제한 사유·비용 부담·환급 기한이
 * 전부 법에 있어서 반품 축(`43`·`44`)이 서기 전에도 쓸 수 있었다.
 *
 * <p>상품별 제한 사유는 이 화면이 아니라 상품 상세가 그린다(`14b`) —
 * <b>표시가 제한의 성립 요건이라</b>(제17조제2항 단서) 사는 자리에 있어야 한다.
 */
export default async function WithdrawalGuidePage() {
  const policy = await apiPublic<Policy>("/api/policies/withdrawal_guide");

  return (
    <PolicyDocument
      title={policy.title}
      body={policy.body}
      version={policy.version}
      effectiveAt={policy.effectiveAt}
    />
  );
}
