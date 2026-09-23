import Link from "next/link";

import { Td, Th } from "@/components/table-cells";
import { dateText, priceText } from "@/lib/format";

/** 하루치 합(`SalesStatsQuery.Day`) */
export type SalesDay = {
  salesDate: string;
  orderCount: number;
  soldQuantity: number;
  paidAmount: number;
  refundCount: number;
  refundedAmount: number;
  netAmount: number;
};

export type SalesTotal = Omit<SalesDay, "salesDate">;

/** {@code GET /api/sales-stats} 의 답. {@code to} 는 그날을 안 넣는다 */
export type SalesReport = { from: string; to: string; days: SalesDay[]; total: SalesTotal };

/** 고를 수 있는 기간(일). 서버 상한(366일) 안이다 */
export const RANGES = [7, 30, 90] as const;
export type Range = (typeof RANGES)[number];

/**
 * 오늘을 끝으로 하는 기간. {@code to} 는 내일이다 — 서버가 끝을 안 넣는다(`41`).
 *
 * @param today 한국 날짜 {@code YYYY-MM-DD}
 */
export function salesRange(days: Range, today: string): { from: string; to: string } {
  const [year, month, day] = today.split("-").map(Number);
  const shift = (offset: number) => new Date(Date.UTC(year, month - 1, day + offset)).toISOString().slice(0, 10);
  return { from: shift(-(days - 1)), to: shift(1) };
}

/**
 * 매출 통계(`41a`).
 *
 * <p><b>누구의 합인지는 서버가 정한다</b> — 대표는 자기 셀러, 관리자·감사자는 전부다. 화면은 범위를 안 본다
 * ({@code can} 과 같은 판단). 그래서 문구가 「볼 수 있는 셀러」다.
 *
 * <p><b>순매출을 맨 앞에 둔다.</b> 결제와 환불이 같은 축(할인 뒤·배송비 제외)이라 뺀 값이 곧 그 기간에 남은 판매다.
 * 표는 최근 날부터 — 이 화면을 여는 사람이 먼저 묻는 것이 「어제 얼마 팔았나」다.
 */
export function SalesView({ report, range }: { report: SalesReport; range: Range }) {
  const days = [...report.days].reverse();

  return (
    <>
      <nav aria-label="기간" className="flex gap-2 text-sm">
        {RANGES.map((option) => (
          <Link
            key={option}
            href={`/seller/sales?days=${option}`}
            aria-current={option === range ? "page" : undefined}
            className={`rounded-ui border px-3 py-1.5 ${option === range ? "border-accent font-semibold" : "border-border"}`}
          >
            최근 {option}일
          </Link>
        ))}
      </nav>

      <dl className="grid grid-cols-3 gap-4 rounded-ui border border-border bg-surface-raised p-5 text-sm">
        <div className="grid gap-1">
          <dt className="text-text-muted">순매출</dt>
          <dd className="text-xl font-semibold tabular-nums">{priceText(report.total.netAmount)}</dd>
        </div>
        <div className="grid gap-1">
          <dt className="text-text-muted">결제 금액</dt>
          <dd className="tabular-nums">{priceText(report.total.paidAmount)}</dd>
        </div>
        <div className="grid gap-1">
          <dt className="text-text-muted">환불 금액</dt>
          <dd className="tabular-nums">{priceText(report.total.refundedAmount)}</dd>
        </div>
      </dl>

      <div className="overflow-x-auto">
        <table className="w-full min-w-[40rem] border-collapse text-sm">
          <caption className="sr-only">
            날짜별 매출. 날짜, 결제 건수, 판매 수량, 결제 금액, 환불 건수, 환불 금액, 순매출 순. 최근 날부터
          </caption>
          <thead>
            <tr className="border-b border-border text-left text-xs text-text-muted">
              <Th>날짜</Th>
              <Th align="right">결제</Th>
              <Th align="right">수량</Th>
              <Th align="right">결제 금액</Th>
              <Th align="right">환불</Th>
              <Th align="right">환불 금액</Th>
              <Th align="right">순매출</Th>
            </tr>
          </thead>
          <tbody>
            {days.map((day) => (
              <tr key={day.salesDate} className="border-b border-border">
                <Td>{dateText(day.salesDate)}</Td>
                <Td align="right">{day.orderCount}건</Td>
                <Td align="right">{day.soldQuantity}개</Td>
                <Td align="right">{priceText(day.paidAmount)}</Td>
                <Td align="right" muted={day.refundCount === 0}>{day.refundCount}건</Td>
                <Td align="right" muted={day.refundedAmount === 0}>{priceText(day.refundedAmount)}</Td>
                <Td align="right">{priceText(day.netAmount)}</Td>
              </tr>
            ))}
          </tbody>
          <tfoot>
            <tr className="font-semibold">
              <th scope="row" className="py-2 pr-3 text-left">합계</th>
              <Td align="right">{report.total.orderCount}건</Td>
              <Td align="right">{report.total.soldQuantity}개</Td>
              <Td align="right">{priceText(report.total.paidAmount)}</Td>
              <Td align="right">{report.total.refundCount}건</Td>
              <Td align="right">{priceText(report.total.refundedAmount)}</Td>
              <Td align="right">{priceText(report.total.netAmount)}</Td>
            </tr>
          </tfoot>
        </table>
      </div>
    </>
  );
}
