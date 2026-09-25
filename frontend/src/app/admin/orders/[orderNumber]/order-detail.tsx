import { type ReturnProgress, ReturnProgressView } from "@/components/return-progress";
import { dateTimeText, priceText } from "@/lib/format";
import {
  carrierText,
  fromStatusText,
  paymentStatusText,
  shipmentStatusText,
  statusText,
} from "@/lib/order-text";
import { refundStatusText } from "@/lib/refund-text";

import { type CompensationListing, Compensations } from "./compensations";
import { ForceStatusForm } from "./force-status-form";
import { ReturnDecision } from "./return-decision";

type Item = {
  orderItemId: number;
  productName: string;
  optionLabel: string | null;
  quantity: number;
  lineAmount: number;
};

type SellerOrder = {
  sellerOrderNumber: string;
  sellerName: string;
  status: string;
  shipOverdue: boolean;
  carrierCode: string | null;
  trackingNo: string | null;
  items: Item[];
  /** 관리자가 강제로 옮길 수 있는 곳(`16c`). 서버가 고르고 권한이 없으면 비어 있다 */
  forcibleStatuses: string[];
  /** 관리자 몫만 온다(`Q202`) — 반품 승인·거절. 승인은 입고 뒤에만 실린다(`43a-5`) */
  allowedActions: string[];
  /** 가장 최근 반품의 진행(`43a-5`). 반품이 없으면 null */
  returnRequest: ReturnProgress | null;
};

type HistoryEntry = {
  sellerName: string | null;
  fromStatus: string | null;
  toStatus: string;
  actorType: string;
  occurredAt: string;
};

type Shipping = {
  receiverName: string;
  receiverPhone: string;
  postalCode: string;
  address1: string;
  address2: string | null;
  deliveryMemo: string | null;
};

type Payment = {
  status: string;
  approvalNumber: string | null;
  cardIssuer: string | null;
  cardLast4: string | null;
  paidAt: string;
};

type Refund = {
  refundNumber: string;
  sellerOrderNumber: string;
  status: string;
  amount: number;
  overdue: boolean;
};

/**
 * 관리자가 여는 주문 상세. {@code GET /api/orders/{번호}} 의 응답 그대로다(`Q176`).
 *
 * <p><b>못 보는 칸은 키가 없다</b>(`D5` 「null 과 생략」, `4d`) — 감사자에게는 {@code payment} 가 안 온다.
 * 그래서 선택 필드고, 화면은 키가 있을 때만 그 절을 그린다.
 */
export type AdminOrderDetail = {
  orderNumber: string;
  status: string;
  totalAmount: number;
  shippingFeeTotal: number;
  discountTotal: number;
  payableAmount: number;
  createdAt: string;
  sellerOrders: SellerOrder[];
  history: HistoryEntry[];
  shipping?: Shipping;
  payment?: Payment;
  refunds?: Refund[];
};

/** 보낸 뒤의 상태. 이 상태인데 송장이 없으면 송장 없이 옮겨진 묶음이다(`57`, `V104` 가 일부러 안 막았다) */
const SHIPPED_OR_LATER = new Set(["SHIPPING", "DELIVERED", "CONFIRMED", "RETURN_REQUESTED", "RETURNED"]);

/**
 * 관리자 주문 상세(`Q176`).
 *
 * <p><b>응답의 {@code allowedActions} 는 반품 판정 폼만 읽는다</b> — `Q202` 뒤로 관리자에게는 관리자 몫(승인·거절)만 온다.
 * 그 밖의 쓰기는 강제 전이(`16c`) 폼이고, 갈 곳은 {@code forcibleStatuses} 가 고른다.
 */
export function OrderDetailView({
  order,
  compensations = {},
}: {
  order: AdminOrderDetail;
  /** 묶음마다의 배상 판정(`43a-4c`). 조회 권한이 없으면 그 묶음 키가 없다 */
  compensations?: Record<string, CompensationListing>;
}) {
  return (
    <div className="grid gap-8">
      <div className="grid gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">
          주문 <span className="font-mono">{order.orderNumber}</span>
        </h1>
        <p className="text-sm text-text-muted">
          {paymentStatusText(order.status)} · {dateTimeText(order.createdAt)}
        </p>
      </div>

      <dl className="grid grid-cols-[auto_1fr] gap-x-6 gap-y-1 text-sm">
        <dt className="text-text-muted">상품 합계</dt>
        <dd>{priceText(order.totalAmount)}</dd>
        <dt className="text-text-muted">배송비</dt>
        <dd>{priceText(order.shippingFeeTotal)}</dd>
        <dt className="text-text-muted">할인</dt>
        <dd>{priceText(order.discountTotal)}</dd>
        <dt className="text-text-muted">결제 금액</dt>
        <dd className="font-semibold">{priceText(order.payableAmount)}</dd>
      </dl>

      {order.sellerOrders.map((bundle) => (
        <Bundle key={bundle.sellerOrderNumber} bundle={bundle} compensations={compensations[bundle.sellerOrderNumber]} />
      ))}

      {order.shipping ? <ShippingBox shipping={order.shipping} /> : null}
      {order.payment ? <PaymentBox payment={order.payment} /> : null}
      {order.refunds && order.refunds.length > 0 ? <Refunds refunds={order.refunds} /> : null}

      <History entries={order.history} />
    </div>
  );
}

