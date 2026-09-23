import { dateTimeText } from "@/lib/format";
import { returnReasonText, returnStatusText } from "@/lib/order-text";

/** 서버의 `ReturnRequestQuery.Progress` 와 짝이다 */
export type ReturnProgress = {
  status: string;
  reasonCode: string;
  requestedAt: string;
  receivedAt: string | null;
  inspectedAt: string | null;
  decidedAt: string | null;
  /** 이 사람이 이 반품에 할 수 있는 것. 지금은 셀러의 {@code RECEIVE} 하나다 */
  allowedActions: string[];
};

/**
 * 반품이 어디까지 왔나(`43a-5`). 사는 사람·셀러·관리자 화면이 같이 쓴다.
 *
 * <p><b>안 지난 단계는 「아직」으로 적는다</b> — 빈칸이면 기록이 빠진 것인지 아직인지 못 가른다.
 * 검수는 입고 때 소견을 적었을 때만 지나서, 소견 없이 들어온 것은 「따로 안 함」이다.
 *
 * <p><b>표제를 제목 태그로 안 둔다</b> — 세 화면에서 놓이는 깊이가 달라 제목 단계가 어긋난다.
 */
export function ReturnProgressView({ progress }: { progress: ReturnProgress }) {
  const rows: [string, string][] = [
    ["사유", returnReasonText(progress.reasonCode)],
    ["상태", returnStatusText(progress.status)],
    ["접수", dateTimeText(progress.requestedAt)],
    ["입고", progress.receivedAt ? dateTimeText(progress.receivedAt) : "아직"],
    ["검수", progress.inspectedAt ? dateTimeText(progress.inspectedAt) : progress.receivedAt ? "따로 안 함" : "아직"],
    ["판정", progress.decidedAt ? dateTimeText(progress.decidedAt) : "아직"],
  ];

  return (
    <div className="grid gap-2 rounded-ui border border-border bg-surface-raised p-4 text-sm">
      <p className="font-semibold">반품 진행</p>
      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1">
        {rows.map(([label, value]) => (
          <div key={label} className="contents">
            <dt className="text-text-muted">{label}</dt>
            <dd>{value}</dd>
          </div>
        ))}
      </dl>
    </div>
  );
}
