/**
 * 로그인 화면에 공개하는 연습용 계정(`Q131`).
 *
 * <p><b>값의 진실은 시드다</b>(`db/seed/V904__demo_accounts.sql`, `Q130`). 여기 적은 것이
 * 그쪽과 갈리면 안내를 보고 친 사람이 로그인에 실패하는데, <b>화면은 「비밀번호가 틀렸다」고만
 * 말하므로</b> 안내가 낡았다는 것을 아무도 모른다. 그래서 {@code demo-accounts.test.ts} 가
 * 시드 파일을 읽어 두 목록을 맞춘다.
 *
 * <p><b>운영 빌드에서도 나간다.</b> 이 저장소의 배포는 연습용이고 <b>공개하는 것이 목적</b>이라,
 * {@code NODE_ENV} 로 접지 않는다 — {@code login-form.tsx} 의 개발 전용 자동 채움과 갈리는 자리다.
 */

/** 아홉이 같은 값을 쓴다. 안내가 한 줄로 끝나고 앱 규칙(`D14` 15자)도 지킨다 */
export const DEMO_PASSWORD = "demo-password-1234";

export type DemoAccount = {
  /** 로그인 칸에 넣는 값 */
  email: string;
  /** 화면에 뜨는 이름. 시드의 `display_name` 과 같다 */
  name: string;
};

export type DemoGroup = {
  /** 사용자 그룹 이름 */
  title: string;
  /** 그 그룹으로 들어가면 무엇을 볼 수 있나 */
  note: string;
  accounts: DemoAccount[];
};

/**
 * 그룹마다 셋이다. <b>셋인 이유는 세션이다</b> — 계정 하나를 두 사람이 같이 쓰면
 * 뒤에 들어온 쪽이 앞을 밀어낸다.
 */
export const DEMO_GROUPS: DemoGroup[] = [
  {
    title: "구매자",
    note: "상품을 고르고 장바구니에 담아 결제까지 해 보실 수 있습니다. 결제는 모의 결제입니다.",
    accounts: [
      { email: "buyer1@example.com", name: "구매자1" },
      { email: "buyer2@example.com", name: "구매자2" },
      { email: "buyer3@example.com", name: "구매자3" },
    ],
  },
  {
    title: "판매자",
    note: "받은 주문과 등록한 상품, 문의와 정산서를 보실 수 있습니다. 판매자3은 다른 판매사 소속이라 보이는 상품이 다릅니다.",
    accounts: [
      { email: "seller1@example.com", name: "판매자1" },
      { email: "seller2@example.com", name: "판매자2" },
      { email: "seller3@example.com", name: "판매자3" },
    ],
  },
  {
    title: "시스템관리자",
    note: "감사 기록에서 누가 언제 무엇을 했는지 보실 수 있습니다. 판매자 화면도 모든 판매사의 것이 보입니다.",
    accounts: [
      { email: "admin1@example.com", name: "시스템관리자1" },
      { email: "admin2@example.com", name: "시스템관리자2" },
      { email: "admin3@example.com", name: "시스템관리자3" },
    ],
  },
];
