import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";

import { ApiError } from "@/lib/api";
import { apiSession } from "@/lib/api-session";
import { dateText, dateTimeText, priceText } from "@/lib/format";
import {
  paymentStatusText,
  shipmentStatusText,
  statusText,
} from "@/lib/order-text";

import { OrderActions } from "@/components/order-actions";
import { PolicyBody } from "@/components/policy-document";

import { ORDER_ACTIONS } from "../status";

export const metadata: Metadata = { title: "주문 상세 · ProjectShop" };

type Item = {
  productName: string;
  optionLabel: string | null;
  quantity: number;
  unitPriceInclVat: number;
  lineAmount: number;
};

type SellerOrder = {
  sellerOrderNumber: string;
  sellerName: string;
  status: string;
  shippingFee: number;
  deliveredAt: string | null;
  withdrawalExpireAt: string | null;
  autoConfirmAt: string | null;
  items: Item[];
  allowedActions: string[];
};

type HistoryEntry = {
  sellerName: string | null;
  fromStatus: string;
  toStatus: string;
  actorType: string;
  occurredAt: string;
};

type Payment = {
  status: string;
  method: string;
  approvalNumber: string | null;
  cardIssuer: string | null;
  cardLast4: string | null;
  declineReason: string | null;
  paidAt: string;
};

type Shipping = {
  receiverName: string;
  receiverPhone: string;
  postalCode: string;
  address1: string;
  address2: string | null;
  deliveryMemo: string | null;
};

/** 못 보는 것은 통째로 빠진다(`D5` 「null 과 생략」). 그래서 필드가 선택이다 */
type OrderDetail = {
  orderNumber: string;
  status: string;
  totalAmount: number;
  shippingFeeTotal: number;
  payableAmount: number;
  createdAt: string;
  sellerOrders: SellerOrder[];
  history: HistoryEntry[];
  shipping?: Shipping;
  payment?: Payment;
  contractDocuments: ContractDocument[];
};

/**
 * 계약내용 서면 한 줄(`15-4`). <b>본문은 여기 없다</b> — 조항 수만큼 약관 전문이 딸려 나오면
 * 주문 상세가 무거워져서, 펼칠 때 하나씩 받는다(`5k` 가 동의 고지에서 같은 판단을 했다).
 */
type ContractDocument = {
  clause: string;
  code: string;
  title: string;
  version: number;
  effectiveAt: string;
};

/** 조항 하나를 본문까지 펼친 것. <b>이 주문이 가리키는 판</b>이지 지금 효력 있는 판이 아니다 */
type ContractDocumentBody = ContractDocument & { body: string };

/**
 * 주문 상세(`15-3`).
 *
 * <p><b>거래기록 열람이 여기서 닫힌다</b>(`D2` R6 제3항, 전자상거래법 제6조).
 * 상태 이력을 같이 그려서 <b>언제 무엇이 일어났는지</b>에 답한다 — 현재 상태만 보여주면
 * 「언제 배송됐나」에 답할 수 없다(`ADR 0007`).
 *
 * <p><b>동작 버튼은 서버가 정한 것만 그린다</b>(`allowed_actions`, `11c-3b`).
 * 화면이 상태를 보고 판단하면 서버 규칙과 두 벌이 되고, 그때 눌러야 403 이 난다(`D20`).
 */
