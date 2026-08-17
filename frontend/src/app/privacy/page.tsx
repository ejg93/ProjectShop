import type { Metadata } from "next";

import { PolicyDocument } from "@/components/policy-document";
import { apiPublic } from "@/lib/api";

export const metadata: Metadata = { title: "개인정보처리방침 · ProjectShop" };

/** 알리기만 하는 정책 문서 한 판(`13a-1`) */
type Policy = {
  code: string;
  title: string;
  version: number;
  body: string;
  effectiveAt: string;
};

/**
 * 개인정보처리방침(`D2` R11, 개인정보법 제30조제2항).
 *
 * <p><b>동의 항목이 아니라 알리는 문서다.</b> 그래서 `consent_item` 이 아니라
 * `policy_document` 에서 온다 — 가르는 이유는 `13a-1` 이 적어 뒀다.
 *
 * <p>판과 시행일을 같이 그린다. 개정할 때 <b>시행 7일 전부터 알려야 해서</b>
 * (시행령 제31조제3항) 지금 걸린 것이 어느 판인지가 보여야 한다.
 */
export default async function PrivacyPage() {
  const policy = await apiPublic<Policy>("/api/policies/privacy_policy");

  return (
    <PolicyDocument
      title={policy.title}
      body={policy.body}
      version={policy.version}
      effectiveAt={policy.effectiveAt}
    />
  );
}
