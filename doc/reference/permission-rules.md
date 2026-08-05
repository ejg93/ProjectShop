# 권한 모델 명세

지금 이 저장소의 판정이 실제로 어떻게 도는지를 한 장으로 적는다.
바깥 서비스의 권한 모델 요약은 `permission-models.md` 에 따로 있다. 이 문서는 우리 규칙이다.

## 왜 두나

규칙이 다섯 군데에 흩어져 있었다.
`V3__auth_seed.sql` 이 역할별 권한을, `V5__deny_effect.sql` 이 거부를, `V6__field_visibility.sql` 이 필드를,
`PermissionEvaluator` 가 이 셋을 합치는 방법을, ADR 0003·0004·0007 이 그렇게 정한 이유를 갖고 있다.

"판매자가 자기 상품이 걸린 주문을 볼 수 있나" 를 답하려면 다섯을 다 읽어야 했다.

## 판정의 입력과 출력

```
decide(userId, resource, action, target) -> Decision
```

| 입력 | 무엇 |
|---|---|
| `userId` | 판정 대상 사용자 |
| `resource` | 자원 종류. `order`, `product`, `payment`, `user`, `role` |
| `action` | 동작. `read`, `create`, `update`, `delete`, `update_status`, `refund`, `assign`, `manage` |
| `target.ownerUserId` | 이 행의 주인. 주문이면 주문자. 주인이 없으면 null |
| `target.sellerId` | 이 행이 속한 셀러. 셀러와 무관하면 null |

| 출력 | 무엇 |
|---|---|
| `allowed` | 허용 여부 |
| `reason` | 어느 규칙이 이겼나. 사람이 읽는 문자열 |
| `visibleFieldGroups` | 볼 수 있는 필드 그룹. **빈 집합은 제한 없음** |

## 판정 순서

순서가 결과를 바꾼다. 규칙 두 개가 방향이 반대라서다.

```
1. 사용자의 역할에 걸린 규칙을 전부 읽는다
   규칙이 하나도 없으면 → 거부

2. deny 규칙을 먼저 훑는다
   대상을 덮는 deny 가 하나라도 있으면 → 거부 (즉시 끝)

3. allow 규칙을 전부 모은다 (첫 매치에서 끊지 않는다)
   대상을 덮는 allow 가 없으면 → 거부

4. 허용
   근거로 남길 규칙 = 모인 allow 중 스코프가 가장 넓은 것
   보이는 필드 = 모인 allow 의 필드 그룹 합집합
```

### 왜 deny 를 먼저 훑나

allow 를 먼저 보면 넓은 allow 하나가 매치되는 순간 반환해서, 뒤에 있는 deny 를 영영 못 본다.
`allow/seller` 와 `deny/own` 을 같이 가진 판매자가 자기 주문을 그대로 통과시킨다.

### 왜 allow 를 첫 매치에서 안 끊나

끊으면 DB 가 돌려주는 순서가 판정을 정한다. 스코프 우선순위가 실제로는 적용되지 않는다.
필드 그룹도 먼저 걸린 규칙 것만 반영돼서, 역할이 둘인 사용자가 한쪽 역할의 필드만 보게 된다.

청크 4 에 이 결함이 있었고 4d 에서 고쳤다. 테스트 13개가 통과하는데도 못 잡았다 —
역할이 하나뿐인 사용자만 검증해서 규칙이 하나씩만 걸렸다.

## 우선순위 두 개

**방향이 서로 반대다.** 이 프로젝트에서 판정이 어려운 이유가 이것이다.

| 축 | 규칙 | 뜻 |
|---|---|---|
| 스코프 | `all` > `seller` > `own` | 넓은 쪽이 이긴다 |
| 효과 | `deny` > `allow` | 좁은 쪽이 이긴다 |

스코프 우선순위는 **근거를 고를 때만** 쓴다. 허용 여부 자체는 "덮는 allow 가 하나라도 있나" 로 정해진다.

## 스코프의 뜻

| 스코프 | 덮는 범위 |
|---|---|
| `all` | 전부 |
| `own` | `target.ownerUserId == userId` |
| `seller` | 아래 두 갈래 |

`seller` 는 역할을 **어떻게 받았느냐로 뜻이 갈린다.**

| 부여 방식 | 덮는 범위 |
|---|---|
| 조직 역할 (`user_role.seller_id` 있음) | 받은 그 셀러만 |
| 전역 역할 (`user_role.seller_id` 없음) | 사용자가 속한 모든 셀러 (`seller_member` 기준) |

구분하지 않으면 A셀러의 CS 담당이 B셀러의 주문을 본다.
지금 `seller` 역할은 `is_org_role = true` 라 항상 앞쪽이다. 뒤쪽은 규칙만 있고 쓰는 데이터가 없다.

## 필드 그룹

행에 접근할 수 있다는 것과 그 행을 통째로 볼 수 있다는 것이 다르다.

| 규칙 | 내용 |
|---|---|
| 연결이 없는 규칙 | 제한이 없다. 모든 필드가 보인다 |
| 여러 allow 가 걸림 | 필드 그룹의 **합집합** |
| 하나라도 제한 없는 규칙이 있음 | 제한이 풀린다 |
| 거부된 판정 | 어떤 필드도 못 본다 |

### 정의된 그룹

| 자원 | 그룹 | 내용 |
|---|---|---|
| `order` | `basic` | 주문번호, 금액, 상태, 일시 |
| `order` | `shipping` | 수령인, 연락처, 배송지 주소 |
| `order` | `payment` | 결제 수단, 승인번호 |
| `user` | `basic` | 표시 이름, 가입일 |
| `user` | `contact` | 전자우편, 연락처 |

## 지금 데이터 — 역할 × 권한 매트릭스

