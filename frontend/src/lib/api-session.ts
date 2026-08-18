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

/** 세션이 끊겨서 로그인 화면으로 보낼 때 붙이는 표시. 그 화면이 이유를 말한다(`D20`) */
const SESSION_EXPIRED = "/login?reason=session-expired";

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
  const jar = await cookies();

  const carried = FORWARDED_COOKIES.map((name) => jar.get(name))
    .filter((cookie) => cookie !== undefined)
    .map((cookie) => `${cookie.name}=${cookie.value}`)
    .join("; ");

  const response = await fetch(`${BACKEND_ORIGIN}${path}`, {
    // 쿠키가 하나도 없을 수 있다. 비로그인이 장바구니를 처음 여는 경우다.
    headers: carried ? { Cookie: carried } : {},

    // 사람마다 다른 응답이다. 캐시되면 남의 주문이 보인다(`D24` 「캐시」).
    // 기본값이 이미 캐시 안 함이지만 Next 문서 안에서 서술이 갈리는 자리라 명시한다.
    cache: "no-store",
  });

  if (response.status === 401) {
    // 던지지 않고 여기서 보낸다. 예외로 올리면 화면마다 같은 처리를 다시 적게 된다.
    redirect(SESSION_EXPIRED);
  }

  if (!response.ok) {
    throw await toApiError(response);
  }

  return toCamel(await response.json()) as T;
}
