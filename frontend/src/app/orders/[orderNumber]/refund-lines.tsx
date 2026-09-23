import { dateText, priceText } from "@/lib/format";
import { refundReasonText, refundStatusText } from "@/lib/refund-text";

/** 주문 상세가 내리는 환불 한 줄(`OrderQuery.Refund`). 요청 사유는 안 온다(`D2` R8) */
export type OrderRefund = {
  refundNumber: string;
  sellerOrderNumber: string;
  status: string;
  reasonCode: string;
  amount: number;
  dueAt: string;
  /** 기한을 넘겼나. 서버가 판단한 값이라 화면이 다시 재지 않는다(`D2` R5) */
  overdue: boolean;
  createdAt: string;
};

/**
 * 묶음 하나의 환불(`Q187`).
 *
 * <p><b>취소·반품 뒤 돈이 언제 오는지를 여기서 답한다</b>(전자상거래법 제18조제2항, `D2` R5). 대기 중이면 돌려줄
 * 날을 적고, 그날을 넘겼으면 지연배상금이 붙는다는 것까지 적는다 — 승인 때 서버가 더한다({@code RefundMath}).
 * 반려면 사유를 적는다 — 고객에게 왜 안 됐는지 답하는 값이다.
 *
 * <p><b>요청 버튼은 없다.</b> 닫힌 묶음의 환불은 스위퍼가 스스로 요청하고 승인한다({@code RefundSweeper}, `12a-3`).
 * 여기서 사람이 먼저 요청하면 스위퍼가 그 묶음을 건너뛰어 관리자 대기열로 가고, 돈이 더 늦게 나간다.
 */
export function RefundLines({
  refunds,
  rejectionReasons,
}: {
  refunds: OrderRefund[];
  rejectionReasons: Record<string, string>;
}) {
  if (refunds.length === 0) {
    return null;
  }

  return (
    <div className="grid gap-2 border-t border-border pt-2 text-sm">
      <h3 className="font-semibold">환불</h3>
      <ul className="grid gap-2">
        {refunds.map((refund) => (
          <li key={refund.refundNumber} className="grid gap-1">
            <div className="flex flex-wrap justify-between gap-2">
              <span>
                {refundReasonText(refund.reasonCode)} · {refundStatusText(refund.status)}
              </span>
              <span>{priceText(refund.amount)}</span>
            </div>
            <RefundNote refund={refund} rejectionReason={rejectionReasons[refund.refundNumber]} />
          </li>
        ))}
      </ul>
    </div>
  );
}

function RefundNote({ refund, rejectionReason }: { refund: OrderRefund; rejectionReason?: string }) {
  if (refund.status === "REQUESTED") {
    return refund.overdue ? (
      <p className="text-danger-text">
        환급 기한({dateText(refund.dueAt)})이 지났습니다.<br />
        늦어진 날만큼 지연배상금을 더해 돌려드립니다.
      </p>
    ) : (
      <p className="text-text-muted">{dateText(refund.dueAt)}까지 돌려드립니다.</p>
    );
  }
  if (refund.status === "REJECTED" && rejectionReason) {
    return <p className="text-text-muted">반려 사유: {rejectionReason}</p>;
  }
  return null;
}