function Bundle({ bundle, compensations }: { bundle: SellerOrder; compensations?: CompensationListing }) {
  const headingId = `bundle-${bundle.sellerOrderNumber}`;

  return (
    <section aria-labelledby={headingId} className="grid gap-2 border-t border-border pt-6 text-sm">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 id={headingId} className="font-semibold">
          {bundle.sellerName} <span className="font-mono text-xs text-text-muted">{bundle.sellerOrderNumber}</span>
        </h2>
        <p>
          {shipmentStatusText(bundle.status)}
          {bundle.shipOverdue ? <span className="ml-2 text-danger-text">발송 기한 지남</span> : null}
        </p>
      </div>

      {bundle.carrierCode ? (
        <p className="text-text-muted">
          {carrierText(bundle.carrierCode)} 송장 {bundle.trackingNo}
        </p>
      ) : SHIPPED_OR_LATER.has(bundle.status) ? (
        <p className="text-text-muted">송장 없음</p>
      ) : null}

      <ul className="grid gap-1">
        {bundle.items.map((item) => (
          <li key={item.orderItemId} className="flex flex-wrap justify-between gap-2">
            <span>
              {item.productName}
              {item.optionLabel ? <span className="text-text-muted"> · {item.optionLabel}</span> : null} × {item.quantity}
            </span>
            <span className="tabular-nums">{priceText(item.lineAmount)}</span>
          </li>
        ))}
      </ul>

      {bundle.returnRequest ? <ReturnProgressView progress={bundle.returnRequest} /> : null}
      <ReturnDecision
        sellerOrderNumber={bundle.sellerOrderNumber}
        allowedActions={bundle.allowedActions}
        inspected={bundle.returnRequest?.inspectedAt != null}
      />
      <ForceStatusForm sellerOrderNumber={bundle.sellerOrderNumber} forcibleStatuses={bundle.forcibleStatuses} />
      {compensations ? <Compensations sellerOrderNumber={bundle.sellerOrderNumber} listing={compensations} /> : null}
    </section>
  );
}

/** 받는 분. 파기 대상이라 보관 기간이 지나면 이 절이 통째로 없다(`D2` R9) */
function ShippingBox({ shipping }: { shipping: Shipping }) {
  return (
    <section aria-labelledby="shipping-heading" className="grid gap-2 border-t border-border pt-6">
      <h2 id="shipping-heading" className="text-sm font-semibold">
        받는 분
      </h2>
      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
        <dt className="text-text-muted">이름</dt>
        <dd>{shipping.receiverName}</dd>
        <dt className="text-text-muted">연락처</dt>
        <dd>{shipping.receiverPhone}</dd>
        <dt className="text-text-muted">주소</dt>
        <dd>
          ({shipping.postalCode}) {shipping.address1} {shipping.address2}
        </dd>
        {shipping.deliveryMemo ? (
          <>
            <dt className="text-text-muted">요청 사항</dt>
            <dd>{shipping.deliveryMemo}</dd>
          </>
        ) : null}
      </dl>
    </section>
  );
}

/** 결제. {@code payment} 그룹이라 감사자에게는 안 온다(`V6`) */
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
            <dd>{payment.cardIssuer ? `${payment.cardIssuer} ****${payment.cardLast4}` : "계좌이체"}</dd>
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

function Refunds({ refunds }: { refunds: Refund[] }) {
  return (
    <section aria-labelledby="refunds-heading" className="grid gap-2 border-t border-border pt-6">
      <h2 id="refunds-heading" className="text-sm font-semibold">
        환불
      </h2>
      <ul className="grid gap-1 text-sm">
        {refunds.map((refund) => (
          <li key={refund.refundNumber} className="flex flex-wrap justify-between gap-2">
            <span>
              <span className="font-mono">{refund.refundNumber}</span> · {refundStatusText(refund.status)}
              {refund.overdue ? <span className="ml-2 text-danger-text">기한 지남</span> : null}
            </span>
            <span className="tabular-nums">{priceText(refund.amount)}</span>
          </li>
        ))}
      </ul>
    </section>
  );
}

/** 처리 내역. 두 층(결제·배송)이 한 줄로 온다({@code OrderQuery.HistoryEntry}) */
function History({ entries }: { entries: HistoryEntry[] }) {
  return (
    <section aria-labelledby="history-heading" className="grid gap-2 border-t border-border pt-6">
      <h2 id="history-heading" className="text-sm font-semibold">
        처리 내역
      </h2>
      {entries.length === 0 ? (
        <p className="text-sm text-text-muted">아직 처리 내역이 없습니다.</p>
      ) : (
        <ol className="grid gap-2">
          {entries.map((entry, index) => (
            <li key={`${entry.occurredAt}-${index}`} className="flex flex-wrap justify-between gap-2 text-sm">
              <span>
                {entry.sellerName ? <span className="text-text-muted">{entry.sellerName} · </span> : null}
                {fromStatusText(entry.fromStatus)} → {statusText(entry.toStatus)}
              </span>
              <span className="text-xs text-text-muted">{dateTimeText(entry.occurredAt)}</span>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}
