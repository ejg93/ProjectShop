// 목록 입구 여섯의 p95(`70`). `scripts/load-test.sh` 가 컴포즈의 k6 로 세 번 부르고 중앙값을 기준선과 견준다.
//
// 키 이름이 `load/baseline.json` 과 `performance-goals.md` 「기준선」 표의 키다 — 셋이 같은 이름을 쓴다.
// **세션은 setup 에서 한 번 만든다.** k6 의 쿠키 통은 VU 하나에 하나라 세 역할을 같이 못 든다 —
// 역할마다 로그인해 세션 값만 넘기고, 요청마다 그 값을 쿠키로 싣는다.
import http from "k6/http";
import { check } from "k6";
import { Trend } from "k6/metrics";

const BASE = __ENV.BASE_URL || "http://backend:8080";
const PASSWORD = "demo-password-1234";

// [키, 경로, 누구로]. 누구 = null 이면 로그인 없이 부른다.
const ENDPOINTS = [
  ["products", "/api/products?size=20", null],
  ["search", "/api/products?q=%EC%9A%B4%EB%8F%99%ED%99%94&size=20", null], // q=운동화
  ["my_orders", "/api/orders?size=20", "customer"],
  ["seller_orders", "/api/seller/orders?size=20", "seller"],
  ["admin_orders", "/api/admin/orders?size=20", "admin"],
  ["settlements", "/api/settlements?size=20", "admin"],
];

const ACCOUNTS = {
  customer: "customer@example.com",
  seller: "fashion-owner@example.com",
  admin: "admin@example.com",
};

const trends = Object.fromEntries(ENDPOINTS.map(([key]) => [key, new Trend(`t_${key}`, true)]));

export const options = {
  // **10 이다**(20 에서 내렸다). 20 이면 0.3ms 질의의 p95 가 100ms 를 넘어 질의가 아니라 컨테이너의 줄 서기를 재고,
  // 회차마다 30% 넘게 흔들렸다(2026-10-02 실측 — `performance-goals.md` 「회귀 문턱」).
  vus: Number(__ENV.VUS || 10),
  duration: __ENV.DURATION || "1m",
  summaryTrendStats: ["med", "p(95)", "count"],
  // 하나라도 200 이 아니면 그 회차는 잰 것이 아니다 — 429·401 이 섞이면 p95 가 오류 응답의 속도가 된다.
  thresholds: { checks: ["rate==1"] },
};

function login(email) {
  const jar = new http.CookieJar();
  http.get(`${BASE}/api/auth/session`, { jar });
  const token = jar.cookiesForURL(BASE)["XSRF-TOKEN"][0];
  const res = http.post(`${BASE}/api/auth/login`, JSON.stringify({ email, password: PASSWORD }), {
    jar,
    headers: { "Content-Type": "application/json", "X-XSRF-TOKEN": token },
  });
  if (res.status !== 200) {
    throw new Error(`로그인 실패 ${email}: ${res.status}`);
  }
  return jar.cookiesForURL(BASE).SHOPSESSION[0];
}

export function setup() {
  return Object.fromEntries(Object.entries(ACCOUNTS).map(([role, email]) => [role, login(email)]));
}

export default function (sessions) {
  for (const [key, path, role] of ENDPOINTS) {
    const params = role ? { cookies: { SHOPSESSION: { value: sessions[role], replace: true } } } : {};
    const res = http.get(`${BASE}${path}`, params);
    check(res, { [`${key} 200`]: (r) => r.status === 200 });
    trends[key].add(res.timings.duration);
  }
}

export function handleSummary(data) {
  const p95 = Object.fromEntries(
    ENDPOINTS.map(([key]) => [key, Math.round(data.metrics[`t_${key}`].values["p(95)"] * 10) / 10]),
  );
  const lines = Object.entries(p95).map(([key, value]) => `  ${key.padEnd(14)} p95 ${value} ms`);
  return {
    [`/load/out/run-${__ENV.RUN || "0"}.json`]: JSON.stringify(p95, null, 2) + "\n",
    stdout: `회차 ${__ENV.RUN || "0"}\n${lines.join("\n")}\n`,
  };
}
