/**
 * 서버를 부르는 유일한 통로.
 *
 * <p>여기를 안 거치는 `fetch` 를 쓰지 않는다(`D5`). 표기 변환과 CSRF 헤더가 여기에만 있어서,
 * 직접 부르면 어떤 응답은 바뀌고 어떤 것은 안 바뀐 채로 화면에 닿는다.
 *
 * <p>입구가 셋이다(`D24` 「서버를 부르는 입구가 둘이다」에서 하나 늘었다).
 * <b>도는 곳과 누구의 것이냐로 갈린다.</b>
 *
 * <pre>
 * api()        클라이언트 컴포넌트 · 상대경로 · 쿠키를 브라우저가 붙인다 · CSRF 를 싣는다
 * apiPublic()  서버 컴포넌트       · 절대주소 · 쿠키 없음               · 읽기 전용
 * apiSession() 서버 컴포넌트       · 절대주소 · 쿠키를 손으로 싣는다     · 읽기 전용
 * </pre>
 *
 * <p>{@link api} 는 쿠키를 `document.cookie` 로 읽어서 <b>브라우저에서만 돈다.</b>
 * 서버 컴포넌트에서 부르면 그 자리에서 터진다.
 *
 * <p><b>{@link apiSession} 만 파일이 다르다</b>(`api-session.ts`). `next/headers` 를 쓰는데,
 * 그것을 여기 들이면 이 파일을 가져다 쓰는 <b>클라이언트 컴포넌트가 전부 빌드에서 깨진다.</b>
 * 세 입구가 같은 변환·같은 오류 처리를 쓰도록 아래 셋을 내보낸다.
 */

/**
 * 우리 오류 `type` 의 접두어. 서버의 `ErrorCode.TAG_PREFIX` 와 같은 값이다.
 *
 * <p>`tag:` URI 다(RFC 4151). 예전에는 `urn:shop:error:` 였는데 RFC 8141 이 요구하는
 * 네임스페이스 등록이 없어서 문법만 맞는 이름이었다(`Q1`).
 *
 * <p><b>바뀌는 자리가 여기 하나다.</b> 화면들은 접두어를 모르고 슬러그만 본다 —
 * 그러지 않으면 접두어를 바꿀 때 화면 아홉을 같이 고쳐야 하고, 하나를 빠뜨리면
 * 그 화면만 조용히 기본 문구로 떨어진다.
 */
const ERROR_TYPE_PREFIX = "tag:projectshop.example,2026:error:";

export class ApiError extends Error {
  /**
   * 접두어를 뗀 오류 이름. <b>화면은 이것으로 분기한다</b>(`D5`·`D20`).
   *
   * <p>우리가 낸 오류가 아니면 빈 문자열이다 — 프록시나 다른 서버가 낸 `problem+json` 이
   * 우연히 우리 이름과 겹치는 일이 없다.
   */
  readonly slug: string;

  constructor(
    readonly status: number,
    readonly type: string,
    readonly detail: string,
    readonly traceId?: string,
  ) {
    super(detail);
    this.name = "ApiError";
    this.slug = type.startsWith(ERROR_TYPE_PREFIX)
      ? type.slice(ERROR_TYPE_PREFIX.length)
      : "";
  }
}

/** CSRF 토큰이 담겨 오는 쿠키와 그것을 돌려보낼 헤더. 이름은 Spring Security 기본값이다 */
const CSRF_COOKIE = "XSRF-TOKEN";
const CSRF_HEADER = "X-XSRF-TOKEN";

/** 토큰이 없을 때 한 번 두드려서 쿠키를 받아 오는 곳. 인증이 필요 없는 경로여야 한다 */
const CSRF_PRIMER = "/api/health";

/** 이 메서드들은 서버 상태를 안 바꾼다. CSRF 토큰이 필요 없다 */
const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);

type Json = unknown;

