import type { Metadata } from "next";
import Link from "next/link";

import { apiSession } from "@/lib/api-session";
import { dateText } from "@/lib/format";
import { REVIEW_REASON_TEXT, type ReviewReason } from "@/lib/review-text";

import { ReportActions, RestoreForm } from "./report-actions";

export const metadata: Metadata = { title: "후기 신고 · ProjectShop" };

type Status = "PENDING" | "ACCEPTED" | "REJECTED";

/** 처리할 신고 한 줄(`Q167`). <b>신고한 사람은 안 온다</b> — 판단할 것은 글이다 */
type ReviewReport = {
  reviewReportId: number;
  reviewId: number;
  productId: number;
  productName: string;
  rating: number;
  body: string;
  reason: ReviewReason;
  status: Status;
  createdAt: string;
  resolvedAt: string | null;
  reviewBlockedReason: ReviewReason | null;
  reviewDeleted: boolean;
  /** 이 신고에 할 수 있는 것 — 접수면 `ACCEPT`·`REJECT`(`Q234`) */
  allowedActions: string[];
  /** 그 후기에 할 수 있는 것 — 받아들여 내려가 있으면 `RESTORE`. 다른 자원의 조작이라 칸이 따로다 */
  reviewActions: string[];
};

type Page = { items: ReviewReport[]; page: number; size: number; total: number };

const TABS: { status: Status; label: string }[] = [
  { status: "PENDING", label: "접수" },
  { status: "ACCEPTED", label: "받아들임" },
  { status: "REJECTED", label: "물림" },
];

/**
 * 후기 신고(`Q171`).
 *
 * <p><b>받아들이면 그 신고의 사유로 후기가 내려간다</b>(`Q167`). 사유를 따로 안 고른다 — 신고한 사유와
 * 내린 사유가 갈리면 쓴 사람이 「무엇 때문에 내려갔나」를 잘못 안다.
 *
 * <p><b>되살리기는 받아들인 쪽에만 있다.</b> 처리한 신고는 다시 못 열고(`D7`), 판단이 뒤집힌 것은
 * 후기를 되살리는 것으로 남긴다 — 이유를 적어야 누른다. 그 이유가 감사에 남는다.
 */
export default async function ReviewReportsPage({
  searchParams,
}: {
  searchParams: Promise<{ status?: string }>;
}) {
  const requested = (await searchParams).status;
  const status: Status = TABS.some((tab) => tab.status === requested)
    ? (requested as Status)
    : "PENDING";

  const page = await apiSession<Page>(`/api/admin/review-reports?status=${status}&size=50`);

  return (
    <div className="grid gap-8">
      <div className="grid gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">후기 신고</h1>
        <p className="text-sm text-text-muted">
          신고를 받아들이면 그 사유로 후기가 게시 중단되고, 쓴 분은 내 후기에서 사유를 봅니다.
        </p>
      </div>

      <nav aria-label="신고 상태" className="flex gap-2 text-sm">
        {TABS.map((tab) => (
          <Link
            key={tab.status}
            href={`/admin/review-reports?status=${tab.status}`}
            aria-current={tab.status === status ? "page" : undefined}
            className={`rounded-ui border px-3 py-1.5 ${
              tab.status === status ? "border-accent font-semibold" : "border-border"
            }`}
          >
            {tab.label}
          </Link>
        ))}
      </nav>

      {page.items.length === 0 ? (
        <p className="rounded-ui border border-border bg-surface-raised p-5 text-sm text-text-muted">
          이 상태의 신고가 없습니다.
        </p>
      ) : (
        <ul className="grid gap-4">
          {page.items.map((item) => (
            <li
              key={item.reviewReportId}
              className="grid gap-3 rounded-ui border border-border bg-surface-raised p-5 text-sm"
            >
              <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
                <span className="font-semibold">{REVIEW_REASON_TEXT[item.reason]}</span>
                <span className="text-text-muted">신고 {dateText(item.createdAt)}</span>
                {item.resolvedAt === null ? null : (
                  <span className="text-text-muted">처리 {dateText(item.resolvedAt)}</span>
                )}
              </div>

              <div className="grid gap-1 rounded-ui bg-surface p-4">
                <Link href={`/products/${item.productId}`} className="text-text-muted underline">
                  {item.productName}
                </Link>
                <span aria-label={`별점 ${item.rating}점`}>{"★".repeat(item.rating)}</span>
                <p className="whitespace-pre-wrap">{item.body}</p>
              </div>

              {item.reviewDeleted ? (
                <p className="text-text-muted">쓴 분이 지운 후기입니다.</p>
              ) : item.reviewBlockedReason === null ? null : (
                <p className="text-text-muted">
                  게시 중단 중 · 사유: {REVIEW_REASON_TEXT[item.reviewBlockedReason]}
                </p>
              )}

              {item.allowedActions.length > 0 ? (
                <ReportActions reportId={item.reviewReportId} allowedActions={item.allowedActions} />
              ) : null}

              {item.reviewActions.includes("RESTORE") ? (
                <RestoreForm reviewId={item.reviewId} deleted={item.reviewDeleted} />
              ) : null}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
