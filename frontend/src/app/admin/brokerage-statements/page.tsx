import type { Metadata } from "next";

import { apiSession } from "@/lib/api-session";
import { priceText } from "@/lib/format";
import { pageQuery, type Page } from "@/lib/paging";

import { SubmitStatementButton } from "./submit-statement-button";

export const metadata: Metadata = { title: "중개 명세 · ProjectShop" };

/** 응답 상태 — 서버가 제출 기록·기한에서 계산한다(`Q456`) */
type StatementStatus = "OPEN" | "OVERDUE" | "SUBMITTED";

type Statement = {
  brokerageStatementId: number;
  periodYear: number;
  periodQuarter: number;
  /** 분기 말일의 다음 달 15일(부가가치세법 시행령 제121조제1항) */
  dueDate: string;
  status: StatementStatus;
  lineCount: number;
  totalAmount: number;
};

type StatementPage = Page<Statement> & {
  /** 제출 기록을 남길 수 있나. 감사자는 보기만 한다(`V139`) */
  canSubmit: boolean;
};

const STATUS_TEXT: Record<StatementStatus, string> = {
  OPEN: "제출 전",
  OVERDUE: "기한 지남",
  SUBMITTED: "제출됨",
};

/**
 * 판매·결제 중개 명세 분기 목록을 서버에서 받아 내려받기와 제출 기록을 낸다(`Q457`, 부가가치세법 제75조 `D2` R46).
 *
 * <p>분기가 끝나면 배치가 명세를 세운다. 관리자는 파일을 내려받아 홈택스에 올리고 「제출 기록」을 누른다 — 이 데모는 실제 제출을
 * 안 한다(2026-11-13 사용자 결정). <b>제출 버튼은 권한이 있고 아직 안 낸 분기에만 그린다</b>(`D20` 「권한 없는 것은 숨긴다」).
 */
export default async function BrokerageStatementsPage() {
  const page = await apiSession<StatementPage>(`/api/admin/brokerage-statements?${pageQuery(0, 50)}`);

  return (
    <>
      <div className="grid gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">중개 명세</h1>
        <p className="text-sm text-text-muted">
          분기마다 판매자별 판매·결제 건수와 금액을 국세청에 냅니다(부가가치세법 제75조).
          <br />
          파일을 내려받아 홈택스에 올리신 뒤 제출 기록을 남겨 주세요.
          {page.canSubmit ? "" : " 보기만 하실 수 있습니다."}
        </p>
      </div>

      <section aria-labelledby="statement-list-heading" className="grid gap-3">
        <h2 id="statement-list-heading" className="text-lg font-semibold">
          분기 {page.total}개
        </h2>

        {page.items.length > 0 ? (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <caption className="sr-only">분기별 중개 명세와 제출 상태</caption>
              <thead className="border-b border-border text-xs text-text-muted">
                <tr>
                  <th scope="col" className="py-2 pr-4 font-medium">분기</th>
                  <th scope="col" className="py-2 pr-4 font-medium">기한</th>
                  <th scope="col" className="py-2 pr-4 font-medium">상태</th>
                  <th scope="col" className="py-2 pr-4 font-medium">줄 수</th>
                  <th scope="col" className="py-2 pr-4 font-medium">금액</th>
                  <th scope="col" className="py-2 pr-4 font-medium">파일</th>
                  <th scope="col" className="py-2 font-medium">제출</th>
                </tr>
              </thead>
              <tbody>
                {page.items.map((statement) => {
                  const label = `${statement.periodYear}년 ${statement.periodQuarter}분기`;
                  return (
                    <tr key={statement.brokerageStatementId} className="border-b border-border">
                      <th scope="row" className="py-2 pr-4 font-medium">{label}</th>
                      <td className="py-2 pr-4">{statement.dueDate}</td>
                      <td className="py-2 pr-4">{STATUS_TEXT[statement.status]}</td>
                      <td className="py-2 pr-4">{statement.lineCount}</td>
                      <td className="py-2 pr-4">{priceText(statement.totalAmount)}</td>
                      <td className="py-2 pr-4">
                        {/* 파일 응답이라 `Link` 가 아니라 `a` 다 — 클라이언트 이동이 내려받기를 가로채면 안 된다 */}
                        <a
                          href={`/api/admin/brokerage-statements/${statement.brokerageStatementId}/file`}
                          className="underline"
                        >
                          내려받기<span className="sr-only"> — {label}</span>
                        </a>
                      </td>
                      <td className="py-2">
                        {page.canSubmit && statement.status !== "SUBMITTED" ? (
                          <SubmitStatementButton
                            statementId={statement.brokerageStatementId}
                            label={label}
                          />
                        ) : null}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        ) : (
          <p className="text-sm text-text-muted">아직 끝난 분기가 없습니다.</p>
        )}
      </section>
    </>
  );
}
