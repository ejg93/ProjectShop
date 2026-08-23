import type { Metadata } from "next";
import Link from "next/link";

import { apiSession } from "@/lib/api-session";
import { dateText } from "@/lib/format";

import { InquiryForm } from "./inquiry-form";
import { WithdrawButton } from "./withdraw-button";

export const metadata: Metadata = { title: "내 문의 · ProjectShop" };

/**
 * 내가 낸 문의 한 줄(`59`). <b>비공개도 내려간 것도 여기서는 보인다</b> —
 * 자기 글이 왜 안 보이는지를 본인은 알아야 한다.
 */
export type MyInquiry = {
  inquiryNumber: string;
  kind: "PRODUCT" | "PROCESSING_STOP" | "ACCESS_OBJECTION" | "DISPUTE";
  productId: number | null;
  productName: string | null;
  question: string;
  answer: string | null;
  status: "RECEIVED" | "ANSWERED" | "BLOCKED" | "WITHDRAWN";
  isPublic: boolean;
  createdAt: string;
  answeredAt: string | null;
  dueAt: string | null;
  overdue: boolean;
};

type Page = { items: MyInquiry[]; page: number; size: number; total: number };

/** 종류를 사람 말로. <b>API 는 대문자, 화면은 우리말</b>(`D20`) */
const KIND_TEXT: Record<MyInquiry["kind"], string> = {
  PRODUCT: "상품 문의",
  PROCESSING_STOP: "처리 정지 요구",
  ACCESS_OBJECTION: "열람·정정 결과에 대한 이의제기",
  DISPUTE: "불만·분쟁 접수",
};

const STATUS_TEXT: Record<MyInquiry["status"], string> = {
  RECEIVED: "접수됨",
  ANSWERED: "답변 완료",
  BLOCKED: "게시 중단됨",
  WITHDRAWN: "거두었음",
};

/**
 * 내 문의(`59-1`).
 *
 * <p><b>이 화면이 법 요건 둘을 닫는다.</b>
 *
 * <ul>
 *   <li>개인정보법 제38조제4항(`D2` R28) — 열람등요구의 방법과 절차는 <b>수집보다 어렵지
 *       않아야 한다</b>. 수집은 `/signup` <b>화면</b>인데 처리정지·이의제기는 <b>API 뿐</b>이었다.
 *       `13e` 가 `/me` 를 세우며 닫은 자리가 `58`·`59` 로 API 만 서면서 다시 열려 있었다
 *   <li>전자상거래법 제20조제3항(`D2` R25) — 중개자가 불만·분쟁 해결에 필요한 조치를
 *       신속히 해야 한다. 받는 자리가 화면에 없으면 「신속히」가 성립을 안 한다
 * </ul>
 *
 * <p><b>상품 문의는 여기서 안 낸다.</b> 그것은 상품을 보면서 묻는 것이라 상품 상세에 있고,
 * 여기는 <b>낸 것을 모아 보는 자리</b>다 — 종류가 넷이어도 접수 폼은 계정에 붙는 셋만 낸다.
 *
 * <p><b>읽기는 여기, 쓰기는 잎사귀</b>(`D24`).
 */
export default async function MyInquiriesPage() {
  const page = await apiSession<Page>("/api/me/inquiries?size=50");

  return (
    <div className="mx-auto grid w-full max-w-3xl flex-1 content-start gap-10 px-4 py-16">
      <div className="grid gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">내 문의</h1>
        <p className="text-sm text-text-muted">
          보내신 문의와 답변을 여기서 보실 수 있습니다.
          <br />
          권리 행사 요구도 이 자리로 접수하실 수 있습니다.
        </p>
      </div>

      <section aria-labelledby="new-heading" className="grid gap-4">
        <h2 id="new-heading" className="text-lg font-semibold">
          권리 행사 요구하기
        </h2>
        <InquiryForm />
      </section>

      <section aria-labelledby="list-heading" className="grid gap-4">
        <h2 id="list-heading" className="text-lg font-semibold">
          보내신 문의 {page.total}건
        </h2>

        {page.items.length === 0 ? (
          <p className="rounded-ui border border-border bg-surface-raised p-5 text-sm text-text-muted">
            아직 보내신 문의가 없습니다.
          </p>
        ) : (
          <ul className="grid gap-4">
            {page.items.map((item) => (
              <InquiryCard key={item.inquiryNumber} item={item} />
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}

function InquiryCard({ item }: { item: MyInquiry }) {
  return (
    <li className="grid gap-3 rounded-ui border border-border bg-surface-raised p-5 text-sm">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
        <span className="font-semibold">{KIND_TEXT[item.kind]}</span>
        <span className="text-text-muted">{STATUS_TEXT[item.status]}</span>
        {item.isPublic ? null : <span className="text-text-muted">비공개</span>}
        <span className="text-text-muted">{dateText(item.createdAt)}</span>
      </div>

      {item.productId === null ? null : (
        <Link href={`/products/${item.productId}`} className="text-text-muted underline">
          {item.productName}
        </Link>
      )}

      <p className="whitespace-pre-wrap">{item.question}</p>

      {/*
        기한은 법이 정한 것만 나온다(`58-1`) — 처리정지가 시행령 제44조제2항의 10일이다.
        **넘긴 것이 조회로 드러나는 것이 강제의 천장**이라, 그 사실을 사람이 보는 자리가 여기다.
      */}
      {item.dueAt === null ? null : (
        <p className={item.overdue ? "text-danger-text" : "text-text-muted"}>
          {item.overdue
            ? `답변 기한(${dateText(item.dueAt)})이 지났습니다. 고객센터로 알려 주시기 바랍니다.`
            : `${dateText(item.dueAt)}까지 답변해 드립니다.`}
        </p>
      )}

      {item.answer === null ? null : (
        <div className="grid gap-1 rounded-ui bg-surface p-4">
          <p className="text-text-muted">
            답변 {item.answeredAt === null ? null : `· ${dateText(item.answeredAt)}`}
          </p>
          <p className="whitespace-pre-wrap">{item.answer}</p>
        </div>
      )}

      {item.status === "BLOCKED" ? (
        <p className="text-text-muted">
          광고성 게시물로 판단되어 게시가 중단되었습니다(정보통신망법 제50조의7).
          <br />
          이 글은 다른 분께 보이지 않습니다.
        </p>
      ) : null}

      {/* 거둘 수 있는 것은 아직 답이 안 나간 것뿐이다(`59-1`) */}
      {item.status === "RECEIVED" ? <WithdrawButton inquiryNumber={item.inquiryNumber} /> : null}
    </li>
  );
}
