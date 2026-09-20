import type { Metadata } from "next";

import { Pager, pageNumberOf } from "@/components/pager";
import { apiSession } from "@/lib/api-session";
import { dateTimeText } from "@/lib/format";

export const metadata: Metadata = { title: "감사 기록 · ProjectShop" };

/** 한 쪽에 몇 개. 서버 기본값과 같게 둔다(`D5` 「목록」) */
const PAGE_SIZE = 20;

type AuditLogRow = {
  auditLogId: number;
  /** `user.password_changed` 처럼 점으로 나뉜 이름. **닫힌 목록이 아니다** */
  eventType: string;
  /** 시스템이 한 일이면 없다(배치·만료) */
  actorUserId: number | null;
  targetType: string | null;
  targetId: number | null;
  detail: Record<string, unknown>;
  createdAt: string;
};

type AuditLogPage = {
  items: AuditLogRow[];
  page: number;
  size: number;
  total: number;
};

/**
 * 감사 기록(`Q132`).
 *
 * <p><b>관리자에게 갈 화면이 없어서 세운 첫 화면이다.</b> 역할은 셋인데 화면군은 둘이었고,
 * 관리자로 로그인하면 셸에 고객 메뉴만 떴다 — 가진 권한에 닿을 입구가 없었다.
 *
 * <p><b>감사 기록을 첫 자리로 골랐다.</b> 권한이 이미 `admin`·`auditor` 로 닫혀 있고(`V12`),
 * <b>읽기라 실수로 망가뜨릴 것이 없다</b>. 환불 승인처럼 돈이 움직이는 것은 그다음이다.
 *
 * <p><b>사건 이름을 한글로 안 옮긴다.</b> 목록이 닫혀 있지 않아서
 * ({@code user.password_changed}·{@code permission.denied} 처럼 서비스마다 늘어난다)
 * 표를 만들면 새 사건이 <b>빈칸으로 뜬다.</b> 읽는 사람이 관리자라 원래 이름이 더 정확하다.
 *
 * <p><b>판정은 서버가 한다.</b> {@code AuditLogQuery} 가 조회 전에 보고, 권한이 없으면
 * 여기까지 와도 응답이 안 온다 — 셸이 링크를 가리는 것은 <b>갈 곳이 있는 것처럼 보이지
 * 않게</b> 하는 것이지 방어가 아니다(`D20` 「권한 없는 것은 숨긴다」).
 *
 * <p>바깥 틀은 {@code admin/layout.tsx} 가 진다. 컨테이너를 여기서 다시 만들지 않는다.
 */
export default async function AdminAuditPage({
  searchParams,
}: {
  searchParams: Promise<{ page?: string }>;
}) {
  const requested = await searchParams;
  const page = pageNumberOf(requested.page);

  const query = new URLSearchParams({ page: String(page), size: String(PAGE_SIZE) });
  const result = await apiSession<AuditLogPage>(`/api/audit-logs?${query}`);
  const lastPage = Math.max(0, Math.ceil(result.total / result.size) - 1);

  return (
    <>
      <div className="grid gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">감사 기록</h1>
        <p className="text-sm text-text-muted">
          누가 언제 무엇을 했는지 남긴 기록입니다. 최근 것부터 보여 드립니다.
        </p>
      </div>

      {result.items.length > 0 ? (
        <>
          <AuditTable items={result.items} />
          <Pager
            page={result.page}
            lastPage={lastPage}
            total={result.total}
            basePath="/admin/audit"
            label="감사 기록 목록"
            unit="건"
          />
        </>
      ) : (
        <p className="rounded-ui border border-border bg-surface-raised px-4 py-6 text-sm text-text-muted">
          아직 남은 기록이 없습니다. 계정을 고치거나 권한이 막히는 일이 생기면 여기에 쌓입니다.
        </p>
      )}
    </>
  );
}

/**
 * 기록을 줄로 그린다.
 *
 * <p><b>표다</b>(`D20` 밀도 7). 여러 건을 훑는 자리라 칸이 세로로 맞아야 눈이 훑는다.
 *
 * <p><b>자세한 값은 접는다.</b> {@code detail} 은 사건마다 모양이 달라서 칸을 못 맞춘다 —
 * 펴 두면 줄 높이가 제각각이 되고, 그러면 위 문장의 이유가 사라진다.
 */
function AuditTable({ items }: { items: AuditLogRow[] }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[44rem] border-collapse text-sm">
        <caption className="sr-only">감사 기록 목록. 시각, 사건, 한 사람, 대상 순</caption>
        <thead>
          <tr className="border-b border-border text-left text-xs text-text-muted">
            <Th>시각</Th>
            <Th>사건</Th>
            <Th>한 사람</Th>
            <Th>대상</Th>
            <Th>자세히</Th>
          </tr>
        </thead>
        <tbody>
          {items.map((row) => (
            <tr key={row.auditLogId} className="border-b border-border align-top">
              <Td muted>{dateTimeText(row.createdAt)}</Td>
              <Td>
                <span className="font-mono">{row.eventType}</span>
              </Td>
              <Td>
                <Actor userId={row.actorUserId} />
              </Td>
              <Td>
                <Target type={row.targetType} id={row.targetId} />
              </Td>
              <Td>
                <Detail value={row.detail} />
              </Td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/**
 * 누가 했나.
 *
 * <p><b>없는 것과 모르는 것을 가른다.</b> 비어 있으면 사람이 한 일이 아니다 —
 * 배치나 만료가 일으킨 사건이라 「시스템」이라고 적는다(`D20` 「빈 상태」).
 */
function Actor({ userId }: { userId: number | null }) {
  if (userId === null) {
    return <span className="text-text-muted">시스템</span>;
  }
  return <span className="font-mono">#{userId}</span>;
}

/** 무엇에 대한 사건인가. 대상이 없는 사건도 있다 */
function Target({ type, id }: { type: string | null; id: number | null }) {
  if (type === null) {
    return <span className="text-text-muted">—</span>;
  }
  return (
    <span className="font-mono">
      {type}
      {id === null ? "" : ` #${id}`}
    </span>
  );
}

/** 사건마다 모양이 다른 값. 접어 두고 편 사람만 본다 */
function Detail({ value }: { value: Record<string, unknown> }) {
  const entries = Object.entries(value ?? {});

  if (entries.length === 0) {
    return <span className="text-text-muted">—</span>;
  }

  return (
    <details>
      <summary
        className="
          cursor-pointer text-xs text-text-muted
          focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
        "
      >
        {entries.length}개 항목
      </summary>
      <dl className="grid gap-1 pt-2 text-xs">
        {entries.map(([key, entry]) => (
          <div key={key} className="flex gap-2">
            <dt className="font-mono text-text-muted">{key}</dt>
            <dd className="font-mono break-all">{String(entry)}</dd>
          </div>
        ))}
      </dl>
    </details>
  );
}

function Th({ children }: { children: React.ReactNode }) {
  return <th scope="col" className="px-3 py-2 font-medium">{children}</th>;
}

function Td({ children, muted = false }: { children: React.ReactNode; muted?: boolean }) {
  return (
    <td className={`px-3 py-2 ${muted ? "text-text-muted" : ""}`}>{children}</td>
  );
}
