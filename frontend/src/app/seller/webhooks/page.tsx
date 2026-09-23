import type { Metadata } from "next";
import Link from "next/link";

import { apiSession } from "@/lib/api-session";
import { dateTimeText } from "@/lib/format";
import { webhookEventText } from "@/lib/webhook-text";

import { DeleteEndpointButton, WebhookForm } from "./webhook-actions";

export const metadata: Metadata = { title: "웹훅 · ProjectShop" };

/** 서버의 `WebhookEndpointService.Endpoint` 와 짝이다. <b>시크릿은 안 실린다</b> — 등록 응답에만 있다 */
export type WebhookEndpoint = {
  webhookEndpointId: number;
  sellerId: number;
  url: string;
  eventTypes: string[];
  createdAt: string;
};

type Memberships = { sellerIds: number[] };

/**
 * 셀러 웹훅(`Q175`).
 *
 * <p><b>셀러 번호를 화면이 안 고른다</b> — 멤버 화면(`16a`)과 같이 서버가 내려준 첫째를 쓴다.
 *
 * <p><b>다섯 개 상한을 화면이 안 센다.</b> 폼은 늘 그리고 넘치면 서버가 422 로 답한다 — 화면이 세면
 * 규칙이 두 벌이 되고, 상한이 바뀔 때 한쪽만 따라간다.
 */
export default async function SellerWebhooksPage() {
  const memberships = await apiSession<Memberships>("/api/seller/memberships");
  const sellerId = memberships.sellerIds[0];

  if (sellerId === undefined) {
    return (
      <>
        <h1 className="text-2xl font-semibold tracking-tight">웹훅</h1>
        <p className="rounded-ui border border-border bg-surface-raised px-4 py-6 text-sm text-text-muted">
          속한 셀러가 없습니다.
        </p>
      </>
    );
  }

  const { items } = await apiSession<{ items: WebhookEndpoint[] }>(`/api/seller/webhooks?sellerId=${sellerId}`);

  return (
    <div className="grid gap-8">
      <div className="grid gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">웹훅</h1>
        <p className="text-sm text-text-muted">
          주문·환불·반품·정산 지급의 상태가 바뀌면 적어 둔 주소로 알려 드립니다.<br />
          요청마다 서명이 붙으니 받는 서버에서 시크릿으로 확인해 주세요.
        </p>
      </div>

      {items.length === 0 ? (
        <p className="rounded-ui border border-border bg-surface-raised p-5 text-sm text-text-muted">
          등록한 주소가 없습니다.
        </p>
      ) : (
        <ul className="grid gap-4">
          {items.map((endpoint) => (
            <li
              key={endpoint.webhookEndpointId}
              className="grid gap-2 rounded-ui border border-border bg-surface-raised p-5 text-sm"
            >
              <span className="break-all font-mono">{endpoint.url}</span>
              <span className="text-text-muted">{endpoint.eventTypes.map(webhookEventText).join(" · ")}</span>
              <span className="text-text-muted">등록 {dateTimeText(endpoint.createdAt)}</span>
              <span className="flex flex-wrap items-center gap-3">
                <Link href={`/seller/webhooks/${endpoint.webhookEndpointId}`} className="underline">
                  발송 기록
                </Link>
                <DeleteEndpointButton webhookEndpointId={endpoint.webhookEndpointId} />
              </span>
            </li>
          ))}
        </ul>
      )}

      <section aria-labelledby="webhook-new" className="grid gap-3">
        <h2 id="webhook-new" className="text-lg font-semibold">
          주소 등록
        </h2>
        <WebhookForm sellerId={sellerId} />
      </section>
    </div>
  );
}