export default async function OrderDetailPage({
  params,
}: {
  params: Promise<{ orderNumber: string }>;
}) {
  const { orderNumber } = await params;
  const order = await findOrder(orderNumber);

  return (
    <div className="mx-auto grid w-full max-w-4xl flex-1 content-start gap-8 px-4 py-16">
      <div className="grid gap-2">
        <p className="text-sm text-text-muted">
          <Link
            href="/orders"
            className="
              underline underline-offset-4 hover:text-accent-text
              focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
            "
          >
            내 주문
          </Link>
        </p>
        <h1 className="text-3xl font-semibold tracking-tight">{order.orderNumber}</h1>
        <p className="text-sm text-text-muted">
          {dateTimeText(order.createdAt)} · {paymentStatusText(order.status)}
        </p>
        {/*
          거래기록 보존(전자상거래법 제6조제1항 후단, `D2` R6). 이 화면은 열람까지고,
          계정이 막히거나 서비스가 닫히면 남는 사본이 없다. 파일은 서버가 만든다(`15-5`).

          `Link` 가 아니라 `a` 다 — 클라이언트 전환이 아니라 브라우저가 파일로 받아야 한다.
        */}
        <p className="text-sm">
          <a
            href={`/api/orders/${encodeURIComponent(order.orderNumber)}/record`}
            className="
              underline underline-offset-4 hover:text-accent-text
              focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
            "
          >
            거래기록 내려받기 (.txt)
          </a>
        </p>
      </div>

      <div className="grid gap-4">
        {order.sellerOrders.map((bundle) => (
          <SellerBundle key={bundle.sellerOrderNumber} bundle={bundle} />
        ))}
      </div>

      <Amounts order={order} />

      {order.payment ? <PaymentBox payment={order.payment} /> : null}
      {order.shipping ? <ShippingBox shipping={order.shipping} /> : null}

      <ContractDocuments orderNumber={order.orderNumber} documents={order.contractDocuments} />

      <History entries={order.history} />
    </div>
  );
}

/**
 * 셀러 묶음 하나. <b>취소·확정·반품이 이 단위로 걸린다</b>(`D7`) —
 * 주문 전체가 아니라 셀러 묶음이 최소 단위다.
 */
function SellerBundle({ bundle }: { bundle: SellerOrder }) {
  return (
    <section
      aria-labelledby={`bundle-${bundle.sellerOrderNumber}`}
      className="grid gap-3 rounded-ui border border-border bg-surface-raised p-4"
    >
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 id={`bundle-${bundle.sellerOrderNumber}`} className="text-sm font-semibold">
          {bundle.sellerName}
        </h2>
        <p className="text-sm">{shipmentStatusText(bundle.status)}</p>
      </div>

      <ul className="grid gap-2">
        {bundle.items.map((item, index) => (
          <li
            key={`${item.productName}-${index}`}
            className="flex flex-wrap justify-between gap-2 text-sm"
          >
            <span>
              {item.productName}
              {item.optionLabel ? (
                <span className="text-text-muted"> · {item.optionLabel}</span>
              ) : null}
              <span className="text-text-muted"> × {item.quantity}개</span>
            </span>
            <span>{priceText(item.lineAmount)}</span>
          </li>
        ))}
      </ul>

      <p className="flex justify-between border-t border-border pt-2 text-sm text-text-muted">
        <span>배송비</span>
        <span>{priceText(bundle.shippingFee)}</span>
      </p>

      <Deadlines bundle={bundle} />

      <OrderActions
        sellerOrderNumber={bundle.sellerOrderNumber}
        allowedActions={bundle.allowedActions}
        actions={ORDER_ACTIONS}
      />
    </section>
  );
}

/**
 * 기한 둘. <b>배송완료 때 박제한 값을 그대로 보여준다</b>(`D10`) —
 * 화면이 다시 계산하면 임시공휴일이 추가됐을 때 지나간 주문의 기한까지 흔들린다.
 */
function Deadlines({ bundle }: { bundle: SellerOrder }) {
  if (!bundle.deliveredAt) {
    return null;
  }

  return (
    <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 border-t border-border pt-2 text-xs text-text-muted">
      <dt>배송 완료</dt>
      <dd>{dateTimeText(bundle.deliveredAt)}</dd>

      {bundle.withdrawalExpireAt ? (
        <>
          <dt>청약철회 기한</dt>
          <dd>{dateText(bundle.withdrawalExpireAt)}까지</dd>
        </>
      ) : null}

      {/*
        약관규제법 제12조1호(`R31`, `D2-7`). 부작위를 의사표시로 읽는 조항은
        **그 뜻을 명확하게 따로 고지**해야 산다 — 날짜만 그리면 읽는 사람은
        그것을 예정일로 볼 뿐, 자기가 가만히 있는 것이 구매확정이 된다는 것을 모른다.
      */}
      {bundle.autoConfirmAt ? (
        <>
          <dt>자동 구매확정</dt>
          <dd>
            {dateText(bundle.autoConfirmAt)}
            <br />
            <span className="text-xs text-text-muted">
              이날까지 구매확정도 반품 신청도 하지 않으시면 구매확정하신 것으로 봅니다.
            </span>
          </dd>
        </>
      ) : null}
    </dl>
  );
}

