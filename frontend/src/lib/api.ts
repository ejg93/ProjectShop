/**
 * 서버를 부르는 유일한 통로.
 *
 * <p>여기를 안 거치는 `fetch` 를 쓰지 않는다(`D5`). 표기 변환과 CSRF 헤더가 여기에만 있어서,
 * 직접 부르면 어떤 응답은 바뀌고 어떤 것은 안 바뀐 채로 화면에 닿는다.
 *
 * <p>입구가 넷이다(`D24` 「서버를 부르는 입구」). <b>도는 곳과 누구의 것이냐로 갈린다.</b>
 *
 * <pre>
 * api()        클라이언트 컴포넌트 · 상대경로 · 쿠키를 브라우저가 붙인다 · CSRF 를 싣는다 · JSON
 * apiUpload()  클라이언트 컴포넌트 · 상대경로 · 〃                        · 〃              · 파일 하나
 * apiPublic()  서버 컴포넌트       · 절대주소 · 쿠키 없음 · 손님 주소를 싣는다 · 읽기 전용
 * apiSession() 서버 컴포넌트       · 절대주소 · 쿠키를 손으로 싣는다 · 〃    · 읽기 전용
 * </pre>
 *
 * <p>{@link api} 는 쿠키를 `document.cookie` 로 읽어서 <b>브라우저에서만 돈다.</b>
 * 서버 컴포넌트에서 부르면 그 자리에서 터진다.
 *
 * <p><b>서버 입구 둘은 파일이 다르다</b>(`api-session.ts`). `next/headers` 로 쿠키와 손님 주소(`Q240`)를 읽는데,
 * 그것을 여기 들이면 이 파일을 가져다 쓰는 <b>클라이언트 컴포넌트가 전부 빌드에서 깨진다.</b>
 * 입구들이 같은 변환·같은 오류 처리를 쓰도록 아래 것들을 내보낸다.
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

/**
 * 서버가 지목한 칸 하나(`Q129`). {@code errors} 배열의 원소다.
 *
 * <p><b>{@code field} 는 요청에 쓴 이름이다</b> — 본문 칸이면 snake_case 고 중첩이면
 * 점 표기({@code shipping.postal_code})이며, 헤더면 헤더 이름 그대로다(`D5`).
 * 화면이 이 이름으로 자기 칸을 찾으므로 <b>여기서 표기를 바꾸지 않는다.</b>
 */
export type FieldError = { field: string; message: string };

/**
 * 서버가 {@code message} 를 안 실었을 때 그리는 문구. 서버의 `ErrorCode.FALLBACK_USER_TEXT` 와 같은 값이다(`Q233`).
 *
 * <p>우리 서버는 늘 싣는다. 비는 것은 프록시나 다른 서버가 낸 오류 본문뿐이다.
 */
