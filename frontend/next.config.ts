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

  /**
   * 상품 사진의 자리표시(`D20` 「화면마다 다시 정하지 않는 것」).
   *
   * 스키마에 이미지 컬럼이 없어서 상품번호를 씨앗으로 받아 온다. 같은 상품은 언제나 같은 사진이다.
   * 진짜 업로드는 청크 26 이고 그때 이 항목이 우리 저장소 주소로 바뀐다.
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