function Amounts({ order }: { order: OrderDetail }) {
  return (
    <section aria-labelledby="amounts-heading" className="grid gap-2 border-t border-border pt-6">
      <h2 id="amounts-heading" className="text-sm font-semibold">
        결제 금액
      </h2>

      <p className="flex justify-between text-sm">
        <span className="text-text-muted">상품 금액</span>
        <span>{priceText(order.totalAmount)}</span>
      </p>
      <p className="flex justify-between text-sm">
        <span className="text-text-muted">배송비</span>
        <span>{priceText(order.shippingFeeTotal)}</span>
      </p>
      <p className="flex justify-between border-t border-border pt-2 text-base font-semibold">
        <span>결제 금액</span>
        <span>{priceText(order.payableAmount)}</span>
      </p>
    </section>
  );
}

/**
 * 결제 내역.
 *
 * <p><b>셀러에게는 이 절이 통째로 안 나간다</b>(`V6` 의 `payment` 필드 그룹, `D2` R18).
 * 여기 있는 것이 우리가 가진 전부다 — 카드번호는 담은 적이 없다.
 */
function PaymentBox({ payment }: { payment: Payment }) {
  const approved = payment.status === "APPROVED";

  return (
    <section aria-labelledby="payment-heading" className="grid gap-2 border-t border-border pt-6">
      <h2 id="payment-heading" className="text-sm font-semibold">
        결제 정보
      </h2>

      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
        <dt className="text-text-muted">결제 일시</dt>
        <dd>{dateTimeText(payment.paidAt)}</dd>

        {approved ? (
          <>
            <dt className="text-text-muted">승인번호</dt>
            <dd>{payment.approvalNumber}</dd>
            <dt className="text-text-muted">결제 수단</dt>
            <dd>
              {payment.cardIssuer ? `${payment.cardIssuer} ****${payment.cardLast4}` : "계좌이체"}
            </dd>
          </>
        ) : (
          <>
            <dt className="text-text-muted">결과</dt>
            <dd>거절됨</dd>
          </>
        )}
      </dl>
    </section>
  );
}

/**
 * 배송지.
 *
 * <p><b>파기 대상이라 주문에서 떼어 놨다</b>(`D2` R9, `10-1`). 보관 기간이 지나면
 * 이 절이 통째로 사라지고 주문은 남는다.
 */
function ShippingBox({ shipping }: { shipping: Shipping }) {
  return (
    <section aria-labelledby="shipping-heading" className="grid gap-2 border-t border-border pt-6">
      <h2 id="shipping-heading" className="text-sm font-semibold">
        배송지
      </h2>

      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
        <dt className="text-text-muted">받는 분</dt>
        <dd>{shipping.receiverName}</dd>

        <dt className="text-text-muted">연락처</dt>
        <dd>{shipping.receiverPhone}</dd>

        <dt className="text-text-muted">주소</dt>
        <dd>
          ({shipping.postalCode}) {shipping.address1}
          {shipping.address2 ? ` ${shipping.address2}` : ""}
        </dd>

        {shipping.deliveryMemo ? (
          <>
            <dt className="text-text-muted">요청사항</dt>
            <dd>{shipping.deliveryMemo}</dd>
          </>
        ) : null}
      </dl>
    </section>
  );
}

/**
 * 상태 이력.
 *
 * <p><b>이 절이 `R6` 제3항이다</b>(전자상거래법 제6조). 거래에 관한 기록을 소비자가
 * 열람할 수 있어야 하고, 「지금 어떤 상태인가」가 아니라 <b>언제 무엇이 있었나</b>가 그 기록이다.
 *
 * <p>사람 이름이 없다. 누가 옮겼는지는 역할이면 충분하고, 계정이 파기돼도 이력은 5년 남는다(`D13`).
 */
/** 조항이 무엇을 담는지. 모르는 값이 오면 문서 제목만 그린다(`D5` 「모르는 열거값은 무시한다」) */
const CLAUSE_TEXT: Record<string, string> = {
  WITHDRAWAL: "청약철회",
  EXCHANGE: "교환·반품",
  DISPUTE: "분쟁 해결",
  TERMS: "이용약관",
};

