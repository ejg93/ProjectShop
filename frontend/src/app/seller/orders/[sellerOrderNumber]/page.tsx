import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";

import { OrderActions } from "@/components/order-actions";
import { ApiError } from "@/lib/api";
import { apiSession } from "@/lib/api-session";
import { dateTimeText, priceText } from "@/lib/format";
import { returnReasonText, shipmentStatusText } from "@/lib/order-text";

import { SELLER_ACTIONS } from "../actions";

export const metadata: Metadata = { title: "받은 주문 상세 · ProjectShop" };

type Item = {
  productName: string;
  optionLabel: string | null;
  quantity: number;
  unitPriceInclVat: number;
  lineAmount: number;
};

/** 받는 사람. <b>못 보는 사람에게는 이 필드가 통째로 빠진다</b>(`D5` 필드 그룹) */
type Shipping = {
  receiverName: string;
  receiverPhone: string;
  postalCode: string;
  address1: string;
  address2: string | null;
  deliveryMemo: string | null;
};

type SellerOrderDetail = {
  sellerOrderNumber: string;
  orderNumber: string;
  status: string;
  shippingFee: number;
  shipDueAt: string | null;
  shippedAt: string | null;
  shipOverdue: boolean;
  deliveredAt: string | null;
  withdrawalExpireAt: string | null;
  autoConfirmAt: string | null;
  createdAt: string;
  /** 반품이 아니면 응답에 아예 없다(`11c-2c`) */
  returnReason?: string;
  items: Item[];
  allowedActions: string[];
  shipping?: Shipping;
};

/**
 * 받은 주문 하나(`13g`).
 *
 * <p><b>셀러가 여기서 하는 일은 셋이다</b> — 무엇을 보낼지 확인하고, 어디로 보낼지 읽고,
 * 처리했다고 기록한다. 그래서 품목·배송지·동작이 한 화면에 있다.
 *
 * <p><b>반품 사유를 그린다</b>(`11c-2c`). 하자 반품은 기한이 3개월이고 반환 비용을
 * 셀러가 진다(전자상거래법 제17조제3항·제18조제9항, `D2` R3) — <b>사유를 안 보여주면
 * 단순 변심으로 보고 거절하게 되고, 그 자리에서 법을 어긴다.</b>
 *
 * <p><b>배송지는 있을 때만 그린다.</b> 못 보는 사람에게는 서버가 필드를 통째로 뺀다 —
 * 화면이 「권한 없음」이라고 적으면 없는 정보의 존재를 알리는 것이 된다(`D5`).
 */
export default async function SellerOrderDetailPage({
  params,
}: {
  params: Promise<{ sellerOrderNumber: string }>;
}) {
  const { sellerOrderNumber } = await params;
  const order = await findSellerOrder(sellerOrderNumber);

  const itemsTotal = order.items.reduce((sum, item) => sum + item.lineAmount, 0);

  return (
    <>
      <div className="grid gap-2">
        <Link
          href="/seller/orders"
          className="
            justify-self-start text-sm text-text-muted underline underline-offset-4
            transition-colors duration-200 hover:text-text
            focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
          "
        >
          받은 주문으로
        </Link>
        <h1 className="font-mono text-2xl font-semibold tracking-tight">
          {order.sellerOrderNumber}
        </h1>
        <p className="text-sm text-text-muted">
          주문번호 <span className="font-mono">{order.orderNumber}</span> ·{" "}
          {shipmentStatusText(order.status)}
        </p>
      </div>

      {order.returnReason ? <ReturnNotice reason={order.returnReason} /> : null}

      <Section title="처리 시각">
        <Facts
          rows={[
            ["접수", dateTimeText(order.createdAt)],
            ["발송 기한", deadlineText(order)],
            ["발송", order.shippedAt ? dateTimeText(order.shippedAt) : "아직"],
            ["배송완료", order.deliveredAt ? dateTimeText(order.deliveredAt) : "아직"],
            [
              "청약철회 기한",
              order.withdrawalExpireAt ? dateTimeText(order.withdrawalExpireAt) : "아직",
            ],
            [
              "자동 구매확정",
              order.autoConfirmAt ? dateTimeText(order.autoConfirmAt) : "아직",
            ],
          ]}
        />
      </Section>

      <Section title="보낼 것">
        <ul className="grid gap-2">
          {order.items.map((item, index) => (
            <li
              key={`${item.productName}-${index}`}
              className="flex flex-wrap items-baseline justify-between gap-2 border-b border-border pb-2"
            >
              <span>
                {item.productName}
                {item.optionLabel ? (
                  <span className="text-text-muted"> · {item.optionLabel}</span>
                ) : null}
                <span className="text-text-muted"> × {item.quantity}</span>
              </span>
              <span className="font-mono">{priceText(item.lineAmount)}</span>
            </li>
          ))}
        </ul>

        <Facts
          rows={[
            ["상품 합계", priceText(itemsTotal)],
            ["배송비", priceText(order.shippingFee)],
          ]}
          mono
        />
      </Section>

      {order.shipping ? (
        <Section title="받는 사람">
          <Facts
            rows={[
              ["이름", order.shipping.receiverName],
              ["연락처", order.shipping.receiverPhone],
              [
                "주소",
                `(${order.shipping.postalCode}) ${order.shipping.address1}` +
                  (order.shipping.address2 ? ` ${order.shipping.address2}` : ""),
              ],
              ["요청사항", order.shipping.deliveryMemo ?? "없음"],
            ]}
          />
        </Section>
      ) : null}

      <OrderActions
        sellerOrderNumber={order.sellerOrderNumber}
        allowedActions={order.allowedActions}
        actions={SELLER_ACTIONS}
      />
    </>
  );
}

