/** 검색어 상한. 목록 화면이 주소의 검색어를 자를 때 쓴다 — 아래 `maxLength` 와 같은지는 이 파일의 시험이 본다 */
export const SEARCH_TERM_MAX_LENGTH = 100;

/**
 * 상품 검색창(`61b`). 검색어를 주소의 `q` 로 보낸다.
 *
 * <p><b>`method="get"` 폼이다</b>(`D24` 「상태는 주소에 둔다」). 검색어가 주소에 남아야 뒤로 가기·새로고침·
 * 링크 공유가 그대로 되고, 서버 컴포넌트가 그 값을 읽어 그린다. 그래서 클라이언트 상태도 자바스크립트도 없다.
 *
 * <p><b>`maxLength` 100 은 서버 `@Size(max = 100)` 과 같다</b> — `ScreenLengthTest` 가 칸 이름 `q` 로 서버의
 * `@RequestParam @Size` 와 맞댄다. 그 시험이 태그 안의 숫자를 읽으므로 여기는 글자로 둔다.
 *
 * <p>검색창은 `/products` 안에만 둔다 — 머리 셸에 두면 `Q213` 의 줄 규칙에 걸린다.
 */
export function ProductSearchForm({ q }: { q?: string }) {
  return (
    <form method="get" action="/products" role="search" className="grid gap-2">
      <label htmlFor="product-search-q" className="text-sm font-semibold">
        상품 검색
      </label>
      <div className="flex gap-2">
        <input
          id="product-search-q"
          name="q"
          type="search"
          maxLength={100}
          defaultValue={q}
          autoComplete="off"
          className="
            min-w-0 flex-1 rounded-ui border border-border bg-surface-raised px-3 py-2.5 text-base
            transition-colors duration-200
            focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
          "
        />
        <button
          type="submit"
          className="
            rounded-ui border border-border px-4 py-2 text-sm font-semibold
            transition-colors duration-200
            hover:bg-surface
            focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
          "
        >
          검색
        </button>
      </div>
    </form>
  );
}
