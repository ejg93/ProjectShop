/**
 * 굳은 프록시 목적지가 준 값과 같은지 잰다(`Q116` 의 강제 지점).
 *
 * `next.config.ts` 의 `rewrites()` 목적지를 Next 가 **빌드 산출물에 굳힌다.**
 * `standalone` 은 설정 파일을 안 지고 그 산출물만 지므로, 호스팅에서 환경변수로 줘도 안 바뀐다.
 * 그래서 이미지를 만드는 자리에서 한 번 확인하고, 아니면 **빌드를 세운다.**
 *
 * `frontend/Dockerfile` 이 `npm run build` 직후에 부른다. 로컬 `npm run build` 는 안 부른다 —
 * 거기는 기본값(`http://localhost:8080`)이 맞는 답이다.
 *
 * <b>재는 것이 넷이다.</b> 「인자를 줬나」 하나만 보면 `http://localhost:8080` 을 그대로 줘도
 * 통과해서 막으려던 결함이 그대로 재현된다(마무리 33차 독립 리뷰).
 *
 *   1. 값이 있나
 *   2. 절대 http(s) 주소인가
 *   3. 호스트가 루프백이 아닌가 — 컨테이너 안의 루프백은 자기 자신이라 백엔드가 없다
 *   4. `/api/:path*` 규칙의 목적지가 그 값으로 시작하나 — 규칙을 `source` 로 찾는다
 *
 * <b>산출물은 실려 나가는 사본을 읽는다.</b> 빌드 폴더의 것과 `standalone` 아래 사본이
 * 같은 빌드에서 나오지만, 런타임이 쓰는 것은 후자다.
 */
import { readFileSync } from "node:fs";

const MANIFEST = ".next/standalone/.next/routes-manifest.json";
const API_SOURCE = "/api/:path*";
const LOOPBACK = new Set(["localhost", "127.0.0.1", "::1", "0.0.0.0", "[::1]"]);

function die(message) {
  console.error(`[Q116] ${message}`);
  process.exit(1);
}

const want = (process.env.BACKEND_ORIGIN ?? "").trim();
if (!want) {
  die("BACKEND_ORIGIN 을 빌드 인자로 안 줬다."
    + " docker build --build-arg BACKEND_ORIGIN=http://<백엔드>:8080 ./frontend");
}

let origin;
try {
  origin = new URL(want);
} catch {
  die(`BACKEND_ORIGIN 이 주소가 아니다: ${want}`);
}
if (origin.protocol !== "http:" && origin.protocol !== "https:") {
  die(`BACKEND_ORIGIN 의 스킴이 http 나 https 가 아니다: ${want}`);
}
if (LOOPBACK.has(origin.hostname)) {
  die(`BACKEND_ORIGIN 이 루프백이다: ${want}.`
    + " 컨테이너 안의 루프백은 자기 자신이라 백엔드가 없다 — 화면의 조작이 전부 500 이 된다");
}

let manifest;
try {
  manifest = JSON.parse(readFileSync(MANIFEST, "utf8"));
} catch (error) {
  die(`산출물을 못 읽었다: ${MANIFEST} (${error.message})`);
}

const rules = [
  ...(manifest.rewrites?.beforeFiles ?? []),
  ...(manifest.rewrites?.afterFiles ?? []),
  ...(manifest.rewrites?.fallback ?? []),
];
const api = rules.filter((rule) => rule.source === API_SOURCE);
if (api.length !== 1) {
  die(`${API_SOURCE} 규칙이 ${api.length} 개다. 하나여야 한다`
    + " — 여럿이면 어느 것이 이기는지 이 검사가 답을 못 한다");
}

const destination = api[0].destination;
if (!destination.startsWith(`${want.replace(/\/$/, "")}/api`)) {
  die(`굳은 목적지가 준 값과 다르다. 준 값: ${want} / 산출물: ${destination}`);
}

console.log(`[Q116] 프록시 목적지가 굳었다: ${destination}`);