/**
 * 이 주문에 적용된 계약내용 서면(`Q4`, `D2` R22).
 *
 * <p><b>전자상거래법 제13조제2항 후단이 「교부」라고 한다.</b> 서버가 주문 시점의 판을 박제해
 * 응답에 싣고 있었는데 화면이 안 그려서, 저장은 됐고 도달이 없는 상태였다 —
 * 그건 교부가 아니다.
 *
 * <p><b>지금 효력 있는 판이 아니라 이 주문이 계약한 판이다.</b> 발 아래의 `/terms`·`/privacy` 는
 * 최신판을 보여주므로, 개정된 뒤에 그 링크를 보내면 <b>그 사이 우리가 고친 것을 들이미는 꼴</b>이 된다.
 *
 * <p>본문은 조항마다 따로 받는다. 목록에 실으면 약관 전문이 조항 수만큼 딸려 나온다.
 * <b>서버 컴포넌트라 마크다운을 브라우저로 안 내려보낸다</b>(`D24`) — 읽는 것이지 누르는 것이 아니다.
 */
async function ContractDocuments({
  orderNumber,
  documents,
}: {
  orderNumber: string;
  documents: ContractDocument[];
}) {
  if (documents.length === 0) {
    return null;
  }

  const bodies = await Promise.all(
    documents.map((document) =>
      apiSession<ContractDocumentBody>(
        `/api/orders/${encodeURIComponent(orderNumber)}/contract-documents/${document.clause}`,
      ),
    ),
  );

  return (
    <section aria-labelledby="contract-heading" className="grid gap-3 border-t border-border pt-6">
      <h2 id="contract-heading" className="text-lg font-semibold">
        계약 내용
      </h2>
      <p className="text-sm text-text-muted">
        주문하실 때 적용된 판입니다. 이후에 개정되어도 이 주문에는 아래 내용이 그대로 적용됩니다.
      </p>

      {bodies.map((document) => (
        <details
          key={document.clause}
          className="rounded-ui border border-border bg-surface-raised p-4"
        >
          <summary
            className="
              cursor-pointer text-sm font-semibold
              focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
            "
          >
            {CLAUSE_TEXT[document.clause] ?? document.title}{" "}
            <span className="font-normal text-text-muted">
              제{document.version}판 · {dateText(document.effectiveAt)} 시행
            </span>
          </summary>
          <div className="mt-3 grid gap-3 border-l border-border pl-4">
            <PolicyBody>{document.body}</PolicyBody>
          </div>
        </details>
      ))}
    </section>
  );
}

function History({ entries }: { entries: HistoryEntry[] }) {
  return (
    <section aria-labelledby="history-heading" className="grid gap-3 border-t border-border pt-6">
      <h2 id="history-heading" className="text-sm font-semibold">
        처리 내역
      </h2>

      {entries.length === 0 ? (
        <p className="text-sm text-text-muted">아직 처리 내역이 없습니다.</p>
      ) : (
        <ol className="grid gap-2">
          {entries.map((entry, index) => (
            <li
              key={`${entry.occurredAt}-${index}`}
              className="flex flex-wrap justify-between gap-2 text-sm"
            >
              <span>
                {entry.sellerName ? (
                  <span className="text-text-muted">{entry.sellerName} · </span>
                ) : null}
                {statusText(entry.fromStatus)} → {statusText(entry.toStatus)}
              </span>
              <span className="text-xs text-text-muted">{dateTimeText(entry.occurredAt)}</span>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}

/**
 * 없는 주문이면 없는 쪽을 그린다.
 *
 * <p>서버가 <b>남의 주문과 없는 주문을 같은 404</b> 로 준다(`D5`) — 가르면 번호를 훑어서
 * 주문 수와 증가 속도가 샌다. 화면도 그 답을 그대로 따른다.
 *
 * <p><b>다만 HTTP 상태는 200 이다.</b> 여기 적혀 있던 「200 으로 답하지 않는다」는 틀린 말이었다 —
 * {@code notFound()} 가 스트리밍이 시작된 뒤에 던져서 상태를 못 바꾼다(`stack.md`).
 * 대신 {@code noindex} 가 붙어 색인에는 안 들어간다.
 */
async function findOrder(orderNumber: string): Promise<OrderDetail> {
  try {
    return await apiSession<OrderDetail>(`/api/orders/${encodeURIComponent(orderNumber)}`);
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      notFound();
    }
    throw error;
  }
}
