import type { NextConfig } from "next";

/**
 * 백엔드 주소. 기본값은 로컬 기동 포트다(`application.yml` 의 `SERVER_PORT`).
 *
 * 브라우저가 아니라 Next 서버가 이 주소로 붙는다. 그래서 값이 바뀌어도 화면 코드는 안 바뀐다.
 */
const BACKEND = process.env.BACKEND_ORIGIN ?? "http://localhost:8080";

const nextConfig: NextConfig = {
  /**
   * 컨테이너에 실을 것만 추려 낸다(`Q38`).
   *
   * 이것이 없으면 이미지가 `node_modules` 를 통째로 져야 한다. 켜면 Next 가 실제로 쓰는 것만
   * 골라 `.next/standalone` 에 서버 진입점과 같이 넣는다 — `Dockerfile` 이 그것만 옮긴다.
   *
   * **로컬에는 영향이 없다.** `npm run dev`·`npm run build` 가 하던 일은 그대로고
   * 산출물이 하나 더 생길 뿐이다.
   */
  output: "standalone",

  /**
   * `forbidden()` 과 `app/forbidden.tsx` 를 켠다(`Q133`).
   *
   * **왜 경계에서 못 가르고 여기까지 오나는 `D24` 「오류 경계는 `app/error.tsx` 다」가 든다.**
   *
   * **`experimental` 인 것을 알고 켠다.** 대안은 화면 열일곱 곳이 각자 `try/catch` 로 403 을
   * 잡는 것인데, 하나가 빠뜨리면 그 화면만 다시 「실패했습니다」가 된다 — 기억에 맡기는
   * 자리를 하나 더 만드는 셈이다(`D23` 축 2). **끄면 `forbidden.test.tsx` 가 빨개진다** —
   * Next 를 올릴 때 이 플래그가 살아 있는지 그 시험이 대신 본다.
   */
  experimental: {
    authInterrupts: true,
  },

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

  /**
   * 상품 사진의 자리표시(`D20` 「화면마다 다시 정하지 않는 것」).
   *
   * 사진을 아직 안 올린 상품이 쓴다. 상품번호가 씨앗이라 같은 상품은 언제나 같은 사진이다.
   *
   * **진짜 사진은 이 목록을 안 탄다**(`28`). 서명 URL 이 만료 5분이라 최적화 캐시와 수명이
   * 어긋나서 `unoptimized` 로 그린다 — `next/image` 의 loader 를 안 지나므로
   * 저장소 주소를 여기 등록할 일이 없다.
   *
   * 목록을 안 쓰면 `next/image` 가 남의 주소를 통째로 거부한다. 아무 주소나 받으면
   * 우리 서버가 남의 이미지를 대신 내려받아 주는 통로가 된다.
   */
  images: {
    remotePatterns: [
      {
        protocol: "https",
        hostname: "picsum.photos",
        pathname: "/seed/**",
      },
    ],
  },
};

export default nextConfig;