마이그레이션 V3·V5·V6 이 넣은 값이다. 청크 4c 의 회귀 테스트가 이 표를 고정한다.

`A/범위` 는 allow, `D/범위` 는 deny 다. 빈 칸은 규칙 없음이다.

| 권한 | customer | seller | admin | auditor |
|---|---|---|---|---|
| `product:read` | A/all | A/all | A/all | A/all |
| `product:create` | | A/own | A/all | D/all |
| `product:update` | | A/own | A/all | D/all |
| `product:delete` | | A/own | A/all | D/all |
| `order:read` | A/own | A/seller | A/all | A/all |
| `order:create` | A/own | A/own | A/all | D/all |
| `order:update_status` | | A/seller + **D/own** | A/all | D/all |
| `payment:read` | A/own | A/seller | A/all | A/all |
| `payment:refund` | | | A/all | D/all |
| `user:read` | A/own | A/own | A/all | A/all |
| `user:update` | A/own | A/own | A/all | D/all |
| `role:read` | | | A/all | A/all |
| `role:assign` | | | A/all | D/all |
| `role:manage` | | | A/all | D/all |

### 필드 그룹 연결

| 역할 | 권한 | 보이는 그룹 |
|---|---|---|
| customer | `order:read` | basic, shipping, payment |
| seller | `order:read` | basic, shipping |
| auditor | `order:read` | basic, shipping |
| admin | `order:read` | 연결 없음 → 제한 없음 |

### 역할의 성격

| 역할 | `is_system` | `is_org_role` | 뜻 |
|---|---|---|---|
| customer | true | false | 전역 부여 |
| seller | true | **true** | 셀러를 지정해야 부여된다 |
| admin | true | false | 전역 부여 |
| auditor | true | false | 전역 부여. 다른 역할 위에 덧씌워서 쓴다 |

## 판정 결과의 종류

XACML 3.0 은 판정 결과를 넷으로 나눈다. 우리 `Decision` 과 대조하면 이렇다.

| XACML | 뜻 | 우리 구현 |
|---|---|---|
| Permit | 허용 | `allowed = true` |
| Deny | 거부 규칙이 있어서 거부 | `allowed = false`, reason 에 규칙 |
| NotApplicable | 해당하는 규칙이 없어서 거부 | `allowed = false`, reason 에 "규칙이 하나도 없다" |
| Indeterminate | 판정 중 오류 | **없다.** 예외가 그대로 위로 전파된다 |

### 여기서 나오는 두 가지 문제

**하나. Deny 와 NotApplicable 이 같은 값이다.**
`allowed = false` 하나로 뭉쳐 있고 `reason` 문자열로만 갈린다.
청크 7b(권한 실패 응답 규약)에서 403 과 404 를 가르려면 이 구분이 값으로 필요해진다.
문자열을 파싱해서 가르는 건 규약이 아니다.

**둘. Indeterminate 가 정의돼 있지 않다.**
DB 조회가 실패하면 지금은 예외가 터지고 그 위에서 무슨 일이 나는지 정해진 바가 없다.
권한 판정이 실패했을 때 **거부로 떨어지는지 서버 오류로 나가는지** 는 보안에 직결된다.
정하지 않으면 프레임워크 기본 동작에 맡기게 된다.

둘 다 7b 에서 결정한다.

## 알려진 구멍

지금 통과하는 것이 정상이지만 고쳐야 할 것들이다.

### 1. 감사자가 새 권한으로 뚫린다

`V5` 의 감사자 deny 는 그 마이그레이션을 쓰던 시점의 권한만 훑는다.

```sql
where p.action not in ('read')
```

뒤 청크가 권한을 추가하면서 감사자 deny 를 같이 안 넣으면, 감사자에게 관리자 역할이 붙었을 때 그 권한이 통과한다.
`PermissionEvaluatorTest.KnownHole` 이 이걸 고정하고 있다.

읽기·쓰기 분류를 `permission` 에 컬럼으로 두고 판정에서 처리하는 것이 제대로 된 해결이다.

### 2. 액션 이름에 기대고 있다

같은 줄이 "액션 이름이 `read` 면 읽기" 라는 암묵 규칙을 쓴다.
`product:list`, `order:search` 같은 것이 생기면 이름이 `read` 가 아니라서 감사자에게 deny 가 붙는다.
감사자가 조회를 못 하게 된다. 1번과 뿌리가 같다.

### 3. user 필드 그룹이 아무도 제한하지 않는다

`V6` 이 `user:basic` 과 `user:contact` 를 정의했지만 **어느 규칙에도 연결하지 않았다.**
그래서 `user:read` 는 전부 제한 없음이다. 그룹은 있는데 아무도 제한받지 않는 상태다.

V6 주석이 경고한 상황이 그 마이그레이션 안에서 이미 벌어졌다.
셀러가 고객 계정을 조회하는 경로가 생기면(문의·CS 청크) 여기가 바로 문제가 된다.

### 4. 스코프가 목록 조회에 안 걸린다

이 문서의 판정은 **행 하나**에 대한 것이다.
목록 조회에서 남의 데이터가 새는 것은 쿼리에 스코프를 섞어야 막히고, 그건 청크 8 이다.
판정 엔진만 만들어 놓고 목록을 그대로 두면 단건은 막히는데 목록에서 다 보인다.

## 이 문서를 고칠 때

판정 규칙이나 초기 데이터가 바뀌면 여기를 같이 고친다.
청크 4c 의 매트릭스 테스트가 이 표와 같은 모양이라, 한쪽만 바뀌면 테스트가 깨져서 알 수 있다.

바깥 모델과의 비교는 `permission-models.md`, 법 요건과의 연결은 `commerce-compliance.md` 에 있다.
