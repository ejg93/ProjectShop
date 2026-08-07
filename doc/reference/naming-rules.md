# 명명 규칙

무엇을 어떤 이름으로 부르나. DB 컬럼과 Java 식별자를 한 장에서 정한다.

JSON 속성 이름은 여기서 안 다룬다. `api-guidelines.md`(D5)가 Zalando 를 따라 `snake_case` 로 정했다.
**다만 그 결정 때문에 DB 컬럼명이 API 로 그대로 샌다** — 아래 「이 규칙은 API 로 샌다」를 본다.

## 기준

| 대상 | 기준 자료 |
|---|---|
| SQL | [SQL Style Guide](https://www.sqlstyle.guide/) (Simon Holywell, CC BY-SA 4.0) |
| Java | [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html) |

둘 다 살아 있고 널리 인용되며 링크로 대조할 수 있다.
`SQL Antipatterns`(Bill Karwin)도 같은 방향을 말하지만 책이라 인용 대조가 어려워서 기준으로 안 잡았다.

## 기준에서 벗어난 것

먼저 적는다. 벗어난 곳을 뒤에 숨기면 다음 사람이 기준만 읽고 어긋난 코드를 짠다.

### 1. 테이블은 단수형이다

가이드는 복수형(`employees`)을 권한다. 우리는 `app_user`, `role`, `seller` 처럼 단수를 쓴다.

**코드의 타입 이름과 1:1로 붙이려는 것이다.** `app_user` ↔ `AppUser`, `consent_item` ↔ `ConsentItem`.
복수형이면 매핑에 "복수를 단수로 되돌리는" 규칙이 하나 더 붙고, 불규칙 복수에서 어긋난다.

### 2. 시각 컬럼은 `_at` 이다

가이드의 표준 접미사에는 `_date` 만 있다. 우리는 `created_at`, `deleted_at`, `acted_at` 을 쓴다.

`_date` 는 날짜형에 맞는 이름인데 **우리는 전부 `timestamptz` 다**(`D10` 이 UTC 저장으로 정했다).
날짜가 아니라 시각이므로 `_at` 이 값을 더 정확히 말한다.

### 3. 기존 테이블의 기본키는 `id` 로 남는다

가이드는 **"`id` 를 기본 식별자 이름으로 쓰지 말라"** 고 명시한다. 아래 규칙도 그쪽을 따른다.
하지만 **이미 있는 테이블 13개는 안 바꾼다.**

바꾸면 마이그레이션 하나에 테이블 13개와 코드 전반이 걸리는데, 얻는 것이 일관성뿐이다.
지금 이름 때문에 틀린 코드가 나온 적은 없다 — 조회를 전부 명시적 `select` 로 써서
`select *` 의 컬럼 충돌이 성립하지 않았다.

**새로 만드는 테이블부터 적용한다.** 상품 축(청크 6)이 첫 대상이다.

## SQL

### 공통

| 규칙 | 값 |
|---|---|
| 문자 | 소문자, 숫자, 밑줄만 |
| 길이 | 30자 이하 |
| 시작·끝 | 글자로 시작하고 밑줄로 끝내지 않는다 |
| 예약어 | 쓰지 않는다 — `user` 를 못 써서 `app_user` 가 됐다 |

### 테이블

단수형. `tbl_` 같은 접두사를 안 붙인다. 테이블과 같은 이름의 컬럼을 두지 않는다.

### 기본키

**`<테이블>_id`.** `product` 의 기본키는 `product_id` 다.

이렇게 하면 외래키가 같은 이름이 되어 조인에서 이름이 안 바뀐다.

```sql
select * from product p join sku s using (product_id)
```

`using` 을 쓸 수 있고, `select *` 로 여럿을 조인해도 `id` 가 여러 개 나오는 혼란이 없다.
`Map<String, Object>` 로 받을 때 키가 덮이는 사고도 안 난다.

**연결 테이블에서 특히 값을 한다.** `user_role` 은 지금 `user_id, role_id, id, seller_id` 인데
그 `id` 가 무엇의 id 인지 이름만으로 안 드러난다. 새 규칙이면 `user_role_id` 다.

### 외래키

**참조하는 테이블의 기본키 이름을 그대로 쓴다.** `sku.product_id` 는 `product.product_id` 를 가리킨다.

같은 테이블을 두 번 참조해서 이름이 겹치면 역할을 앞에 붙인다.
`consent_item.depends_on_id` 가 그 예다 — 같은 테이블을 가리키므로 `consent_item_id` 를 못 쓴다.

### 접미사

| 접미사 | 뜻 |
|---|---|
| `_id` | 식별자 |
| `_at` | 시각 (`timestamptz`) |
| `_status` | 상태 값 |
| `_type` | 종류 |
| `_name` | 이름 |
| `_code` | 코드에서 참조하는 안정된 키 |
| `_count` | 개수 |
| `_total` | 합계 |

`_num` 은 안 쓴다. 개수인지 번호인지 안 갈린다 — 개수는 `_count`, 번호는 `_no` 를 쓴다.

### 불리언은 `is_` 로 시작한다

`is_required`, `is_system`, `is_org_role`.

**기존에 어긋난 것이 하나 있다.** `user_consent.granted` 는 접두사가 없다.
안 바꾼다 — 그 컬럼은 `current_consent` 뷰와 응답에 그대로 나가고, 이름만 고치면 API 가 바뀐다.

## Java

Google Java Style Guide 를 따른다. 아래는 그 위에 우리가 더 정한 것뿐이다.

### DB 컬럼과 Java 필드

컬럼은 `snake_case`, 필드는 `lowerCamelCase`. **이름이 바뀌는 자리는 여기 하나뿐이다.**

```
DB      created_at      password_hash    is_required
Java    createdAt       passwordHash     required
JSON    created_at      password_hash    is_required
```

불리언의 `is_` 는 Java 에서 뗀다. 게터가 `isRequired()` 라 접두사가 두 번 붙는다.

### 이름을 짓는 자리

| 대상 | 규칙 | 예 |
|---|---|---|
| 판정·조회 서비스 | 명사 | `PermissionEvaluator`, `AuditLogQuery` |
| 요청·응답 record | `<동작>Request` / `<동작>Response` | `SignupRequest`, `LoginResponse` |
| 서비스 입력 record | `Command` (서비스 안에 중첩) | `SignupService.Command` |
| 조회 조건 | `Criteria` | `AuditLogQuery.Criteria` |
| 목록 응답 | `Page` (항목·페이지·크기·전체) | `AuditLogQuery.Page` |
| DB 행을 그대로 담은 것 | `Row` (private) | `AccountService.Row` |
| 도메인 값 | 그 값의 이름 그대로 | `Decision`, `Target`, `Rule`, `Account` |
| 테스트 | `<대상>Test` | `PermissionEvaluatorTest` |
| 테스트 바탕 | `<무엇>TestBase` | `PostgresTestBase`, `HttpTestBase` |

**`Request`·`Response` 는 HTTP 경계에서만 쓴다.** 서비스 안쪽까지 그 이름이 들어가면
서비스가 웹을 아는 모양이 된다(`coding-rules.md` 의 「예외」와 같은 이유다).

### 축약하지 않는다

`cnt`, `usr`, `perm` 을 안 쓴다. 길이를 아껴서 얻는 것보다 읽을 때 잃는 것이 크다.
널리 쓰이는 것(`id`, `url`, `http`)은 예외다.

## 이 규칙은 API 로 샌다

`D5` 가 **DB 컬럼명과 JSON 필드명을 같게** 정했다. 로그·쿼리·응답을 같은 단어로 검색하려는 것이다.

그 대가로 **컬럼 이름을 바꾸면 API 응답이 바뀐다.** 이름을 고칠 때 그 자원을 쓰는 화면이
있는지 먼저 본다. 위에서 `granted` 와 기존 `id` 를 안 바꾸기로 한 이유가 이것이다.

## 이 문서를 고칠 때

새 테이블을 만들면서 여기 없는 접미사를 쓰게 되면 **그 접미사를 여기 먼저 추가한다.**
표에 없는 이름이 스키마에 먼저 들어가면 다음 사람이 그것을 관례로 본다.