/**
 * 서버를 부르고 응답을 화면이 쓰는 모양으로 돌려준다.
 *
 * @param path `/api` 로 시작하는 경로. 포트를 적지 않는다 - 프록시가 같은 출처로 넘긴다
 * @param init.idempotencyKey 돈이나 재고가 움직이는 POST 에 필수다(`D11`). 만드는 쪽은 화면이고,
 *                            <b>재시도에도 같은 값을 보내야 한다</b> — 새로 만들면 서버가
 *                            재전송이 아니라 새 요청으로 보고 주문을 하나 더 만든다
 * @throws ApiError 서버가 2xx 가 아닌 것을 줬을 때
 */
export async function api<T>(
  path: string,
  init: { method?: string; body?: Json; idempotencyKey?: string } = {},
): Promise<T> {
  const method = init.method ?? "GET";
  const headers: Record<string, string> = {};

  if (init.body !== undefined) {
    headers["Content-Type"] = "application/json";
  }

  if (init.idempotencyKey !== undefined) {
    headers["Idempotency-Key"] = init.idempotencyKey;
  }

  if (!SAFE_METHODS.has(method)) {
    headers[CSRF_HEADER] = await csrfToken();
  }

  const response = await fetch(path, {
    method,
    headers,
    // 세션 쿠키를 싣는다. 같은 출처라 기본값도 같지만, 프록시를 걷어내는 날
    // 이 줄이 없으면 로그인만 조용히 안 된다.
    credentials: "same-origin",
    body: init.body === undefined ? undefined : JSON.stringify(toSnake(init.body)),
  });

  // 세션이 끊겼다. 화면마다 문구를 만들지 않고 로그인으로 보낸다(`D20` 「401 은 조용히 보내지 않는다」).
  //
  // 로그인·가입 경로는 뺀다. 거기서 나는 401 은 「세션이 없다」가 아니라 「이번 시도가 틀렸다」라
  // 보내 봐야 같은 화면이고, 대신 그 폼이 어느 칸도 지목하지 않는 오류로 그린다(`D20`).
  //
  // 서버 컴포넌트 쪽은 `api-session.ts` 가 같은 일을 한다. 층이 둘이라 두 군데인 것이지
  // 규칙이 둘인 것이 아니다.
  if (response.status === 401 && !path.startsWith("/api/auth/")) {
    window.location.replace("/login?reason=session-expired");

    // 이동이 시작돼도 이 함수는 계속 돈다. 여기서 안 끊으면 부르는 쪽이 오류 문구를 띄우고,
    // 사용자는 로그인 화면으로 넘어가기 직전에 그것을 본다.
    await new Promise(() => {});
  }

  if (!response.ok) {
    throw await toApiError(response);
  }

  // 204 는 본문이 없다. 파싱하면 그 자리에서 터진다.
  if (response.status === 204) {
    return undefined as T;
  }

  return toCamel(await response.json()) as T;
}

/**
 * 백엔드 주소. `next.config.ts` 의 rewrite 가 쓰는 것과 같은 값에서 온다.
 *
 * <p>서버 컴포넌트는 프록시를 안 지난다. 브라우저가 아니라 Next 서버가 부르는 것이라
 * 상대경로에 붙일 출처가 없어서 절대 주소가 필요하다.
 */
export const BACKEND_ORIGIN = process.env.BACKEND_ORIGIN ?? "http://localhost:8080";

/**
 * 로그인 없이 볼 수 있는 것을 서버 컴포넌트에서 읽는다.
 *
 * <p><b>쿠키를 안 싣는다.</b> 공개 데이터는 누구에게나 같으므로 실을 이유가 없고,
 * 안 실으면 <b>사람마다 다른 응답이 섞일 수 없다</b> — 캐시를 켜는 날 그 위험이 안 생긴다(`D24`).
 * 로그인해야 보는 것은 세션을 손으로 실어야 해서 입구를 또 하나 낸다.
 *
 * <p><b>CSRF 도 없다.</b> 읽기만 하는 입구라 서버가 토큰을 안 본다.
 *
 * @param path `/api` 로 시작하는 경로
 * @throws ApiError 서버가 2xx 가 아닌 것을 줬을 때
 */
