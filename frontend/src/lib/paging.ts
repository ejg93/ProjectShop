/**
 * 목록 질의와 껍데기를 한 곳에서 다룬다(`Q311c`, `frontend-rules.md`).
 *
 * <p><b>화면 URL 의 `?page=` 와 API 의 `offset` 을 가르는 자리다.</b> 화면은 사람이 읽는 쪽 번호(0 기점)를
 * 주소에 두고, API 는 건너뛸 행 수를 받는다(`api-guidelines.md` 「목록 조회」). 변환이 화면마다 있으면
 * 한 화면이 옛 이름을 보내는 날이 온다 — 백엔드는 모르는 파라미터를 무시해서 조용히 첫 쪽을 준다.
 */

/** 목록 응답 껍데기. 덧칸이 있는 입구는 `Page<T> & { … }` 로 늘린다 */
export type Page<T> = { items: T[]; offset: number; limit: number; total: number };

/** 화면의 쪽 번호와 쪽 크기를 API 질의로 바꾼다 */
export function pageQuery(page: number, limit: number): URLSearchParams {
  return new URLSearchParams({ offset: String(page * limit), limit: String(limit) });
}

/**
 * 마지막 쪽 번호(0 기점).
 *
 * <p><b>수가 아니면 0 이다.</b> 배포 사이 창에 새 화면이 옛 백엔드 껍데기(`page`·`size`)를 받으면
 * `limit` 이 없다 — 그대로 나누면 `Pager` 가 `NaN` 을 그린다.
 */
export function lastPageOf(result: { total: number; limit: number }): number {
  const last = Math.ceil(result.total / result.limit) - 1;
  return Number.isFinite(last) ? Math.max(0, last) : 0;
}
