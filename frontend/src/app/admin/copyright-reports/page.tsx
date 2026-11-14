import type { Metadata } from "next";
import Link from "next/link";

import { apiSession } from "@/lib/api-session";
import { dateText } from "@/lib/format";
import { pageQuery, type Page } from "@/lib/paging";

import { CopyrightDecisionButtons } from "./decision-buttons";
import { ReinstatementButtons, type Reinstatement } from "./reinstatement-buttons";

export const metadata: Metadata = { title: "저작권 신고 · ProjectShop" };

type Status = "PENDING" | "DECIDED";

/** 판정할 신고 한 줄(`Q183`). 사진이 이미 없으면 썸네일이 `null` 이다 */
type CopyrightReport = {
  copyrightReportId: number;
  productId: number;
  productName: string;
  /** 무엇을 신고했나(`Q196`) — 상품 사진이나 후기 사진 */
  target: "PRODUCT_IMAGE" | "REVIEW_IMAGE";
  productImageId: number | null;
  thumbnailUrl: string | null;
  reporterName: string;
  reporterEmail: string;
  claimedWork: string;
  reportedAt: string;
  decision: "TAKEN_DOWN" | "REJECTED" | null;
  decidedAt: string | null;
  /** 재개 요구와 그 처리(`Q465`). 요구가 없으면 `null` */
  reinstatement: Reinstatement | null;
};


const DECISION_TEXT: Record<"TAKEN_DOWN" | "REJECTED", string> = {
  TAKEN_DOWN: "게시 중단(사진 숨김)",
  REJECTED: "기각",
};

/**
 * 저작권 신고 판정(`Q183`, 저작권법 제103조, `D2` `R42`).
 *
 * <p><b>`Q94` 가 판정 입구를 세우고 목록을 안 세워서</b> 신고 번호를 볼 자리가 없었다 — 절차가 있어도 돌릴 수 없었다.
 * <b>게시 중단은 사진을 비공개 버킷으로 숨긴다</b>(`Q464`) — 공개 URL 은 사라지고, 재개 요구(저작권법 제103조③)가 오면
 * 예정일에 되돌린다. 재개 절차의 단계를 줄마다 보인다(`Q467`).
 */
export default async function CopyrightReportsPage({
  searchParams,
}: {
  searchParams: Promise<{ status?: string }>;
}) {
  const status: Status = (await searchParams).status === "DECIDED" ? "DECIDED" : "PENDING";
  const page = await apiSession<Page<CopyrightReport>>(`/api/copyright-reports?status=${status}&${pageQuery(0, 50)}`);

  return (
    <div className="grid gap-8">
      <div className="grid gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">저작권 신고</h1>
        <p className="text-sm text-text-muted">
          권리자가 알린 사진입니다. 게시를 중단하면 사진이 비공개로 옮겨지고 47일 뒤 재개 예정이 없으면 지워집니다.
        </p>
      </div>

      <nav aria-label="신고 상태" className="flex gap-2 text-sm">
        {(["PENDING", "DECIDED"] as const).map((tab) => (
          <Link
            key={tab}
            href={`/admin/copyright-reports?status=${tab}`}
            aria-current={tab === status ? "page" : undefined}
            className={`rounded-ui border px-3 py-1.5 ${tab === status ? "border-accent font-semibold" : "border-border"}`}
          >
            {tab === "PENDING" ? "판정 전" : "판정 후"}
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
              key={item.copyrightReportId}
              className="grid gap-3 rounded-ui border border-border bg-surface-raised p-5 text-sm md:grid-cols-[6rem_1fr]"
            >
              {item.thumbnailUrl ? (
                // eslint-disable-next-line @next/next/no-img-element
                <img
                  src={item.thumbnailUrl}
                  alt={`${item.productName} 의 신고된 ${item.target === "REVIEW_IMAGE" ? "후기 사진" : "상품 사진"}`}
                  className="h-24 w-24 rounded-ui border border-border object-cover"
                />
              ) : (
                <p className="text-xs text-text-muted">사진이 이미 없습니다</p>
              )}
              <div className="grid gap-2">
                <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
                  <Link href={`/products/${item.productId}`} className="font-semibold underline">
                    {item.productName}
                  </Link>
                  <span>{item.target === "REVIEW_IMAGE" ? "후기 사진" : "상품 사진"}</span>
                  <span className="text-text-muted">접수 {dateText(item.reportedAt)}</span>
                  {item.decision === null ? null : (
                    <span className="font-semibold">
                      {DECISION_TEXT[item.decision]}
                      {item.decidedAt === null ? null : ` · ${dateText(item.decidedAt)}`}
                    </span>
                  )}
                </div>
                <p className="whitespace-pre-wrap">{item.claimedWork}</p>
                <p className="text-text-muted">
                  신고인 {item.reporterName} · {item.reporterEmail}
                </p>
                {item.decision === null ? <CopyrightDecisionButtons reportId={item.copyrightReportId} /> : null}
                {item.reinstatement === null ? null : (
                  <p className="font-semibold">{reinstatementText(item.reinstatement)}</p>
                )}
                {item.decision === "TAKEN_DOWN" ? (
                  <ReinstatementButtons reportId={item.copyrightReportId} reinstatement={item.reinstatement} />
                ) : null}
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/** 한국 날짜 `yyyy-mm-dd` */
function kstDate(iso: string): string {
  return new Date(iso).toLocaleDateString("sv-SE", { timeZone: "Asia/Seoul" });
}

/** 재개 절차가 어디까지 왔나(`Q465`) */
function reinstatementText(reinstatement: Reinstatement): string {
  if (reinstatement.suitNotifiedAt !== null) {
    return `소 제기 통보 ${kstDate(reinstatement.suitNotifiedAt)} — 재개하지 않습니다`;
  }
  if (reinstatement.reinstatedAt !== null) {
    return `재개됨 ${kstDate(reinstatement.reinstatedAt)}`;
  }
  if (reinstatement.decision === "SCHEDULED") {
    return `재개 예정 ${reinstatement.scheduledFor}`;
  }
  if (reinstatement.decision === "REFUSED") {
    return "재개 거절";
  }
  return `재개 요구 접수 ${kstDate(reinstatement.requestedAt)}`;
}
