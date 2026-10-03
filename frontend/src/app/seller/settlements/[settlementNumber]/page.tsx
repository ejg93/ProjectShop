import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";

import { Pager, pageNumberOf } from "@/components/pager";
import { PayoutActions } from "@/components/payout-actions";
import { ApiError } from "@/lib/api";
import { apiSession } from "@/lib/api-session";
import { dateText, dateTimeText, priceText } from "@/lib/format";
import {
  commissionRateText,
  payoutStatusText,
  settlementItemKindText,
  settlementSupplierText,
} from "@/lib/settlement-text";

export const metadata: Metadata = { title: "정산서 상세 · ProjectShop" };

type SettlementSummary = {
  settlementNumber: string;
  sellerCode: string;
  periodStart: string;
  periodEnd: string;
  payoutDate: string;
  payoutAmount: number;
  carriedOver: number;
  payoutStatus: string;
  createdAt: string;
};

/**
 * 정산서 한 줄의 근거.
 *
 * <p>배송비 줄은 상품이 없고, 이월 줄은 주문도 상품도 공급자도 없다 —
 * 그 칸이 비는 것이 종류에서 이미 정해져 있다(`V52`).
 */
type SettlementLine = {
  kind: string;
  supplier: string | null;
  amount: number;
  commissionBp: number | null;
  commissionBaseAmount: number | null;
  sellerOrderNumber: string | null;
  productName: string | null;
};

/** 종류 하나의 줄 수와 금액 합. {@code kind} 는 줄의 {@code kind} 와 같은 값이다 */
type SettlementKindTotal = {
  kind: string;
  lineCount: number;
  amount: number;
};

type SettlementDetail = {
  summary: SettlementSummary;
  /** 종류별 합계. 줄은 여기 없다 — 줄 목록 입구가 쪽으로 준다(`Q244`) */
  totals: SettlementKindTotal[];
  /** 서버가 권한·상태·요청자를 다 보고 준 이름 목록(`Q81`) */
  allowedActions: string[];
};

type SettlementLinePage = {
  items: SettlementLine[];
  page: number;
  size: number;
  total: number;
};

/** 줄 한 쪽의 크기. 한 장의 줄이 수만일 수 있다(`Q244`) */
const LINE_PAGE_SIZE = 50;

/**
 * 정산서 하나(`20-1`).
 *
 * <p><b>여기서 답하는 물음은 「이 금액이 어디서 나왔나」다.</b> 종류별 합계로 지급액을 맞춰 보고,
 * 줄로 하나하나를 되짚는다 — 합계만 보고 이의를 제기할 수는 없다. <b>줄은 쪽으로 넘긴다</b>(`Q244`) —
 * 한 장의 줄이 3.5만일 수 있어서 서버가 상세에 줄을 안 싣고 줄 목록 입구로 뗐다.
 *
 * <p><b>지급 버튼을 권한 목록으로 그린다</b>(`8a` 가 그 자리를 열었다). 판정이 내려준 목록에
 * 그 동작이 없으면 <b>버튼을 안 그린다</b> — 그려 놓고 감추는 방식은 안 쓴다(`59` 와 같은 판단).
 * 그래서 셀러가 이 화면을 열면 금액과 근거만 있고, 지급은 관리자만 올리고 승인한다(`V57`).
 *
 * <p><b>권한을 따로 안 묻는다.</b> 서버가 권한·상태·요청자를 다 보고 {@code allowed_actions} 로 준다(`Q81`, `D20`).
 */
