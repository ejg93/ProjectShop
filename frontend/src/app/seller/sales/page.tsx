import type { Metadata } from "next";

import { apiSession } from "@/lib/api-session";

import { RANGES, type Range, type SalesReport, SalesView, salesRange } from "./sales-view";

export const metadata: Metadata = { title: "매출 · ProjectShop" };

/**
 * 매출 통계(`41a`).
 *
 * <p><b>셀러와 관리자·감사자가 같은 화면을 쓴다</b> — 정산서(`20-1`)와 같은 판단이다. 보는 것이 같고 범위만 달라서
 * 읽기 권한 하나가 이 자리를 연다({@code sales_stats:read}, `V103`).
 *
 * <p><b>오늘은 한국 날짜로 센다</b>(`time-rules.md`). 서버가 한국 자정으로 자르므로 화면도 같은 달력을 쓴다 —
 * 서버 시계의 날짜를 쓰면 오전 9시 전에 하루가 밀린다.
 */
export default async function SalesPage({
  searchParams,
}: {
  searchParams: Promise<{ days?: string }>;
}) {
  const requested = Number((await searchParams).days);
  const range: Range = RANGES.find((option) => option === requested) ?? 30;
  const today = new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Seoul" }).format(new Date());
  const { from, to } = salesRange(range, today);

  const report = await apiSession<SalesReport>(`/api/sales-stats?${new URLSearchParams({ from, to })}`);

  return (
    <>
      <div className="grid gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">매출</h1>
        <p className="text-sm text-text-muted">
          볼 수 있는 셀러의 매출을 날짜별로 더합니다. 환불은 환불된 날에 빠지고, 배송비는 들어가지 않습니다.
        </p>
      </div>

      <SalesView report={report} range={range} />
    </>
  );
}