/**
 * 반품으로 들어온 묶음임을 먼저 알린다.
 *
 * <p><b>사유마다 셀러가 지는 것이 다르다</b>(`D2` R3). 하자면 반환 비용이 셀러 몫이고
 * 기한도 3개월이라, 그 사실을 화면 위쪽에 둔다 — 아래에 묻으면 못 보고 처리한다.
 */
function ReturnNotice({ reason }: { reason: string }) {
  const defect = reason === "defect";

  return (
    <div className="grid gap-1 rounded-ui border border-border bg-surface-raised p-4">
      <p className="text-sm font-semibold">반품 사유 · {returnReasonText(reason)}</p>
      <p className="text-sm text-text-muted">
        {defect
          ? "표시·광고와 다르거나 계약과 다르게 이행된 경우입니다. 반환에 드는 비용은 판매자가 부담하며, 청약철회 기간 제한이 적용되지 않습니다."
          : "구매자의 단순 변심입니다. 반환에 드는 비용은 구매자가 부담합니다."}
      </p>
    </div>
  );
}

/** 발송 기한 한 줄. <b>늦은 것을 색으로만 알리지 않는다</b>(`D20`·WCAG 1.4.1) */
function deadlineText(order: SellerOrderDetail): string {
  if (order.shipDueAt === null) {
    return "해당 없음";
  }
  return dateTimeText(order.shipDueAt) + (order.shipOverdue ? " (지남)" : "");
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
function Facts({ rows, mono = false }: { rows: [string, string][]; mono?: boolean }) {
  return (
    <dl className="grid grid-cols-[7rem_1fr] gap-x-3 gap-y-1 text-sm">
      {rows.map(([name, value]) => (
        <div key={name} className="col-span-2 grid grid-cols-subgrid">
          <dt className="text-text-muted">{name}</dt>
          <dd className={mono ? "font-mono" : undefined}>{value}</dd>
        </div>
      ))}
    </dl>
  );
}

/**
 * 그 묶음.
 *
 * <p><b>못 보는 것과 없는 것의 답이 같다</b>(`D5` 「권한 실패」). 서버가 남의 묶음과
 * 없는 묶음을 같은 404 로 주고, 화면도 그 답을 그대로 따른다 —
 * 가르면 번호를 훑어서 셀러별 거래 건수가 샌다.
 *
 * <p><b>화면의 HTTP 상태는 200 이다.</b> {@code notFound()} 가 스트리밍이 시작된 뒤에 던져서
 * 상태를 못 바꾼다(`stack.md`). 대신 {@code noindex} 가 붙는다.
 */
async function findSellerOrder(sellerOrderNumber: string): Promise<SellerOrderDetail> {
  try {
    return await apiSession<SellerOrderDetail>(
      `/api/seller/orders/${encodeURIComponent(sellerOrderNumber)}`,
    );
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      notFound();
    }
    throw error;
  }
}