export default async function SettlementDetailPage({
  params,
  searchParams,
}: {
  params: Promise<{ settlementNumber: string }>;
  searchParams: Promise<{ page?: string }>;
}) {
  const { settlementNumber } = await params;
  const page = pageNumberOf((await searchParams).page);
  const path = `/api/settlements/${encodeURIComponent(settlementNumber)}`;

  // 둘이 나란히 나간다. 못 보는 정산서는 둘 다 같은 404 다.
  const [detail, lines] = await Promise.all([
    orNotFound(apiSession<SettlementDetail>(path)),
    orNotFound(apiSession<SettlementLinePage>(`${path}/lines?page=${page}&size=${LINE_PAGE_SIZE}`)),
  ]);

  const { summary, totals, allowedActions } = detail;
  const lastPage = Math.max(0, Math.ceil(lines.total / lines.size) - 1);

  return (
    <>
      <div className="grid gap-2">
        <Link
          href="/seller/settlements"
          className="
            justify-self-start text-sm text-text-muted underline underline-offset-4
            transition-colors duration-200 hover:text-text
            focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
          "
        >
          정산서로
        </Link>
        <h1 className="font-mono text-2xl font-semibold tracking-tight">
          {summary.settlementNumber}
        </h1>
        <p className="text-sm text-text-muted">
          판매자 {summary.sellerCode} · {payoutStatusText(summary.payoutStatus)}
        </p>
      </div>

      <Section title="정산 개요">
        <Facts
          rows={[
            ["정산 기간", `${dateText(summary.periodStart)} ~ ${dateText(summary.periodEnd)}`],
            ["지급 예정일", dateText(summary.payoutDate)],
            ["지급액", priceText(summary.payoutAmount)],
            ["다음 주기로 이월", summary.carriedOver === 0 ? "없음" : priceText(summary.carriedOver)],
            ["만든 시각", dateTimeText(summary.createdAt)],
          ]}
        />
      </Section>

      <Section title="종류별 합계">
        {totals.length > 0 ? <TotalTable totals={totals} /> : <p className="text-sm text-text-muted">계산에 들어간 항목이 없습니다.</p>}
      </Section>

      <Section title="계산 근거">
        {lines.items.length > 0 ? <LineTable lines={lines.items} /> : <p className="text-sm text-text-muted">이 쪽에는 항목이 없습니다.</p>}
        {lines.total > 0 ? (
          <Pager
            page={lines.page}
            lastPage={lastPage}
            total={lines.total}
            basePath={`/seller/settlements/${encodeURIComponent(summary.settlementNumber)}`}
            label="정산 항목"
            unit="줄"
          />
        ) : null}
      </Section>

      <PayoutActions settlementNumber={summary.settlementNumber} allowed={allowedActions} />
    </>
  );
}

/**
 * 종류별 줄 수와 금액. 합의 합이 지급액이라 줄을 다 넘기지 않아도 대사가 된다.
 *
 * <p><b>차감 종류를 색으로만 알리지 않는다</b>(`D20`·WCAG 1.4.1). 금액 앞의 부호가 그 사실을 말한다.
 */
