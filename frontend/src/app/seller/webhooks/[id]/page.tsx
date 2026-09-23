import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";

import { Pager, pageNumberOf } from "@/components/pager";
import { Td, Th } from "@/components/table-cells";
import { ApiError } from "@/lib/api";
import { apiSession } from "@/lib/api-session";
import { dateTimeText } from "@/lib/format";
import { deliveryStatusText, webhookEventText } from "@/lib/webhook-text";

import type { WebhookEndpoint } from "../page";
import { ResendButton } from "../webhook-actions";

export const metadata: Metadata = { title: "웹훅 발송 기록 · ProjectShop" };

const PAGE_SIZE = 20;

/** 서버의 `WebhookDeliveryQuery.Delivery` 와 짝이다 */
type Delivery = {
  webhookDeliveryId: number;
  eventType: string;
  status: string;
  attemptCount: number;
  nextAttemptAt: string | null;
  lastStatusCode: number | null;
  lastError: string | null;
  createdAt: string;
  deliveredAt: string | null;
  allowedActions: string[];
};

type DeliveryPage = { items: Delivery[]; page: number; size: number; total: number };

const TABS: { status?: string; label: string }[] = [
  { label: "전체" },
  { status: "PENDING", label: "기다림" },
  { status: "SENT", label: "보냄" },
  { status: "FAILED", label: "실패" },
  { status: "EXHAUSTED", label: "멈춤" },
];

/**
 * 엔드포인트 하나의 발송 기록(`Q175`, `31`).
 *
 * <p><b>다시 보내기는 서버가 권할 때만 선다</b>({@code allowedActions} 의 {@code RESEND}) — 화면이 상태를 보고
 * 고르면 규칙이 두 벌이 된다(`Q79`).
 */
export default async function WebhookDeliveriesPage({
  params,
  searchParams,
}: {
  params: Promise<{ id: string }>;
  searchParams: Promise<{ status?: string; page?: string }>;
}) {
  const { id } = await params;
  const requested = await searchParams;
  const status = TABS.some((tab) => tab.status !== undefined && tab.status === requested.status)
    ? requested.status
    : undefined;
  const page = pageNumberOf(requested.page);

  const endpoint = await findEndpoint(id);
  const query = new URLSearchParams({ page: String(page), size: String(PAGE_SIZE) });
  if (status) {
    query.set("status", status);
  }
  const result = await apiSession<DeliveryPage>(`/api/seller/webhooks/${endpoint.webhookEndpointId}/deliveries?${query}`);
  const lastPage = Math.max(0, Math.ceil(result.total / result.size) - 1);
  const basePath = `/seller/webhooks/${endpoint.webhookEndpointId}`;

  return (
    <div className="grid gap-8">
      <div className="grid gap-2">
        <Link href="/seller/webhooks" className="text-sm underline">
          웹훅 목록
        </Link>
        <h1 className="text-2xl font-semibold tracking-tight">발송 기록</h1>
        <p className="break-all font-mono text-sm">{endpoint.url}</p>
        <p className="text-sm text-text-muted">
          실패하면 30초부터 두 배씩 늘려 가며 여덟 번까지 다시 보냅니다.<br />
          멈춘 것과 실패한 것은 받는 쪽을 고친 뒤 다시 보낼 수 있습니다.
        </p>
      </div>

      <nav aria-label="발송 상태" className="flex flex-wrap gap-2 text-sm">
        {TABS.map((tab) => (
          <Link
            key={tab.label}
            href={tab.status ? `${basePath}?status=${tab.status}` : basePath}
            aria-current={tab.status === status ? "page" : undefined}
            className={`rounded-ui border px-3 py-1.5 ${tab.status === status ? "border-accent font-semibold" : "border-border"}`}
          >
            {tab.label}
          </Link>
        ))}
      </nav>

      {result.items.length === 0 ? (
        <p className="rounded-ui border border-border bg-surface-raised p-5 text-sm text-text-muted">
          이 상태의 발송이 없습니다.
        </p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-left text-sm">
            <caption className="sr-only">웹훅 발송 기록</caption>
            <thead className="text-text-muted">
              <tr>
                <Th>사건</Th>
                <Th>상태</Th>
                <Th align="right">시도</Th>
                <Th>마지막 응답</Th>
                <Th>생긴 때</Th>
                <Th>보낸 때 · 다음 시도</Th>
                <Th>
                  <span className="sr-only">동작</span>
                </Th>
              </tr>
            </thead>
            <tbody>
              {result.items.map((delivery) => (
                <tr key={delivery.webhookDeliveryId} className="border-t border-border">
                  <Td>{webhookEventText(delivery.eventType)}</Td>
                  <Td>{deliveryStatusText(delivery.status)}</Td>
                  <Td align="right">{delivery.attemptCount}</Td>
                  <Td muted>{lastResponseText(delivery)}</Td>
                  <Td muted>{dateTimeText(delivery.createdAt)}</Td>
                  <Td muted>{timingText(delivery)}</Td>
                  <Td>
                    {delivery.allowedActions.includes("RESEND") ? (
                      <ResendButton webhookDeliveryId={delivery.webhookDeliveryId} />
                    ) : null}
                  </Td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <Pager
        page={page}
        lastPage={lastPage}
        total={result.total}
        basePath={basePath}
        label="발송 기록"
        unit="건"
        params={{ status }}
      />
    </div>
  );
}

/** 못 보는 것과 없는 것의 답이 같다(`D5`) — 서버가 남의 엔드포인트를 404 로 준다 */
async function findEndpoint(id: string): Promise<WebhookEndpoint> {
  try {
    return await apiSession<WebhookEndpoint>(`/api/seller/webhooks/${encodeURIComponent(id)}`);
  } catch (error) {
    if (error instanceof ApiError && (error.status === 404 || error.status === 400)) {
      notFound();
    }
    throw error;
  }
}

function lastResponseText(delivery: Delivery): string {
  const parts = [delivery.lastStatusCode === null ? null : `HTTP ${delivery.lastStatusCode}`, delivery.lastError];
  const text = parts.filter((part) => part !== null && part !== "").join(" · ");
  return text === "" ? "—" : text;
}

function timingText(delivery: Delivery): string {
  if (delivery.deliveredAt !== null) {
    return dateTimeText(delivery.deliveredAt);
  }
  if (delivery.nextAttemptAt !== null) {
    return `다음 ${dateTimeText(delivery.nextAttemptAt)}`;
  }
  return "—";
}
