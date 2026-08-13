import type { NextConfig } from "next";

/**
 * 백엔드 주소. 기본값은 로컬 기동 포트다(`application.yml` 의 `SERVER_PORT`).
 *
 * 브라우저가 아니라 Next 서버가 이 주소로 붙는다. 그래서 값이 바뀌어도 화면 코드는 안 바뀐다.
 */
const BACKEND = process.env.BACKEND_ORIGIN ?? "http://localhost:8080";

const nextConfig: NextConfig = {
  /**
   * 이 폴더가 프로젝트 뿌리다.
   *
   * 안 적으면 Turbopack 이 상위 폴더까지 훑다가 저장소 밖의 `package-lock.json` 을 집어
   * 경고를 낸다. 남의 파일이 우리 빌드에 영향을 주는 자리라 못 박는다.
   */
  turbopack: {
    root: import.meta.dirname,
  },

  /**
   * 브라우저에게는 전부 같은 출처로 보이게 한다.
   *
   * 화면은 3000, 백엔드는 8080 이라 브라우저가 교차 출처로 보고 막는다. CORS 를 여는 대신
   * 프록시로 출처를 하나로 만든다 — CORS 를 열면 세션 쿠키를 위해 `credentials` 와
   * `Access-Control-Allow-Origin` 을 정확히 맞춰야 하고, 한 곳만 틀려도 로그인이 조용히 안 된다.
   *
   * 배포에서도 같은 모양을 쓴다. 화면과 API 가 한 도메인이면 쿠키의 `same-site: lax` 가
   * 그대로 성립한다(`application.yml`).
   */
  async rewrites() {
    return [
      {
        source: "/api/:path*",
        destination: `${BACKEND}/api/:path*`,
      },
    ];
  },
};

export default nextConfig;
