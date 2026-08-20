import { cookies } from "next/headers";
import { redirect } from "next/navigation";

import { BACKEND_ORIGIN, toApiError, toCamel } from "./api";

/**
 * 로그인해야 보는 것을 서버 컴포넌트에서 읽는다(`D24` 「인증이 필요한 데이터」).
 *
 * <p><b>파일이 갈린 이유가 `next/headers` 다.</b> 그것을 `api.ts` 에 들이면
 * 그 파일을 가져다 쓰는 클라이언트 컴포넌트가 전부 빌드에서 깨진다.
 * 변환과 오류 처리는 저쪽 것을 그대로 쓴다 — 두 벌이 되면 어떤 응답만 카멜로 바뀐다(`D5`).
 *
 * <h2>왜 손으로 싣나</h2>
 *
 * <p>서버 컴포넌트를 그리는 것은 브라우저가 아니라 Next 서버다. 브라우저가 자동으로 붙이던
 * 쿠키가 여기서는 안 붙어서, <b>백엔드가 보기에 낯선 손님</b>이 된다.
 *
 * <pre>
 * 브라우저 ──(쿠키 자동)──> Next 서버 ──(안 붙음)──> 백엔드 → 401
 * </pre>
 *
 * <p><b>운반이 이 파일 하나에 갇혀 있어야 한다</b>(`D24`). 만지는 자리가 여럿이면
 * 세션 값을 로그에 찍거나, 백엔드 아닌 곳에 싣거나, 응답이 캐시되는 사고가 각각 가능해진다.
 *
 * <p><b>프론트가 세션을 읽는 것이 아니다.</b> 쿠키에 든 것은 뜻 없는 식별자고
 * (`D14` 가 서버 메모리 세션으로 정했다) 누구인지 판정하는 것은 백엔드뿐이다.
 */

/**
 * 백엔드로 넘길 쿠키.
 *
 * <p>둘인 이유는 <b>장바구니가 로그인 전에도 사는 자원</b>이라서다(청크 9).
 * 세션만 나르면 비로그인 장바구니가 서버 컴포넌트에서 통째로 빈 채로 그려진다.
 *
 * <p><b>목록으로 가둔다.</b> 요청에 온 쿠키를 통째로 넘기면 나중에 프론트가 자기 쿠키를
 * 하나 만들었을 때 그것까지 백엔드로 새어 나간다.
 */
const FORWARDED_COOKIES = ["SHOPSESSION", "CART-TOKEN"];

/** 세션 쿠키의 이름. 있었는지로 「끊겼다」와 「처음부터 없었다」를 가른다 */
const SESSION_COOKIE = "SHOPSESSION";

/**
 * 로그인 화면으로 보낼 때 붙이는 표시. 그 화면이 이유를 말한다(`D20`).
 *
 * <p><b>둘로 가른다.</b> 로그인한 적 없는 사람에게 「만료되었습니다」라고 하면 사실이 아닌 것을
 * 말하는 것이고, 사용자는 자기가 뭘 잘못했다고 생각한다.
 */
const SESSION_EXPIRED = "/login?reason=session-expired";
const LOGIN_REQUIRED = "/login?reason=login-required";

/**
 * 세션을 실어서 부른다.
 *
 * <p><b>401 을 여기서 잡는다</b>(`D24` 「오류를 어느 층이 잡나」). 화면마다 잡으면
 * 한 화면이 빠뜨렸을 때 <b>그 화면만 조용히 빈 목록</b>이 된다.
 *
 * @param path `/api` 로 시작하는 경로
 * @throws ApiError 401 말고 2xx 가 아닌 것. 403·404 는 부르는 화면이 잡는다
 */
export async function apiSession<T>(path: string): Promise<T> {
  const response = await carry(path);

  if (response.status === 401) {
    // 던지지 않고 여기서 보낸다. 예외로 올리면 화면마다 같은 처리를 다시 적게 된다.
    redirect((await cookies()).get(SESSION_COOKIE) ? SESSION_EXPIRED : LOGIN_REQUIRED);
  }

  if (!response.ok) {
    throw await toApiError(response);
  }

  return toCamel(await response.json()) as T;
}

/**
 * 로그인해야 보는 것을 <b>안 보내고</b> 읽는다(`13b`).
 *
 * <p><b>셸의 머리 때문에 생긴 입구다.</b> 머리는 모든 화면에 있어서 {@link apiSession} 을 쓰면
 * 비로그인이 상품 목록만 봐도 로그인 화면으로 튕긴다 — 공개 화면이 공개가 아니게 된다.
 *
 * <p>「누구인지 모른다」와 「로그인해야 한다」는 다른 상태고, 이 함수는 앞엣것을 돌려준다.
 * <b>보낼지 말지는 부르는 화면이 정한다.</b>
 *
 * <p><b>세션 쿠키가 있는지로 판단하지 않는다.</b> 만료된 세션도 쿠키는 남아서,
 * 그렇게 하면 머리가 「로그아웃」을 그리는데 누르면 로그인으로 튕긴다 —
 * 누가 로그인했는지는 백엔드만 안다(`D24`).
 *
 * @returns 401 이면 {@code null}. 그 밖의 오류는 그대로 던진다
 */
export async function apiSessionOptional<T>(path: string): Promise<T | null> {
  const response = await carry(path);

  if (response.status === 401) {
    return null;
  }

  if (!response.ok) {
    throw await toApiError(response);
  }

  return toCamel(await response.json()) as T;
}

/**
 * 세션을 실어서 부르고 <b>응답을 그대로 돌려준다.</b>
 *
 * <p>401 을 어떻게 다루느냐만 입구마다 다르고 <b>운반은 하나여야 한다</b>(`D24`) —
 * 쿠키를 만지는 코드가 둘이 되면 목록·캐시·로그 실수가 각각 가능해진다.
 */
async function carry(path: string): Promise<Response> {
  const jar = await cookies();

  const carried = FORWARDED_COOKIES.map((name) => jar.get(name))
    .filter((cookie) => cookie !== undefined)
    .map((cookie) => `${cookie.name}=${cookie.value}`)
    .join("; ");

  return fetch(`${BACKEND_ORIGIN}${path}`, {
    // 쿠키가 하나도 없을 수 있다. 비로그인이 장바구니를 처음 여는 경우다.
    headers: carried ? { Cookie: carried } : {},

    // 사람마다 다른 응답이다. 캐시되면 남의 주문이 보인다(`D24` 「캐시」).
    // 기본값이 이미 캐시 안 함이지만 Next 문서 안에서 서술이 갈리는 자리라 명시한다.
    cache: "no-store",
  });
}