function TotalTable({ totals }: { totals: SettlementKindTotal[] }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[24rem] border-collapse text-sm">
        <caption className="sr-only">종류별 합계. 종류, 줄 수, 금액 순</caption>
        <thead>
          <tr className="border-b border-border text-left text-xs text-text-muted">
            <th scope="col" className="py-2 pr-3 font-normal">
              종류
            </th>
            <th scope="col" className="py-2 pr-3 text-right font-normal">
              줄 수
            </th>
            <th scope="col" className="py-2 pr-3 text-right font-normal">
              금액
            </th>
          </tr>
        </thead>
        <tbody>
          {totals.map((total) => (
            <tr key={total.kind} className="border-b border-border">
              <td className="py-2 pr-3">{settlementItemKindText(total.kind)}</td>
              <td className="py-2 pr-3 text-right font-mono">{total.lineCount.toLocaleString("ko-KR")}</td>
              <td className="py-2 pr-3 text-right">
                <span className="font-mono">{priceText(total.amount)}</span>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/**
 * 정산에 들어간 줄.
 *
 * <p><b>수수료율과 그 기준 금액을 같이 그린다</b>(`D3`). 수수료 금액만 보이면
 * 「얼마에 몇 % 를 떼었나」를 셀러가 되짚을 수가 없고, 되짚을 수 없는 공제는 이의를 못 건다.
 *
 * <p><b>차감 줄을 색으로만 알리지 않는다</b>(`D20`·WCAG 1.4.1). 금액 앞의 부호가 그 사실을
 * 말하므로 색이 없어도 같은 정보가 간다.
 */
function LineTable({ lines }: { lines: SettlementLine[] }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[48rem] border-collapse text-sm">
        <caption className="sr-only">
          계산 근거. 종류, 공급자, 대상, 수수료율, 금액 순
        </caption>
        <thead>
          <tr className="border-b border-border text-left text-xs text-text-muted">
            <th scope="col" className="py-2 pr-3 font-normal">
              종류
            </th>
            <th scope="col" className="py-2 pr-3 font-normal">
              공급자
            </th>
            <th scope="col" className="py-2 pr-3 font-normal">
              대상
            </th>
            <th scope="col" className="py-2 pr-3 font-normal">
              수수료율
            </th>
            <th scope="col" className="py-2 pr-3 text-right font-normal">
              금액
            </th>
          </tr>
        </thead>
        <tbody>
          {lines.map((line, index) => (
            <tr key={`${line.kind}-${index}`} className="border-b border-border">
              <td className="py-2 pr-3 align-top">{settlementItemKindText(line.kind)}</td>
              <td className="py-2 pr-3 align-top text-text-muted">
                {settlementSupplierText(line.supplier)}
              </td>
              <td className="py-2 pr-3 align-top">
                <Subject line={line} />
              </td>
              <td className="py-2 pr-3 align-top">
                <Commission line={line} />
              </td>
              <td className="py-2 pr-3 text-right align-top">
                <span className="font-mono">{priceText(line.amount)}</span>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** 무엇에 붙은 줄인가. 상품이 없는 줄은 주문 번호까지만, 그것도 없으면 정산끼리의 조정이다 */
function Subject({ line }: { line: SettlementLine }) {
  if (line.sellerOrderNumber === null) {
    return <span className="text-text-muted">해당 없음</span>;
  }

  return (
    <span>
      <span className="font-mono">{line.sellerOrderNumber}</span>
      {line.productName === null ? null : (
        <span className="text-text-muted"> · {line.productName}</span>
      )}
    </span>
  );
}

/** 수수료율과 그 기준 금액. 수수료 줄이 아니면 빈 자리가 아니라 「해당 없음」이다 */
function Commission({ line }: { line: SettlementLine }) {
  if (line.commissionBp === null) {
    return <span className="text-text-muted">해당 없음</span>;
  }

  return (
    <span>
      {commissionRateText(line.commissionBp)}
      {line.commissionBaseAmount === null ? null : (
        <span className="text-text-muted"> · {priceText(line.commissionBaseAmount)} 기준</span>
      )}
    </span>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="grid gap-3">
      <h2 className="text-sm font-semibold">{title}</h2>
      {children}
    </section>
  );
}

/** 이름과 값의 짝. <b>{@code dl} 이라야</b> 화면낭독기가 둘을 이어 읽는다(`D20`) */
function Facts({ rows }: { rows: [string, string][] }) {
  return (
    <dl className="grid grid-cols-[9rem_1fr] gap-x-3 gap-y-1 text-sm">
      {rows.map(([name, value]) => (
        <div key={name} className="col-span-2 grid grid-cols-subgrid">
          <dt className="text-text-muted">{name}</dt>
          <dd className="font-mono">{value}</dd>
        </div>
      ))}
    </dl>
  );
}

/**
 * 404 를 「없는 쪽」으로 바꾼다.
 *
 * <p><b>못 보는 것과 없는 것의 답이 같다</b>(`D5` 「권한 실패」). 서버가 남의 정산서와
 * 없는 정산서를 같은 404 로 주고, 화면도 그 답을 그대로 따른다 — 가르면 번호를 훑어서
 * 셀러 수 × 개월이 샌다.
 */
async function orNotFound<T>(request: Promise<T>): Promise<T> {
  try {
    return await request;
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      notFound();
    }
    throw error;
  }
}
