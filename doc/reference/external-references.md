# 바깥 참조 문서

기준 문서(D1~D21)를 쓸 때 근거로 삼는 바깥 자료다.
링크는 2026년 8월 5일에 열어서 확인했다. 회사 문서는 구조가 바뀌므로 안 열리면 검색해서 갱신한다.

## 고르는 게 아닌 것 — 규격

선택지가 없다. 도구가 알아서 따르고, 막힐 때 해당 절만 찾아본다.

| 규격 | 링크 | 우리와의 관계 |
|---|---|---|
| RFC 9110 HTTP Semantics | https://www.rfc-editor.org/rfc/rfc9110.html | 메서드와 상태 코드의 정의 원본 |
| RFC 9457 Problem Details | https://www.rfc-editor.org/rfc/rfc9457.html | 오류 응답 본문 형식. 청크 7b 에서 채택 |
| OpenAPI 3.1 | https://spec.openapis.org/oas/v3.1.0.html | 청크 2a 의 springdoc 이 이 형식으로 뽑는다 |

## D5 API 설계 규약

**기준: Zalando RESTful API Guidelines.** 스타일 가이드는 하나만 고른다.
둘을 섞으면 같은 문제에 서로 다른 답이 들어와 규약이 충돌한다.

- https://opensource.zalando.com/restful-api-guidelines/

확인한 것

- 규칙마다 RFC 2119 의 `MUST`·`SHOULD`·`MAY` 등급이 붙어 있다. 통째로 받아들이지 않고 골라 쓸 수 있다
- OpenAPI 3.1 을 권장한다. 우리 청크 2a 와 맞는다
- 장 구성: REST Basics(메타 정보·보안·데이터 형식·URL·JSON) → HTTP 요청·상태코드·헤더 →
  REST Design(하이퍼미디어·성능·페이징·호환성·폐기) → API Operations → Event Basics·Design
- **Event 장이 따로 있다.** 청크 29(웹훅)·32(아웃박스)의 `D12` 이벤트 카탈로그가 이걸 쓴다

D5 는 우리가 정할 20개 안팎만 본문에 쓰고, 나머지는 "여기 없는 것은 Zalando 를 따른다" 한 줄로 넘긴다.
통째로 베끼면 유지가 안 된다.

## D6 권한 모델 명세

우리 모델이 이미 코드로 있어서 베낄 자리가 아니다. **개념과 점검 목록만 빌린다.**

- XACML 3.0 (OASIS 표준): https://docs.oasis-open.org/xacml/3.0/xacml-3.0-core-spec-en.html

빌린 것

- **결합 알고리즘의 이름.** 우리가 "deny 가 allow 를 이긴다" 고 정한 것이 `deny-overrides` 다
- **판정 결과 4종.** Permit·Deny·NotApplicable·Indeterminate.
  우리 `Decision` 은 앞의 셋을 `boolean` 하나로 뭉쳤고 넷째가 없다. `permission-rules.md` 에 적어 뒀고 청크 7b 에서 정한다

XACML 자체를 도입하지 않는다. XML 정책 언어라 이 규모에 과하다.

참고로만 두는 것

- NIST RBAC (INCITS 359) — "역할" 개념의 공식 정의. 용어가 헷갈릴 때
- Google Zanzibar 논문, OpenFGA, SpiceDB — 관계 기반(ReBAC) 모델.
  우리는 RBAC + 스코프라 **모델이 달라서 섞으면 헷갈린다**

## D15 테스트 전략

**개념 기준: Test Pyramid. 도구 기준: Spring Boot + Testcontainers 공식 문서.**

| 자료 | 링크 |
|---|---|
| The Practical Test Pyramid | https://martinfowler.com/articles/practical-test-pyramid.html |
| Spring Boot Testing (4.1.0) | https://docs.spring.io/spring-boot/reference/testing/index.html |
| Testcontainers for Java (2.0.5) | https://java.testcontainers.org/ |
| Spring Boot × Testcontainers | https://docs.spring.io/spring-boot/reference/testing/testcontainers.html |

확인한 것

- Practical Test Pyramid 는 Ham Vocke 가 2018년 2월 26일에 martinfowler.com 에 쓴 글이다.
  Mike Cohn 의 원래 피라미드를 실무용으로 풀었고, **예제가 Java + Spring Boot 다**
- 층은 단위 → 통합 → 계약 → UI → 종단이다. 원칙은 "작고 빠른 단위 테스트를 많이,
  거친 테스트를 조금, 종단 테스트는 아주 적게"
- Spring Boot 공식 문서가 **4.1.0** 을 다룬다. 우리가 쓰는 버전과 같다
- Spring Boot 테스트 장에 **Testcontainers 절이 따로 있다.** 청크 35 가 이걸 쓴다
- Testcontainers 현재 버전은 2.0.5. Postgres 모듈이 있다

### 지금 우리 상태와의 차이

피라미드를 기준으로 보면 **지금 테스트가 뒤집혀 있다.**
20개 전부가 Spring 컨텍스트를 띄우고 실제 DB 에 붙는 통합 테스트고, 순수 단위 테스트가 0개다.
그리고 그 DB 가 손으로 띄운 로컬 컨테이너라 Docker Desktop 을 끄면 전부 실패한다.

D15 는 이걸 어디까지 바꿀지 정하는 문서다.

## D3 비즈니스 모델

**기준: Stripe Connect 문서.** 표준이 없는 영역이라 실물을 본다.

- https://docs.stripe.com/connect

우리와 구조가 같은 절

| 절 | 링크 | 무엇을 배우나 |
|---|---|---|
| Build a marketplace | https://docs.stripe.com/connect/marketplace | 고객에게 받아서 판매자에게 나눠 주는 구조 |
| Create a charge | https://docs.stripe.com/connect/charges | 플랫폼과 판매자 사이에 결제를 어떻게 쪼개나 |
| Account balances | https://docs.stripe.com/connect/account-balances | 잔액이 언제 잡히고 언제 풀리나 |
| Pay out to connected accounts | https://docs.stripe.com/connect/payouts-connected-accounts | 지급 주기와 외부 계좌 |
| Platform pricing tool | https://docs.stripe.com/connect/platform-pricing-tools | 플랫폼 수수료를 어디에 매기나 |

**Charges 와 Account balances 가 핵심이다.** 청크 17~21(정산)에서 만날 어려운 경우가 여기 있다.
정산 후에 환불이 들어오면 이미 준 돈을 어떻게 회수하나, 분쟁 중인 금액을 어떻게 잡아 두나.

결제를 흉내로 만들어도 이 구조는 그대로 배운다.

### 곁들여 보는 것

- 쿠팡·네이버 판매자센터의 수수료 안내 — 카테고리별 요율의 현실적 범위
- Business Model Canvas — D3 첫 절에 한 페이지로 그리는 용도.
  본문은 **돈의 흐름과 확정 시점**이어야 해서 캔버스로는 해상도가 안 나온다

## D2 법·정책 (완료)

- 국가법령정보센터: https://law.go.kr — 전자상거래법·개인정보법 원문
- 상세는 `commerce-compliance.md`

## 이 문서를 고칠 때

링크가 죽거나 문서 구조가 바뀌면 여기를 고친다.
새 기준 문서를 정할 때 그 근거 자료를 여기 추가한다.

회사가 내는 문서(Zalando·Stripe·Spring)는 갱신되므로 **버전을 적어 두지 않는다.**
대신 무엇을 확인했는지를 적어서, 나중에 내용이 달라졌을 때 알아볼 수 있게 한다.
