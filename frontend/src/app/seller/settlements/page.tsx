import type { Metadata } from "next";
import Link from "next/link";

import { Pager, pageNumberOf } from "@/components/pager";
import { Td, Th } from "@/components/table-cells";
import { apiSession } from "@/lib/api-session";
import { dateText, priceText } from "@/lib/format";
import { payoutStatusText } from "@/lib/settlement-text";

export const metadata: Metadata = { title: "정산서 · ProjectShop" };

/** 한 쪽에 몇 개. 서버 기본값과 같게 둔다(`D5` 「목록」) */
const PAGE_SIZE = 20;

type SettlementSummary = {
  settlementNumber: string;
  sellerCode: string;
  periodStart: string;
  periodEnd: string;
  payoutDate: string;
  payoutAmount: number;
  /** 다음 주기로 넘기는 음수 잔액. 안 넘기면 0 이다 */
  carriedOver: number;
  payoutStatus: string;
  createdAt: string;
};

type SettlementPage = {
  items: SettlementSummary[];
  page: number;
  size: number;
  total: number;
};

/**
 * 정산서 목록(`20-1`).
 *
 * <p><b>이 화면이 답하는 물음은 「얼마가 언제 들어오나」다.</b> 그래서 지급 예정일과
 * 지급 상태가 줄마다 있다 — 상세를 열어야 보이면 목록이 그 물음에 답을 안 하는 것이다.
 *
 * <p><b>관객이 둘인데 화면은 하나다.</b> 셀러는 자기 정산서를, 관리자·감사자는 전체를 본다.
 * 경로를 관객별로 안 가르는 것은 {@code SettlementController} 와 같은 판단이다 —
 * 보는 것이 같고 <b>범위만 달라진다.</b> 그 범위를 정하는 것은 서버고 화면은 받은 것을 그린다.
 *
 * <p><b>셀러 틀 안에 둔다</b>(`13c-1`). 정산서를 처리하는 것은 파는 쪽의 일이고,
 * 여러 건을 한 화면에서 훑는 자리라 밀도 7 의 표가 맞는다.
 *
 * <p><b>지급 버튼은 여기 없다.</b> 목록에서 누르면 무엇을 승인하는지 안 보고 누르게 된다 —
 * 금액과 근거 줄이 있는 상세에서만 연다.
 */
export default async function SettlementsPage({
  searchParams,
}: {
  searchParams: Promise<{ page?: string }>;
}) {
  const requested = await searchParams;
  const page = pageNumberOf(requested.page);

  const query = new URLSearchParams({ page: String(page), size: String(PAGE_SIZE) });
  const result = await apiSession<SettlementPage>(`/api/settlements?${query}`);
  const lastPage = Math.max(0, Math.ceil(result.total / result.size) - 1);

  return (
    <>
      <div className="grid gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">정산서</h1>
        <p className="text-sm text-text-muted">
          정산 주기가 끝나면 그 기간의 판매와 수수료를 모아 정산서를 만듭니다.
        </p>
      </div>

      {result.items.length > 0 ? (
        <>
          <SettlementTable items={result.items} />
          <Pager
            page={result.page}
            lastPage={lastPage}
            total={result.total}
            basePath="/seller/settlements"
            label="정산서 목록"
            unit="건"
          />
        </>
      ) : (
        <Empty hasAny={result.total > 0} />
      )}
    </>
  );
}

/**
 * 정산서를 줄로 그린다.
 *
 * <p><b>이월을 따로 세운다.</b> 지급액 안에 섞으면 「이번에 받을 돈」과 「다음으로 넘어간 돈」이
 * 한 숫자가 되고, 그러면 정산서를 보고도 통장에 들어올 금액을 모른다.
 *
 * <p>좁은 화면에서는 가로로 민다. <b>칸을 접지 않는다</b> — 접으면 금액과 상태가
 * 다른 줄로 가서 「이게 들어왔나」를 한눈에 못 본다.
 */
function SettlementTable({ items }: { items: SettlementSummary[] }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[48rem] border-collapse text-sm">
        <caption className="sr-only">
          정산서 목록. 정산번호, 판매자, 정산 기간, 지급 예정일, 지급액, 이월, 상태 순
        </caption>
        <thead>
          <tr className="border-b border-border text-left text-xs text-text-muted">
            <Th>정산번호</Th>
            <Th>판매자</Th>
            <Th>정산 기간</Th>
            <Th>지급 예정일</Th>
            <Th align="right">지급액</Th>
            <Th align="right">이월</Th>
            <Th>상태</Th>
          </tr>
        </thead>
        <tbody>
          {items.map((settlement) => (
            <tr key={settlement.settlementNumber} className="border-b border-border">
              <Td>
                <Link
                  href={`/seller/settlements/${settlement.settlementNumber}`}
                  className="
                    font-mono text-accent-text underline underline-offset-4
                    focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
                  "
                >
                  {settlement.settlementNumber}
                </Link>
              </Td>
              <Td muted>{settlement.sellerCode}</Td>
              <Td muted>
                {dateText(settlement.periodStart)} ~ {dateText(settlement.periodEnd)}
              </Td>
              <Td>{dateText(settlement.payoutDate)}</Td>
              <Td align="right">
                <span className="font-mono">{priceText(settlement.payoutAmount)}</span>
              </Td>
              <Td align="right">
                <Carried amount={settlement.carriedOver} />
              </Td>
              <Td>{payoutStatusText(settlement.payoutStatus)}</Td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/**
 * 다음 주기로 넘어간 금액.
 *
 * <p><b>0 을 빈 칸으로 두지 않는다</b> — 빈 칸은 「없다」와 「안 그렸다」가 안 갈린다.
 */
function Carried({ amount }: { amount: number }) {
  if (amount === 0) {
    return <span className="text-text-muted">없음</span>;
  }

  return <span className="font-mono">{priceText(amount)}</span>;
}

/**
 * 아무것도 못 그릴 때.
 *
 * <p><b>없는 것과 이 쪽에 없는 것을 가른다</b>(`D20` 「빈 상태」).
 * 뒤쪽은 주소를 직접 고쳐 들어온 경우라 돌아갈 길을 준다.
 */
function Empty({ hasAny }: { hasAny: boolean }) {
  if (hasAny) {
    return (
      <div className="grid justify-items-start gap-3 py-12">
        <p className="text-sm text-text-muted">이 쪽에는 정산서가 없습니다.</p>
        <Link
          href="/seller/settlements"
          className="
            text-sm font-semibold text-accent-text underline underline-offset-4
            focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
          "
        >
          첫 쪽으로 가기
        </Link>
      </div>
    );
  }

  return (
    <div className="grid gap-2 py-12">
      <p className="text-sm text-text-muted">아직 만들어진 정산서가 없습니다.</p>
      <p className="text-sm text-text-muted">
        정산 주기가 끝나면 이 자리에 표시됩니다.
      </p>
    </div>
  );
}
