import { dateTimeText } from "@/lib/format";

/**
 * 보내기로 한 날이 지났다는 것을 사는 사람에게 알린다(`43a-4a`).
 *
 * <p><b>서버는 이미 알고 있었다.</b> {@code ship_overdue} 를 응답에 실어 주고
 * 공급 곤란 통지도 메일로 나가는데(`56`), <b>주문 화면만 아무 말을 안 했다</b> —
 * 메일을 못 본 사람에게는 아무 일도 없는 것처럼 보인다.
 *
 * <p><b>고시가 두 경우를 가른다</b>(소비자분쟁해결기준 별표2 「2. 온라인서비스 &gt;
 * ①인터넷쇼핑몰업」). 아직 안 보낸 것은 <b>미인도</b>고 계약해제 및 손해배상이다(2)·6),
 * `D2` R38). 늦게라도 보낸 것은 <b>지연인도</b>고, 구매목적을 달성 못 했으면 해제와 손해배상,
 * 불편에 그쳤으면 둘 중 하나다(3), `R39`).
 *
 * <p><b>그래서 안 보낸 것과 늦게 보낸 것에 다른 말을 한다.</b> 안 보낸 것은 지금 취소가 열려
 * 있으므로(`OrderStatusPolicy` 의 {@code cancel} 이 {@code preparing} 에서 열린다) 그 길을 알린다.
 * 떠난 물건은 취소가 아니라 반품이라(`glossary.md`) 그 말을 하면 거짓이 된다.
 *
 * <p><b>손해배상은 우리가 정하지 못한다.</b> 고시도 소비자기본법 시행령 별표1 도 <b>배상액을
 * 안 정하고</b>, 「구매목적을 달성했나」가 소비자 사정이라 기계가 혼자 가를 수도 없다.
 * 그래서 화면은 <b>말할 자리</b>를 가리킨다 — 같은 묶음에 이미 문의 입구가 있다(`58-2`).
 * 배상을 실제로 계산하고 정산에 반영하는 것은 별도 청크다(`43a-4b`).
 *
 * <p><b>색으로만 알리지 않는다</b>(`D20`·WCAG 1.4.1). 「지났습니다」가 글로 있어서
 * 색을 못 보는 사람에게도 같은 사실이 간다.
 */
export function ShipDelayNotice({
  shipDueAt,
  shipOverdue,
  shippedAt,
}: {
  /** 약정 발송 기한. 결제 승인 때 박제한 값이다 */
  shipDueAt: string | null;
  /** 늦었나. <b>서버가 판단한다</b> — 화면이 두 시각을 비교하면 시계 차이만큼 답이 갈린다 */
  shipOverdue: boolean;
  shippedAt: string | null;
}) {
  if (shipDueAt === null || !shipOverdue) {
    return null;
  }

  const unshipped = shippedAt === null;

  return (
    <div
      role="note"
      className="grid gap-1 rounded-ui border border-border bg-surface-raised p-3 text-sm"
    >
      <p className="font-semibold text-danger-text">
        보내기로 한 {dateTimeText(shipDueAt)}이 지났습니다.
      </p>
      {unshipped ? (
        <p className="text-text-muted">
          아직 발송되지 않았습니다. 기다리지 않으시려면 이 주문을 취소하실 수 있으며,
          취소하시면 결제하신 금액을 환급해 드립니다.
        </p>
      ) : (
        <p className="text-text-muted">
          {dateTimeText(shippedAt)}에 발송되었습니다. 늦게 받으셔서 생긴 일이 있으시면
          아래 문의로 알려 주세요.
        </p>
      )}
      <p className="text-text-muted">
        늦어진 사정이나 그로 인한 피해는 아래 문의로 접수해 주시면 확인 후 안내해 드리겠습니다.
      </p>
    </div>
  );
}
