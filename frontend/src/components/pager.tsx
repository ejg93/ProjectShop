import Link from "next/link";

/**
 * 목록의 쪽 넘김. <b>목록 화면이 같이 쓴다</b>(`D20` 「목록 넘김」).
 *
 * <p><b>사본 둘을 하나로 모은 것이다</b>(`13c-1`). 상품 목록과 주문 목록이 같은 코드를
 * 각자 들고 있었고, <b>이미 어긋나 있었다</b> — 「색에만 기대지 않는다」를 적어 둔 주석이
 * 한쪽에만 남아 있었다. 셀러 목록이 셋째가 되기 전에 모은다.
 *
 * <p><b>서버 컴포넌트다.</b> 누르면 주소가 바뀌고 서버가 다시 그린다 —
 * 쪽 번호를 컴포넌트 상태로 들면 뒤로 가기·새로고침·링크 공유가 다 깨진다(`D24`).
 */

/** 쪽 번호를 몇 개까지 늘어놓나 */
const PAGE_LINK_WINDOW = 5;

type PagerProps = {
  /** 지금 쪽. 0부터 센다 — 서버가 그렇게 준다(`D5` 「목록」) */
  page: number;
  lastPage: number;
  total: number;

  /** 쪽 번호가 붙을 주소. {@code "/orders"} 꼴이다 */
  basePath: string;

  /**
   * 무엇의 목록인가. {@code "주문 목록"} 이면 {@code aria-label} 이 「주문 목록 쪽 넘기기」다.
   *
   * <p>화면마다 다른 말을 줘야 한다 — 한 쪽에 목록이 둘이면 보조기술이 둘을 못 가른다.
   */
  label: string;

  /** 세는 단위. 상품은 {@code "개"}, 주문은 {@code "건"} */
  unit: string;

  /**
   * 쪽을 넘겨도 유지할 조건. 정렬·필터가 여기 온다.
   *
   * <p><b>안 넘기면 조용히 떨어진다</b> — 셀러가 셀러 하나로 걸러 놓고 2쪽을 누르면
   * 거름이 풀린 목록이 나온다. 값이 없는 것은 안 싣는다.
   */
  params?: Record<string, string | undefined>;
};

export function Pager({ page, lastPage, total, basePath, label, unit, params }: PagerProps) {
  if (lastPage === 0) {
    return null;
  }

  const half = Math.floor(PAGE_LINK_WINDOW / 2);
  const start = Math.max(0, Math.min(page - half, lastPage - PAGE_LINK_WINDOW + 1));
  const end = Math.min(lastPage, start + PAGE_LINK_WINDOW - 1);
  const numbers = Array.from({ length: end - start + 1 }, (_, index) => start + index);

  const hrefOf = (target: number) => pageHref(basePath, target, params);

  return (
    <nav aria-label={`${label} 쪽 넘기기`} className="grid justify-items-center gap-3">
      <ul className="flex flex-wrap items-center justify-center gap-1">
        <li>
          <PagerLink href={hrefOf(page - 1)} disabled={page === 0} label="이전 쪽">
            이전
          </PagerLink>
        </li>

        {numbers.map((number) => (
          <li key={number}>
            <PagerLink
              href={hrefOf(number)}
              current={number === page}
              label={`${number + 1}쪽`}
            >
              {number + 1}
            </PagerLink>
          </li>
        ))}

        <li>
          <PagerLink href={hrefOf(page + 1)} disabled={page === lastPage} label="다음 쪽">
            다음
          </PagerLink>
        </li>
      </ul>

      {/* 지금 어디인지를 글로도 준다. 번호의 강조만으로 알리면 색에 기대는 것이 된다(`D20`) */}
      <p className="text-xs text-text-muted">
        전체 {total.toLocaleString("ko-KR")}
        {unit} 중 {page + 1} / {lastPage + 1}쪽
      </p>
    </nav>
  );
}

/**
 * 쪽 하나로 가는 링크.
 *
 * <p><b>갈 곳이 없으면 링크를 안 그린다.</b> 비활성 링크로 두면 보조기술이 읽고
 * 만질 수 없다고 말한다 — 안 그리면 없는 것이다(`D20` 「색만으로 알리지 않는다」).
 */
function PagerLink({
  href,
  label,
  children,
  current = false,
  disabled = false,
}: {
  href: string;
  label: string;
  children: React.ReactNode;
  current?: boolean;
  disabled?: boolean;
}) {
  const shape =
    "grid h-9 min-w-9 place-items-center rounded-ui px-3 text-sm transition-colors duration-200";

  if (disabled) {
    return <span className={`${shape} text-text-muted opacity-50`}>{children}</span>;
  }

  return (
    <Link
      href={href}
      aria-label={label}
      aria-current={current ? "page" : undefined}
      className={`
        ${shape}
        focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
        ${
          current
            ? "bg-accent font-semibold text-accent-on"
            : "border border-border hover:border-accent-text"
        }
      `}
    >
      {children}
    </Link>
  );
}

/**
 * 그 쪽의 주소.
 *
 * <p><b>첫 쪽에는 {@code page} 를 안 붙인다.</b> 같은 목록이 주소 둘을 갖게 되면
 * 링크를 공유했을 때 어느 쪽이 정본인지가 안 갈린다.
 */
function pageHref(
  basePath: string,
  page: number,
  params?: Record<string, string | undefined>,
): string {
  const query = new URLSearchParams();

  for (const [key, value] of Object.entries(params ?? {})) {
    if (value !== undefined && value !== "") {
      query.set(key, value);
    }
  }
  if (page > 0) {
    query.set("page", String(page));
  }

  const text = query.toString();
  return text === "" ? basePath : `${basePath}?${text}`;
}

/**
 * 주소에서 온 쪽 번호. <b>믿지 않는다</b> — 이상한 값은 첫 쪽으로 본다.
 *
 * <p>사람이 주소를 직접 고치고, 오래된 링크가 남는다. 검사를 화면마다 적으면
 * 한 화면이 음수를 그대로 서버에 넘기는 날이 온다.
 */
export function pageNumberOf(raw: string | undefined): number {
  const parsed = Number(raw);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : 0;
}
