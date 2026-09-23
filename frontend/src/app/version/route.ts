/**
 * 이 판을 만든 커밋(`Q205`). Railway 가 배포마다 넣는 `RAILWAY_GIT_COMMIT_SHA` 고, 없으면(로컬) `unknown` 이다.
 *
 * <p>`scripts/deploy-check.sh` 가 머지한 커밋과 견준다 — 백엔드는 `/api/health` 의 `commit` 이 같은 일을 한다.
 * <b>요청 때 읽는다</b> — 빌드 때 굳히면 변수를 바꿔도 앞 값이 나간다.
 */
export const dynamic = "force-dynamic";

export function GET(): Response {
  return Response.json({ app: "shop-frontend", commit: process.env.RAILWAY_GIT_COMMIT_SHA ?? "unknown" });
}
