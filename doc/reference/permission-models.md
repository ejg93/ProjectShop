# 바깥 권한 모델에서 가져온 것

우리 스키마를 설계할 때 근거로 삼은 두 모델을 요약한다. 코드는 가져오지 않고 구조만 본다.
원문이 바뀔 수 있으니 판단이 갈리면 아래 출처를 다시 본다.

- Shopify 접근 스코프: https://shopify.dev/docs/api/usage/access-scopes
- Shopify 스태프 권한: https://help.shopify.com/en/manual/your-account/staff-accounts/staff-permissions
- GitHub 조직 역할: https://docs.github.com/en/organizations/managing-peoples-access-to-your-organization-with-roles/roles-in-an-organization
- GitHub 저장소 역할: https://docs.github.com/en/organizations/managing-user-access-to-your-organizations-repositories/managing-repository-roles/repository-roles-for-an-organization

## Shopify — 권한을 어느 입도로 쪼개나

API 접근 스코프가 `read_orders` / `write_orders` 처럼 **동작 두 개 × 자원 이름**으로 되어 있다.
자원은 orders, products, customers, inventory, discounts 같은 업무 덩어리 단위고, 테이블 단위가 아니다.

가져올 것:

- 자원 이름을 테이블이 아니라 업무 덩어리로 잡는다. 우리 `permission.resource` 가 `product_option` 이 아니라 `product` 인 이유가 이것이다
- 읽기와 쓰기를 반드시 가른다. 조회만 되는 역할(정산 담당, 감사자)이 실제로 필요해진다
- 스코프가 다른 스코프를 요구하는 경우가 있다. `read_products` 는 구매 옵션 조회 권한을 같이 요구한다.
  권한 사이에 의존이 생기면 판정에서 한쪽만 통과시키면 안 된다
- 민감한 자원은 승인을 거쳐야 한다. `read_all_orders`, 고객 결제수단 조회가 그렇다.
  권한 목록에 있다고 아무 역할에나 붙일 수 있는 게 아니라는 뜻이다

스태프 권한 쪽은 역할을 **매장 단위 / 조직 단위 / POS 단위 / 파트너 조직 단위**로 나눈다.
같은 사람이 매장에서 갖는 권한과 조직에서 갖는 권한이 다른 층에 있다.

## GitHub — 역할을 어느 범위에 붙이나

역할이 세 층에서 따로 붙는다.

| 층 | 역할 예시 | 범위 |
|---|---|---|
| 조직 | Owner, Member, Moderator, Billing manager, Security manager | 조직 전체 |
| 저장소 | Read, Triage, Write, Maintain, Admin | 저장소 하나 |
| 팀 | Team maintainer | 팀 하나 |

가져올 것:

- **역할 부여에 대상이 붙는다.** "이 사람은 관리자" 가 아니라 "이 사람은 이 저장소의 관리자" 다.
  우리 `user_role` 에 셀러 참조를 붙이는 근거가 이것이다
- **기본 권한(base permission)이 따로 있다.** 조직에 속하기만 해도 깔리는 바닥이 있고, 역할이 그 위에 얹힌다
- 조직 소유자는 대상별 역할과 무관하게 모든 저장소에 접근한다. 상위 층의 역할이 하위 층을 덮는 경로가 하나 있다는 뜻이다
- 저장소 역할 다섯 개는 포함 관계다. Write 는 Read 가 하는 걸 다 한다.
  단순 계단이라 이해는 쉽지만, "PR은 관리하되 코드는 못 미는" Triage 같은 게 필요해서 결국 계단 사이에 역할이 끼어든다

## 우리가 안 가져오는 것

- 역할 계단(Read < Write < Admin) 구조는 안 쓴다. 포함 관계를 코드로 표현하면 새 역할을 데이터로 못 만든다.
  우리는 역할마다 권한 집합을 따로 붙이고, 겹치면 넓은 쪽이 이기게 판정한다
- Shopify의 `write_X` 단일 동작은 안 쓴다. 등록·수정·삭제를 갈라야 "등록은 되는데 삭제는 안 되는" 역할이 나온다
- 승인 절차가 필요한 권한을 플랫폼이 심사하는 구조는 안 만든다. 대신 관리자만 부여할 수 있는 권한으로 표시한다