export const FALLBACK_USER_TEXT = "요청을 처리하지 못했습니다. 잠시 뒤 다시 시도해 주세요.";

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
    /**
     * 어느 칸이 왜 틀렸나. <b>검증 실패가 아니면 빈 배열이다</b>(`Q129`).
     *
     * <p>선택 값으로 두지 않는다 — 부르는 쪽마다 {@code ?? []} 를 붙이게 되고,
     * 한 화면이 그것을 빠뜨리는 날 그 화면만 터진다.
     */
    readonly errors: FieldError[] = [],
    /**
     * 사용자가 읽는 문구 — 응답의 {@code message}(`Q233`). <b>화면은 {@code detail} 대신 이것을 그린다</b>(`D20`).
     *
     * <p>{@code detail} 은 개발자용 평서형이라 내부 값(소문자 상태·Spring 영어)이 섞인다.
     * 분기는 여전히 {@link slug} 로 한다 — 이것도 문구라 다듬으면 바뀐다.
     */
    readonly userText: string = FALLBACK_USER_TEXT,
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
  init: { method?: string; body?: Json; idempotencyKey?: string; contentType?: string } = {},
): Promise<T> {
  const method = init.method ?? "GET";
  const headers: Record<string, string> = {};

  if (init.body !== undefined) {
    // `PATCH` 는 「바꿀 것의 목록」이라 형식을 미디어 타입으로 밝혀야 한다(RFC 5789, `Q11`).
    // 부르는 쪽이 안 정하면 부분 갱신이 아니라 통째로 보내는 것으로 본다.
    headers["Content-Type"] =
      init.contentType ?? (method === "PATCH" ? "application/merge-patch+json" : "application/json");
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

  return readResponse<T>(path, response);
}

/**
 * 응답을 읽는 규칙. <b>두 입구가 이 함수 하나를 쓴다</b>(`Q157`).
 *
 * <p><b>요청 쪽은 갈라야 하는 것이 맞다</b> — {@link api} 는 `JSON.stringify` 로 굳히고
 * {@link apiUpload} 는 `FormData` 를 그대로 싣는다. 그러나 <b>응답 쪽은 그 차이와 무관하게</b>
 * 401·204·오류·표기 변환이 똑같고, 두 벌로 두면 한쪽만 고치는 날이 온다 —
 * 마무리 41차 독립 리뷰가 실제로 빠진 204 분기를 짚었다.
 *
 * <p><b>강제 지점이 테스트에서 구조로 내려왔다.</b> 전에는 두 벌이 같은지를 원문 대조가
 * 글자로만 쟀는데(`Q155`), 그 시험은 <b>양쪽에 같은 문자열을 넣기만 하면 통과하고 행동이
 * 갈리는 것은 못 본다</b>(자기 한계로 적어 뒀다). 한 곳으로 모으면 <b>복사할 자리가 없어진다.</b>
 *
 * <p><b>401 에서 경로를 보는 조건을 그대로 들고 왔다.</b> 로그인·가입에서 나는 401 은
 * 「세션이 없다」가 아니라 「이번 시도가 틀렸다」라 보내 봐야 같은 화면이고, 대신 그 폼이
 * 어느 칸도 지목하지 않는 오류로 그린다(`D20`). 업로드 경로는 `/api/auth/` 에 닿을 일이
 * 없으므로 <b>이 조건이 있어도 그쪽 행동은 안 바뀐다</b> — 조건을 뺀 판을 따로 두는 것보다
 * 한 벌이 낫다.
 *
 * <p>서버 컴포넌트 쪽은 `api-session.ts` 가 같은 일을 한다. <b>층이 둘이라 두 군데인 것이지
 * 규칙이 둘인 것이 아니다.</b>
 */
async function readResponse<T>(path: string, response: Response): Promise<T> {
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
 * 파일 하나를 올린다(`Q140`).
 *
 * <p><b>{@link api} 로는 못 보낸다.</b> 그쪽은 본문을 `JSON.stringify` 로 굳혀서 파일이 `{}` 가 된다.
 * 여기는 `FormData` 를 그대로 싣고 <b>`Content-Type` 을 안 정한다</b> — 정하면 브라우저가 붙이는
 * 멀티파트 경계(boundary)가 빠져서 서버가 본문을 못 가른다.
 *
 * <p>응답은 {@link readResponse} 한 벌을 쓴다(`Q157`). 전에는 두 벌이었고 한쪽만 고치는
 * 날이 실제로 왔다 — 마무리 41차 독립 리뷰가 빠진 204 분기를 짚었다.
 *
 * @param field 서버가 받는 파트 이름. 상품 사진은 `file` 이다(`SellerProductController`)
 */
export async function apiUpload<T>(path: string, file: File, field = "file"): Promise<T> {
  const body = new FormData();
  body.append(field, file);

  const response = await fetch(path, {
    method: "POST",
    headers: { [CSRF_HEADER]: await csrfToken() },
    credentials: "same-origin",
    body,
  });

  return readResponse<T>(path, response);
}

/**
 * 백엔드 주소. `next.config.ts` 의 rewrite 가 쓰는 것과 같은 값에서 온다.
 *
 * <p>서버 컴포넌트는 프록시를 안 지난다. 브라우저가 아니라 Next 서버가 부르는 것이라
 * 상대경로에 붙일 출처가 없어서 절대 주소가 필요하다.
 */
export const BACKEND_ORIGIN = process.env.BACKEND_ORIGIN ?? "http://localhost:8080";

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
      message?: unknown;
      trace_id?: string;
      errors?: { field?: unknown; message?: unknown }[];
    };

    return new ApiError(
      response.status,
      body.type ?? "about:blank",
      body.detail ?? "요청을 처리하지 못했습니다.",
      body.trace_id,
      fieldErrorsOf(body.errors),
      // 문자열일 때만 믿는다 — 서버가 보낸 JSON 이라 타입 선언이 보장하지 않는다.
      typeof body.message === "string" && body.message ? body.message : FALLBACK_USER_TEXT,
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
 * {@code errors} 를 걸러서 담는다.
 *
 * <p><b>모양을 여기서 한 번만 믿는다.</b> 이 값은 서버가 보낸 JSON 이라 타입 선언이
 * 보장해 주지 않는다 — 걸러 두지 않으면 {@code undefined} 인 {@code field} 가
 * 화면까지 가서 <b>아무 칸에도 안 붙는 문구</b>가 된다.
 */
function fieldErrorsOf(raw: { field?: unknown; message?: unknown }[] | undefined): FieldError[] {
  if (!Array.isArray(raw)) {
    return [];
  }
  return raw
    .filter((entry) => typeof entry?.field === "string" && typeof entry?.message === "string")
    .map((entry) => ({ field: entry.field as string, message: entry.message as string }));
}

/**
 * 키만 바꾼다. <b>값은 손대지 않는다.</b>
 *
 * <p>`allowed_actions` 의 `REQUEST_RETURN` 같은 값이 열거값이라 그렇다(`D5`).
 * 값까지 바꾸면 화면이 서버가 모르는 이름으로 동작을 부른다.
 *
 * @param opaque <b>안을 안 들여다볼 칸의 이름</b>(`Q135`). 서버가 쓴 이름 그대로 적는다 —
 *     이 판정은 바꾸기 전에 한다. 그 칸의 <b>값 전체가 그대로</b> 지나가고, 칸 이름 자체는
 *     다른 칸과 똑같이 바뀐다.
 *
 *     <p><b>임의 JSON 을 드는 칸이 여기 온다.</b> 그런 칸의 열쇠는 <b>우리 응답 계약이 아니라
 *     데이터</b>다 — `audit_log.detail` 에 `item_code` 로 적혔으면 감사 기록이 말하는 것은
 *     그 글자고, 화면이 `itemCode` 로 보여 주면 <b>적힌 적 없는 것을 적혔다고 말한다</b>(`D16`).
 */
export function toCamel(value: Json, opaque: readonly string[] = []): Json {
  return mapKeys(value, camelCase, new Set(opaque));
}

/**
 * 밑줄 표기를 낙타 표기로. <b>키 하나를 옮기는 규칙이 여기 하나다</b>.
 *
 * <p>{@link toCamel} 이 응답 전체에 쓰고 {@code lib/field-errors} 가 서버가 지목한 칸 이름에
 * 쓴다 — 같은 정규식을 두 벌 두면 한쪽만 고치는 날이 온다(`Q129` 독립 리뷰가 그 사본을 짚었다).
 */
export function camelCase(key: string): string {
  return key.replace(/_([a-z0-9])/g, (_, char: string) => char.toUpperCase());
}

function toSnake(value: Json): Json {
  return mapKeys(value, (key) => key.replace(/[A-Z]/g, (char) => `_${char.toLowerCase()}`));
}

function mapKeys(
  value: Json,
  rename: (key: string) => string,
  opaque: ReadonlySet<string> = EMPTY_OPAQUE,
): Json {
  if (Array.isArray(value)) {
    return value.map((item) => mapKeys(item, rename, opaque));
  }

  // null 도 object 다. 걸러내지 않으면 Object.entries 가 터진다.
  if (value === null || typeof value !== "object") {
    return value;
  }

  return Object.fromEntries(
    Object.entries(value as Record<string, Json>).map(([key, item]) => [
      rename(key),
      // 이름으로만 판정한다. 「값이 임의 JSON 처럼 생겼나」로 고르면 같은 칸이
      // 내용에 따라 다르게 나와서, 화면이 그 둘을 다 다뤄야 한다.
      opaque.has(key) ? item : mapKeys(item, rename, opaque),
    ]),
  );
}

/** 안 들여다볼 칸이 하나도 없을 때. 부를 때마다 `new Set()` 을 만들지 않는다 */
const EMPTY_OPAQUE: ReadonlySet<string> = new Set();
