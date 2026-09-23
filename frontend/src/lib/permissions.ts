/**
 * 이 사람이 무엇을 할 수 있나. <b>화면이 역할 이름을 모른다</b>(`D24`).
 *
 * <p><b>사본 둘을 하나로 모은 것이다</b>(`20-1`). 머리(`site-header`)가 이 모양을 혼자 들고
 * 있었는데 정산 화면이 두 번째 사용자가 됐다 — 거기 두고 가져다 쓰면 <b>화면 하나가 다른
 * 화면의 조각을 부르는 모양</b>이 된다({@link ./format} 이 `13e` 에서 같은 판단을 했다).
 *
 * <p><b>이 목록은 근사치다</b>({@code PermissionCatalog}). 여기 뜬다고 그 자원을 만질 수 있는
 * 것이 아니라 실제 판정은 만질 때 서버가 다시 한다 — 화면이 <b>무엇을 그릴지</b> 정하는 데만 쓴다.
 */

/**
 * <p>{@code scopes} 는 <b>대문자 스네이크다</b>(`D5` 「값의 형식」, `43a-20`) — `OWN`·`SELLER`·`ALL`.
 *
 * <p><b>합집합 타입으로 안 적는다.</b> 지금 범위 값을 비교하는 자리가 없어서, 여기 목록을
 * 적으면 <b>서버와 안 맞춰지는 사본</b>만 는다. 비교하는 자리가 생기면 그때 판다.
 */
export type Permission = { resource: string; action: string; scopes: string[] };

/**
 * {@code /api/me/permissions} 가 주는 것. 로그인 여부와 권한이 한 번에 온다.
 *
 * @property impersonatedBy 대행 중이면 시킨 관리자(`16b`). 그때 `userId`·`permissions` 는 대상 사용자의 것이다
 */
export type Me = { userId: number; permissions: Permission[]; impersonatedBy?: number | null };

/**
 * 그 동작이 열려 있나.
 *
 * <p><b>비로그인을 여기서 받는다.</b> 부르는 쪽마다 {@code me &&} 를 앞에 붙이면
 * 한 화면이 그것을 빠뜨리는 날이 오고, 그날 <b>비로그인에게 관리자 버튼이 난다.</b>
 *
 * <p><b>범위를 안 본다.</b> 범위는 어느 자원에 걸리느냐를 정하는 값이라 대상을 알아야 쓰는데,
 * 화면이 대상까지 재면 판정이 두 벌이 된다 — 그래서 <b>대상별 판정은 서버가 하고</b>
 * 화면은 「이 동작을 아예 못 하는 사람인가」만 본다.
 */
export function can(me: Me | null, resource: string, action: string): boolean {
  return me !== null
    && me.permissions.some(
      (granted) => granted.resource === resource && granted.action === action,
    );
}