export async function apiPublic<T>(path: string): Promise<T> {
  // 명시한다. Next 문서 안에서도 기본값 서술이 갈리는 자리라 기대지 않는다(`D24` 「캐시」).
  // 켜려면 여기가 아니라 부르는 라우트에서 정한다 — 상품이 언제 바뀌는지는 화면이 안다.
  const response = await fetch(`${BACKEND_ORIGIN}${path}`, { cache: "no-store" });

  if (!response.ok) {
    throw await toApiError(response);
  }

  return toCamel(await response.json()) as T;
}

/**
 * 쿠키에 든 CSRF 토큰. 없으면 한 번 두드려서 받아 온다.
 *
 * <p>서버는 <b>토큰을 읽을 때</b> 쿠키를 심는다. 화면만 띄우고 바로 로그인을 누르면
 * 아직 아무 요청도 안 나가서 쿠키가 없다. 그 자리에서 403 이 되므로 여기서 한 번 채운다.
 *
 * <p>쿠키 값을 그대로 헤더에 싣는다. 서버가 헤더로 온 값은 평문으로 비교하도록 맞춰 뒀다
 * (`SecurityConfig`). 풀거나 다시 인코딩하지 않는다.
 */
async function csrfToken(): Promise<string> {
  const existing = readCookie(CSRF_COOKIE);
  if (existing) {
    return existing;
  }

  await fetch(CSRF_PRIMER, { credentials: "same-origin" });

  const issued = readCookie(CSRF_COOKIE);
  if (!issued) {
    // 여기까지 오면 서버 설정이 바뀐 것이다. 403 을 받고 원인을 찾는 것보다 먼저 말하는 편이 낫다.
    throw new Error("CSRF 토큰을 못 받았다. 백엔드가 XSRF-TOKEN 쿠키를 안 내려준다");
  }
  return issued;
}

function readCookie(name: string): string | null {
  const found = document.cookie
    .split("; ")
    .find((pair) => pair.startsWith(`${name}=`));

  return found ? decodeURIComponent(found.slice(name.length + 1)) : null;
}

/**
 * 오류 응답을 예외로 바꾼다.
 *
 * <p>본문이 `problem+json` 이 아닐 수도 있다. 프록시가 못 붙었거나 서버가 죽으면
 * HTML 이 오는데, 그때 파싱을 믿으면 진짜 원인 대신 파싱 오류가 보인다.
 */
export async function toApiError(response: Response): Promise<ApiError> {
  try {
    const body = (await response.json()) as {
      type?: string;
      detail?: string;
      trace_id?: string;
    };

    return new ApiError(
      response.status,
      body.type ?? "about:blank",
      body.detail ?? "요청을 처리하지 못했습니다.",
      body.trace_id,
    );
  } catch {
    return new ApiError(
      response.status,
      "about:blank",
      `서버가 ${response.status} 로 답했습니다.`,
    );
  }
}

/**
 * 키만 바꾼다. <b>값은 손대지 않는다.</b>
 *
 * <p>`allowed_actions` 의 `REQUEST_RETURN` 같은 값이 열거값이라 그렇다(`D5`).
 * 값까지 바꾸면 화면이 서버가 모르는 이름으로 동작을 부른다.
 */
export function toCamel(value: Json): Json {
  return mapKeys(value, (key) =>
    key.replace(/_([a-z0-9])/g, (_, char: string) => char.toUpperCase()),
  );
}

function toSnake(value: Json): Json {
  return mapKeys(value, (key) => key.replace(/[A-Z]/g, (char) => `_${char.toLowerCase()}`));
}

function mapKeys(value: Json, rename: (key: string) => string): Json {
  if (Array.isArray(value)) {
    return value.map((item) => mapKeys(item, rename));
  }

  // null 도 object 다. 걸러내지 않으면 Object.entries 가 터진다.
  if (value === null || typeof value !== "object") {
    return value;
  }

  return Object.fromEntries(
    Object.entries(value as Record<string, Json>).map(([key, item]) => [
      rename(key),
      mapKeys(item, rename),
    ]),
  );
}
