# 남의 실물 대조 — 판정 원장

돌아가는 오픈소스 커머스 엔진의 코드를 우리 기준 문서와 자리마다 맞댄 결과를 묶음마다 적는다.
차례와 절차는 `PLAN.md` 「대조 — 남의 실물과 맞대 보는 점검」이 든다.

**이 파일은 청크 실행 때 안 본다.** 기준 문서가 아니라 판정 원장이다.
남의 코드는 관례(`D23` 축 1 의 4순위)라 **여기 적힌 것을 근거로 코드를 쓰지 않는다** — 바꿀 것은 분할표 행으로 간다.

| 판정 | 뜻 | 처분 |
|---|---|---|
| **같다** | 모양이 우리와 같다 | 이 표에 한 줄 |
| **다르다·근거 있음** | 우리 문서에 왜 다른지가 이미 있다 | 그 문서 절을 적는다 |
| **다르다·근거 없음** | 다른데 우리 쪽 이유가 문서에 없다 | 근거가 이력·표준에 있으면 문서로 올리고, 없으면 「설계 필요」 행 |
| **우리에 없음** | 저쪽에만 있다 | 몇 곳이 그렇게 하는지 세어 「설계 필요」 행 |

---

## 묶음 1 — API 계약(`점검 X`, 2026-10-29)

**짝**: Medusa v2(`medusajs/medusa` `5ac3fdcbf82e`, MIT — `ENTERPRISE-LICENSE.md` 폴더는 안 읽었다) · Spree 5 v3(`spree/spree` `f227994feffd`, BSD-3).
**표기**: `M:` 는 Medusa 의 `packages/`, `S:` 는 Spree 의 `spree/api/app/` 아래 경로다. 줄 번호는 위 해시 기준이다.
**우리 쪽**: `api-guidelines.md`(D5)·`identifier-rules.md`(D9)·`concurrency-rules.md`(D11)의 절 이름으로 적는다. 멱등 항목은 D9 「멱등키」 절이 「형식은 D11 에서 정한다」고 넘겨서 D11 을 정본으로 삼았다.
**공식 문서**: docs.medusajs.com 의 Store API 소개는 목록·`fields`·정렬만 다룬다(오류·멱등·버전은 「not stated」). Spree 의 Store API 소개는 `/api/v3/store` 경로와 SDK 만 든다. **판정은 코드가 근거다.**

| 항목 | 우리 | Medusa | M 판정 | Spree | S 판정 | 처분 |
|---|---|---|---|---|---|---|
| 이름 규칙 | JSON snake_case · URL 복수형 하이픈(D5 「이름」) | 응답 칸 snake_case(`M:medusa/src/api/store/products/query-config.ts:22` `created_at`) · 경로 `product-categories`·`payment-collections` | 같다 | 칸 snake_case(`S:serializers/spree/api/v3/order_serializer.rb:8` `customer_note`) · 경로 `/api/v3/store/carts`(`S:controllers/spree/api/v3/store/carts_controller.rb:169`) | 같다 | 없음 |
| 오류 본문 | RFC 9457 · `tag:` `type` · 사용자 문구 `message`(D5 「오류 본문」) | 자기 형식 `{code, type, message}`(`M:core/framework/src/http/middlewares/error-handler.ts:47`). `unexpected_state`·`invalid_argument` 는 상태 코드를 안 정해 500 으로 나간다(같은 파일 :88) | 다르다·근거 있음 | 자기 형식 `{error: {code, message, details}}`(`S:controllers/concerns/spree/api/v3/error_handler.rb:130`). 오류 코드 쉰여섯을 한 표에 둔다 | 다르다·근거 있음 | D5 「오류 본문」 — 표준(2순위)을 골랐다. 셋 다 상태 코드가 아니라 슬러그로 가른다 |
| 검증 실패 형식 | 필드마다 요청 이름(snake_case)과 칸 문구(D5 「검증 실패는 어느 필드가 틀렸는지 준다」) | 문제를 한 문자열로 이어 붙인다 — 필드 구조가 없다(`error-handler.ts:144` `messages.join("\n")`) | 다르다·근거 있음 | 422 + `details` 가 `{필드: [문구]}`(`error_handler.rb:357`) | 같다 | D5 그 절 — 화면이 칸 옆에 그린다 |
| 상태 코드(201·`Location`) | 생성 201 + `Location`(D5 「상태 코드」) | 생성도 200(`M:medusa/src/api/store/carts/route.ts:31`, store 의 `res.status` 서른하나가 전부 200). 삭제는 200 + `{id, object, deleted}`(`carts/[id]/line-items/[line_id]/route.ts:59`) | 다르다·근거 없음 | 생성 201(`carts_controller.rb:104`, `:created` 쉰여덟) · `Location` 0 · 삭제 204(`resource_controller.rb:92`) | 다르다·근거 없음 | **D5 에 표준 원문을 올렸다**(근거 없음 → 문서로) — 그전에는 표에 「`Location` 헤더에 새 자원 경로」만 있고 왜인지가 없었다(RFC 9110 §15.3.2) |
| 목록 요청 | `page`(0부터)·`size`(상한 100)·`sort=필드,방향` 허용 목록 400(D5 「목록 조회 > 요청」) | `offset`·`limit`(기본 20, 상한 없음 — `M:medusa/src/api/utils/validators.ts:60`) · `order=-필드` 허용 목록 400(`core/framework/src/http/utils/get-query-config.ts:136`) | 다르다·근거 없음 | `page`(1부터, `resource_controller.rb:452`)·`limit`(상한 100, :458)·`sort` | 다르다·근거 없음 | **`Q311`** — 이름 `limit` 이 둘 다고, 0 기점은 우리뿐이다. D5 에 왜 `size`·0 인지가 없다 |
| 목록 응답 | 껍데기 `items`·`page`·`size`·`total` 고정 · 빈 목록 `[]`(D5 「목록 조회 > 응답」) | 자원 이름 키 + `count`·`offset`·`limit`(`M:medusa/src/api/store/orders/route.ts:36`). 상품은 `estimate_count` 도 준다(`products/route.ts:100`) | 다르다·근거 있음 | `{data, meta}` 고정(`resource_controller.rb:22`) · `meta` 에 `count`·`pages`·`next` | 같다 | D5 「껍데기 이름을 하나로 고정한다」. 추정 개수는 `Q312` 를 세웠다가 닫았다 — `D21` 「질의 평균 — 목록 · 건수」가 정확한 `total` 의 값을 이미 쟀다 |
| 필드 선택·확장 | 없다 — 보는 사람마다 필드 그룹으로 깎는다(D5 「null 과 생략」 `_visible_field_groups`) | `fields`(`+`·`-`·`*관계`) · 허용·제한·금지 필터가 서버에서 깎는다(`get-query-config.ts:108`) | 우리에 없음 | `expand`·`fields`(`S:controllers/concerns/spree/api/v3/resource_serializer.rb:37`·`:49`) | 우리에 없음 | **`Q309`** — 둘 다 있다 |
| 식별자 노출 | 주문·셀러 주문·정산은 날짜 + 난수 노출 번호, 상품·사용자는 내부 `bigint`(D9 「자원별 노출 방식」) | 접두어 + ULID(`M:core/utils/src/common/generate-entity-id.ts:13`) — 순번은 안 새고 만든 시각이 샌다 | 다르다·근거 있음 | 접두어 id(`resource_controller.rb:255` `find_by_prefix_id!`). 만드는 법은 core 라 v3 범위 밖 | 다르다·근거 있음 | D9 「자원별 노출 방식」 — 상품 수는 목록에서 세면 나온다 |
| 주문 조회와 식별자 | 번호로 조회하지 않는다 — 권한 판정을 거친다(D9 「주문번호로 조회하지 않는다」) | 주문 상세가 **인증 없이** id 로 열린다(`M:medusa/src/api/store/orders/[id]/route.ts:6` 「the order ID is used as the authentication mechanism」) | 다르다·근거 있음 | 본인 범위나 주문 토큰으로만 찾는다(`S:controllers/spree/api/v3/store/orders_controller.rb:40`) | 같다 | D9 그 절 — 「번호가 곧 인증이 되면 안 된다」 |
| 멱등키 | `POST /api/orders`·`/api/payments` 필수 · 24시간 · 계정 범위 · 본문 다르면 422 · 처리 중 409 · 거절은 저장하고 예외는 안 한다(D11 「멱등키」·「거절은 저장한다」) | store·http 범위에 처리 0 — 409 문구만 그 헤더를 말한다(`error-handler.ts:62`) | 다르다·근거 있음 | 고치는 메서드 전부에 선택 · 24시간(`S:controllers/concerns/spree/api/v3/idempotent.rb:7`) · 본문 다르면 422 `idempotency_key_reused`(:40) · 2xx·4xx 를 저장하고 5xx 는 안 한다(:57) · 재생에 `Idempotent-Replayed: true`(:50) | 다르다·근거 있음 | D11 — 예외로 끝난 4xx 는 기록도 롤백된다. 행 판정과 별개로 재생 표시 헤더 하나가 우리에 없다 → **`Q310`** |
| 버전 자리 | 지금 없다 · 붙이면 경로(D5 「버전」) | 없다(`/store/...`) | 같다 | 경로 `/api/v3` | 같다 | 없음 |
| 폐기 절차 | OpenAPI `deprecated` → `Deprecation`·`Sunset` 헤더 → 호출 수 → 동의(D5 「폐기 절차」) | http 범위에 API 폐기 표시 0(코드 주석의 `@deprecated` 셋만) | 다르다·근거 있음 | 스펙 칸에 `deprecated:` 와 지울 판(`S:controllers/spree/api/v3/store/companies/addresses_controller.rb:15` 「Removed in 6.1」) · 서버 로그 경고 · 응답 헤더 0 | 다르다·근거 있음 | D5 그 절 — 우리는 넷째 단계까지 적었다. Spree 는 첫 단계와 같다 |
| 403/404 | 자원 민감도로 가른다 — 주문·결제·사용자 404, 상품·셀러·역할 403(D5 「권한 실패」) | 남의 주소는 404(`customers/me/addresses/[address_id]/route.ts:35`) · `forbidden` 은 403 | 같다 | store 는 본인 범위 질의라 남의 것은 404(`resource_controller.rb:259`) · 관리는 403 `access_denied` | 같다 | 없음 |
| 401 헤더 | `WWW-Authenticate` 를 붙인다(D5 「401 에는 `WWW-Authenticate` 를 붙인다」, RFC 9110 MUST) | 401 + `{message}`, 헤더 0(`M:core/framework/src/http/middlewares/authenticate-middleware.ts:146`) | 다르다·근거 있음 | 401 `authentication_required`, 헤더 0(`S:controllers/concerns/spree/api/v3/jwt_authentication.rb:45`) | 다르다·근거 있음 | D5 그 절 — 표준 MUST 다. 두 엔진이 다 빠뜨렸다 |
| 동작 엔드포인트 | 하위 경로에 `POST`, 상태 칸을 `PATCH` 로 안 바꾼다(D5 「동작」) | `POST carts/{id}/complete` · `orders/{id}/transfer/request` 꼴 | 같다 | `POST carts/{id}/complete`(`carts_controller.rb:169`) · 단 `PATCH carts/{id}/associate`(:146) | 같다 | 없음 |
| null 과 생략 | 값 없음은 `null`, 권한으로 뺀 칸은 생략 + `_visible_field_groups`(D5 「null 과 생략」) | 금지 칸을 조용히 빼고 200 — 뺐다는 표시가 없다(`get-query-config.ts:108`) | 다르다·근거 있음 | 값 없는 칸은 `nullable: true` 로 늘 싣는다(`order_serializer.rb:8`) | 같다 | D5 그 절 — 빠진 이유를 같이 준다 |

**셈**: 판정 서른둘 — 같다 열둘 · 다르다·근거 있음 열넷 · 다르다·근거 없음 넷 · 우리에 없음 둘. 행 판정 밖의 낱개 하나(멱등 행의 재생 표시 → `Q310`)는 셈에 안 넣었다. 항목 열다섯인데 행이 열여섯인 것은 식별자 줄을 「노출」과 「주문 조회」로 갈라서다(같은 D9 인데 묻는 것이 다르다).
**읽은 양**: Medusa 열여섯 자리 약 700줄 · Spree 열두 자리 약 1천 줄(상한 엔진당 40 파일·8천 줄 안). 받은 파일은 Medusa 393 · Spree 606 이고, 나머지는 `grep` 으로 자리만 찾았다.

**열다섯 밖에서 본 것**(처분 설계의 입력 — 판정 칸에 안 셌다):

| 무엇 | 어디 | 우리 |
|---|---|---|
| Medusa 는 수정도 `POST` 다(`carts/[id]/line-items/[line_id]/route.ts` 의 `POST`) | Spree 는 `PATCH` | D5 「메서드」 — `PATCH` + `merge-patch+json` |
| Spree 는 요청 제한 헤더를 `X-RateLimit-Limit`·`X-RateLimit-Remaining` 으로 모든 응답에 싣는다(`rate_limit_headers.rb:5`) | Medusa 범위 밖 | `X-` 는 RFC 6648 로 막는다(D5 「헤더」). 남은 양을 싣는 칸은 없다 — **묶음 4(보안)** 가 요청 제한 줄에서 본다. **묶음 4 ⑬ 이 봤다**(2026-10-31) — 셋 중 Spree 하나(1곳)라 행을 안 세웠다 |
| Spree 주문에 철회 기한 칸이 있다(`order_serializer.rb:17` `withdrawal_period_ends_at`·`within_withdrawal_period`) | — | 청약철회(R3) 기한을 응답에 싣나 — **묶음 3 ⑥ 이 봤다**(같은 시점에 박제해 싣는다). 처음 적은 것: **묶음 3(거래)** 의 입력 |

---

## 묶음 2 — 데이터 모델·돈(`점검 Y`, 2026-10-29)

**짝**: Saleor(`saleor/saleor` `c982006`, BSD-3 — 설계 해시와 같다) · Solidus(`solidusio/solidus` `f41bf95`, BSD-3 — **설계의 `76bcf31` 과 다르다**, 받은 기본 가지 HEAD 다. 줄 번호는 이 해시 기준).
**표기**: `SA:` 는 Saleor 의 `saleor/` 아래, `SO:` 는 Solidus 의 `core/` 아래 경로다. 마이그레이션은 `SO:db/migrate/20160101010000_solidus_one_four.rb` 를 `one_four` 로 줄였다.
**우리 쪽**: `money-rules.md`(D8)·`money-invariants.md`(불변식 카탈로그 — D 번호 없음)·`domain-model.md`(D4)의 절 이름으로 적는다.
**공식 문서**: 미열람 — **판정은 코드가 근거다.** Saleor 의 반올림 기본값은 저장소 밖 패키지 `mirumee/prices`(`95fd44366d9b`)의 `money.py:113` 을 `gh api` 로 읽었다.

| 항목 | 우리 | Saleor | SA 판정 | Solidus | SO 판정 | 처분 |
|---|---|---|---|---|---|---|
| 통화 | 원화만 · 통화 컬럼 없음(D8 「통화」) | 행마다 `currency` `CharField(3)`(`SA:order/models.py:167`·`:604`), 채널이 통화를 정한다 | 다르다·근거 있음 | 주문·가격에 `currency`(`one_four:286`·`:369`), 줄은 주문 통화를 따른다(`SO:app/models/spree/line_item.rb:42`) | 다르다·근거 있음 | D8 「통화」 — 다중 통화를 안 한다(`business-model.md`) |
| 금액 저장 형식 | `bigint` 원 정수(D8 「저장」) | `decimal(20,3)` 전부(`SA:settings.py:485-486`) | 다르다·근거 있음 | 대부분 `decimal(10,2)`(`one_four:271-293`), 반품 `(12,4)`·배송 요금 `(8,2)` 가 섞인다 | 다르다·근거 있음 | D8 「저장」 — 원이 최소 단위라 소수 자리가 들어갈 여지를 없앤다 |
| 요율 저장 | 만분율 정수 `_bp`(D8 「요율」) | 설정은 백분율 `decimal(20,3)`(`SA:tax/models.py:25-28`), 주문 박제는 분수 `decimal(5,4)`(`SA:order/models.py:726-728`) — 두 눈금을 `/100` 으로 잇는다(`SA:tax/utils.py:338-347`) | 다르다·근거 있음 | 분수 `decimal(8,5)`(`one_four:831`) | 다르다·근거 있음 | D8 「요율」 — 곱한 결과가 소수가 되고 `BigDecimal` 비교에 함정이 있다 |
| 부가세 포함/별도 | 포함가 하나(`price_incl_vat`) · 공급가액·세액은 역산(D8 「부가세」) | 순액·총액 짝을 다 저장한다(`unit_price_net_amount`/`_gross_amount` 등, `SA:order/models.py:624-704`) + 세 전 `base_*` | 다르다·근거 있음 | 가격 하나 + 세금을 조정 행으로(`SO:app/models/spree/adjustment.rb:23`, `included` 플래그 `one_four:90`) | 다르다·근거 있음 | D8 「부가세」 — 값을 하나로 둔다. **행 밖 낱개**: 두 엔진 다 줄에 **세율을 박제**한다(`SA:order/models.py:726` `tax_rate` · `SO:adjustment.rb:51-60` `finalized`). 우리는 수수료율은 박제하고(D8 「요율」) 부가세율은 상수(`VatSplit.VAT_RATE_PERCENT`)로 역산해서, 세율이 바뀌면 과거 주문의 공급가액이 바뀐다 → **`Q320`** |
| 절사 방식 | 버림 — 물어 주는 돈만 올림(D8 「절사 > 방식」) | `quantize` 에 모드를 안 줘서 `prices` 의 기본 `ROUND_HALF_UP`(`SA:core/prices.py:21-26`), 퍼센트 쿠폰도 `ROUND_HALF_UP` 를 명시(`SA:discount/models.py:193`) | 다르다·근거 있음 | `round(2, ROUND_HALF_UP)` — 자리 2 가 통화와 무관하게 박혀 있다(`SO:app/models/spree/calculator/default_tax.rb:44-46`) | 다르다·근거 있음 | D8 그 절 — 한 방향으로만 어긋나야 검증이 쉽다 |
| 절사 단계 | 주문 항목마다(D8 「단계」) | 줄마다 자르고 주문 합계는 자른 줄의 합(`SA:tax/calculations/order.py:180-186`·`:94-108`) | 같다 | 세금을 항목(줄·배송)마다 자른다(`default_tax.rb:25-36`) | 같다 | 없음 |
| 합계 저장과 불변식 | 저장 + 지연 트리거(카탈로그 「합계를 저장하기로 했다」·「왜 지연 트리거인가」) | 저장하고 앱이 다시 센다 — 편집 가능한 상태에서만(`SA:order/calculations.py:71-88`), DB 제약·트리거 0 | 다르다·근거 있음 | 저장하고 `OrderUpdater#recalculate` 가 한 트랜잭션에서 다시 센다(`SO:app/models/spree/order_updater.rb:19-33`), DB 제약 0 | 다르다·근거 있음 | 카탈로그 그 절 — 앱 재계산은 새 입구가 빠뜨린다 |
| 할인 배분 | 항목마다 내림, 마지막 대상 항목이 잔차(D8 「어디에 적용되나」) | 기준가 비율로 나누고 **마지막 줄이 잔차**(`SA:order/base_calculations.py:237-262`), 줄마다 0 아래로 안 간다(`:211`) | 같다 | 할인 엔진이 core 밖 gem 이다. core 의 `DistributedAmountsHandler` 는 부르는 곳이 없다 | 범위 밖 | 없음. Saleor 는 고정 할인을 상품 합계와 배송비에 비율로 가른다(`:139-155`) — 묶음 3(거래)의 입력 |
| 배송비 | 상품 금액과 따로 · 셀러별(D8 「배송비」) | 주문에 하나(`SA:order/models.py:197-238`), 따로 과세 | 다르다·근거 있음 | 배송(shipment)마다 · 주문은 그 합(`order_updater.rb:157`) | 같다 | D8 그 절 — 멀티 셀러라 주문 하나에 배송비가 여럿이다 |
| 음수 | 자원마다 판단 · 정산 줄은 종류가 부호를 정한다(D8 「음수」·카탈로그 「부호를 종류가 정한다」) | 할인·환불은 양수로 따로 둔다(`SA:order/models.py:946-950`), 음수 결과는 0 으로 자른다 — 전부 앱 검증 | 다르다·근거 있음 | 조정 행에 부호가 있다(할인 `< 0`, `adjustment.rb:34-35`) · 환불은 양수 따로(`refund.rb:15`) | 같다 | D8·카탈로그 그 절 |
| 주문 시점 값 박제 | 상품명·가격·요율을 주문 항목에 박제(D4 「외래키를 건다」·D8 「요율」) | 이름·SKU·가격·세율을 줄에 박제(`SA:order/models.py:585-594`·`:726-741`) | 같다 | **가격만 박제** — 이름·설명·SKU 는 살아 있는 변형을 위임으로 읽는다(`line_item.rb:40`, 변형은 소프트 삭제 `:16`) | 다르다·근거 있음 | D4 그 절 — 박제는 표시용이라 SKU 가 없어도 주문이 읽혀야 한다 |
| 애그리거트 경계 | 주문 + 항목 · 배송지는 수명이 달라 따로(D4 「주문과 배송지는 한 덩어리가 아니다」) | 줄·할인은 cascade, 주소는 결제 완료 때 **주문용 사본**을 만든다(`SA:checkout/complete_checkout.py:214`), 결제는 `PROTECT` | 같다 | 주소가 **불변 행을 여러 주문이 같이 쓴다**(`SO:app/models/spree/address.rb:130-135`) | 다르다·근거 있음 | D4 그 절 — 배송지는 파기 대상이라 주문마다 따로 지워야 한다 |
| 수명/업무 상태 분리 | 둘을 섞지 않는다 · 주문에는 수명 컬럼이 없다(D4 「수명과 업무 상태를 섞지 않는다」) | 주문에 소프트 삭제 없음 · `status`·`authorize_status`·`charge_status` 가 따로(`SA:order/models.py:117-126`) | 같다 | 주문은 `state`·`payment_state`·`shipment_state`, `deleted_at` 은 상품·변형·가격에만(`one_four:370`·`:406`) | 같다 | 없음 |
| 참조·cascade | 외래키를 건다 · 안쪽만 cascade · 바깥은 restrict(D4 「참조 규칙」) | 줄 → 변형 `SET_NULL`(`SA:order/models.py:580`) · 주문 → 사용자 `SET_NULL`(`:137`) | 다르다·근거 있음 | 주문·줄·조정에 DB 외래키가 없고 Rails `dependent: :destroy` 로 지운다(`SO:app/models/spree/order.rb:93`) | 다르다·근거 있음 | D4 「외래키를 건다」 — 「어떤 SKU 였나」를 끝까지 따라간다 |

**셈**: 판정 스물여덟 — 같다 아홉 · 다르다·근거 있음 열여덟 · 범위 밖 하나 · 다르다·근거 없음 0 · 우리에 없음 0. 행 판정 밖의 낱개 하나(부가세율 박제 → `Q320`)는 셈에 안 넣었다.
**읽은 양**: Saleor 열한 자리 약 1100줄 · Solidus 열일곱 자리 약 900줄(상한 엔진당 40 파일·8천 줄 안). 받은 파일은 Saleor 1105 · Solidus 370 이고, 나머지는 `grep` 으로 자리만 찾았다.

**열넷 밖에서 본 것**(판정 칸에 안 셌다):

| 무엇 | 어디 | 우리 |
|---|---|---|
| Saleor 는 줄마다 값을 세 겹 둔다 — 세 전 `base_*` · 할인 전 `undiscounted_*` · 세 후 | `SA:order/models.py:624-723` | 주문 항목은 할인 전 `line_amount` 와 `discount_amount` 두 겹 — 세 전 값은 역산(카탈로그 「주문 > 한 행 안에서 끝나는 것」) |
| Solidus 는 완료 전까지 줄 가격을 살아 있는 가격으로 다시 읽는다(`recalculate_cart_prices` 5.0 부터 기본 참) | `SO:lib/spree/app_configuration.rb:284-286` | 주문을 만들 때 항목에 박제한다(D4 「외래키를 건다」). 장바구니 가격의 모양은 묶음 3(거래)의 입력 |

---

## 묶음 3 — 거래(`점검 AA`, 2026-10-30)

**짝**: Spree 5(`spree/spree` `adf2eda78925`, BSD-3 — `main` 이 지금 마켓플레이스다) · Medusa+Mercur 한 짝(`medusajs/medusa` `0ae4aede6684` 기본 가지 `develop`, MIT · `mercurjs/mercur` `b2153e02c989`, MIT). **셋 다 설계 해시(`d4fd2e9`·`f04fc3d`·`37b8284`)와 다르다** — 하루 사이 움직였고 받은 HEAD 다. 줄 번호는 이 해시 기준.
**표기**: `S:` 는 Spree 의 `spree/` 아래, `M:` 은 Medusa 의 저장소 루트 아래, `R:` 은 Mercur 의 `packages/core/src/` 아래 경로다.
**우리 쪽**: `state-machines.md`·`concurrency-rules.md`·`business-model.md` 의 절 이름으로 적는다. 쿠폰 부담 주체는 묶음 2 가 봤다.
**공식 문서**: 미열람 — **판정은 코드가 근거다.** 코드 지도는 서브에이전트 하나(읽기만)가 그렸고 인용 넷(`S:order.rb:475-496` · `S:commission_line.rb:1-20` · `R:payout-amounts.ts:247-276` · `M:status.ts:6-31`)을 원문으로 다시 댔다.
**두 짝 다 없는 것**: 구매확정 상태 · 자동확정 · 검수와 판정의 분리 · 법이 닫은 반품 사유 목록. 우리 쪽은 전부 법이나 정산 대상에서 나온 근거가 있다.

| 항목 | 우리 | Spree | S 판정 | Medusa+Mercur | M 판정 | 처분 |
|---|---|---|---|---|---|---|
| ① 주문 두 층 | `state-machines.md` 「주문은 두 층이다」 | `S:core/app/models/spree/order_group.rb:13-19` · `payment_split.rb:4-15` — 결제 한 건이 그룹에, 셀러별 자식 주문이 품목·배송을 든다 | 같다 | `R:modules/seller/models/order-group.ts:3-11` · `subscribers/split-order-payment-captured.ts:9-30` — 그룹이 장바구니 하나와 주문 N 개를 묶고 매입 뒤 주문별로 나눈다 | 같다 | 한 줄 |
| ② 결제 상태 | 「`order` 상태 — 결제」 넷(만료 포함) | `S:order.rb:13` · `payment.rb:118-124` — 결제 상태가 파생값이고 만료 상태가 없다 | 다르다·근거 있음 | `M:packages/modules/payment/src/models/payment-collection.ts:15-17` · `R:workflows/order-group/utils/aggregate-status.ts:9-21` — 주문별 결제 묶음 상태를 모아 파생한다, 만료 없음 | 다르다·근거 있음 | 「재고는 결제 전에 잡는다」 — 30분 만료가 재고를 돌린다 |
| ③ 셀러 주문 상태 | 「`seller_order` 상태 — 배송」 일곱 | `S:order.rb:17,175` · `fulfillment.rb:152` — `draft`·`placed`·`canceled` + 배송 롤업, 확정·반품 상태가 없다 | 다르다·근거 있음 | `M:packages/core/utils/src/order/status.ts:6-31` · `R:workflows/payout/utils/fulfillment-status.ts:27-33,49-72` — 여섯 값 + 배송 행에서 파생 | 다르다·근거 있음 | `confirmed` 는 정산 대상(`business-model.md` 「대상」)이고 `cancelled`·`return_requested` 는 배송완료 시각이 가른다 |
| ④ 부분 취소·반품 | 「주문은 두 층이다」 — 취소는 `seller_order` 통째 · 반품은 항목·수량(`return_request_item`, `V63`) | `S:api/app/controllers/spree/api/v3/seller/orders_controller.rb:60-89` · `seller/orders/returns_controller.rb:21-24` — 취소는 통째, 반품은 배송 품목·수량 단위 | 같다 | `M:packages/core/core-flows/src/order/workflows/cancel-order.ts:79-103` · `M:packages/modules/order/src/models/order-item.ts:13-19` — 취소는 통째, 반품은 품목별 수량 칸으로 부분 | 같다 | 한 줄. **우리 문서가 코드와 어긋나 있었다** — `state-machines.md` 「주문은 두 층이다」가 「취소·반품의 최소 단위는 `seller_order`」라 적었는데 `43` 이 반품을 항목·수량으로 세웠다. 그 문장을 고쳤다 |
| ⑤ 재고 예약·만료 | 「재고는 결제 전에 잡는다」 · 30분 · 5분 배치 | `S:stock_reservation.rb:50,67,105-106` · `stock_level.rb:205-215` — 장바구니가 TTL 예약 행을 잡고(기본 10분) 만료 작업이 돌린다 | 같다 | `M:packages/modules/inventory/src/services/inventory-module.ts:173-192` — 예약 때 가용을 보고 취소 때 지운다. 예약 시점·만료는 받은 범위 밖 | 범위 밖 | 한 줄 — 모양이 같고 단위(주문/장바구니)·시간만 다르다 |
| ⑥ 철회 기한 | `delivered` 때 철회 만료·자동확정 예정일 박제, 결제 때 `ship_due_at` · 응답 `withdrawalExpireAt`(`OrderQuery:143`) | `S:order.rb:475-496,512-518` · `api/app/serializers/spree/api/v3/order_serializer.rb:17,24-29` — **저장하지 않고 계산한다**(「clock starts at physical possession」), 마지막 소포 배송완료부터 시장별 일수(기본 14), 응답에 싣는다. 공휴일 이월·발송 기한 없음 | 다르다·근거 있음 | `withdraw`·`cooling`·`return_window`·`due_at` 0건 | 다르다·근거 있음 | `D10` 「계산한 기한은 박제한다」 — 우리도 배송완료 때 박제해 Spree 의 「배송 전에는 기한이 없다」와 시점이 같다. 응답에 싣는 것도 같다. **묶음 1 끝 표의 입력이 여기서 닫혔다** |
| ⑦ 반품 사유 | 「`delivered` → `return_requested` 는 사유로 갈린다」 — 법이 정한 둘, 제한은 항목에 박제 | `S:return_reason.rb:2-3` · `claim.rb:1-4,24-28` · `market.rb:50-61` — 상점이 정하는 이름 목록, 법정 철회기간과 상점 반품 기간을 따로 둔다 | 다르다·근거 있음 | `M:packages/modules/order/src/models/return-reason.ts:10-19` · `return-item.ts:17-19` — 계층형 자유 목록 | 다르다·근거 있음 | `coding-rules.md` 「법이 인정한 목록은 닫는다」 · 박제는 `Q5` |
| ⑧ 누가 옮기나·이력 | 「누가 옮기나」 — 셀러는 자기 것만, 판정은 셀러가 안 한다, 관리자 강제 전이, 상태 이력 | `S:seller/orders/returns_controller.rb:6-18` · `return.rb:26-27` — 셀러가 반품 승인·수령·환불까지 한다, 이력 표 없음 | 다르다·근거 있음 | `M:packages/modules/order/src/models/order-change.ts:7-29` · `R:workflows/order/workflows/confirm-return-receive.ts:399-406` — 변경 행이 판·요청자·확인자를 남긴다 | 다르다·근거 있음 | 「판정은 셀러가 안 한다」 — 제17조제5항 입증책임 · 「상태 이력을 남긴다」 |
| ⑨ 자동확정 | 「자동확정 8일」 — 철회 기간보다 짧을 수 없다 | 구매확정 개념 없음 | 다르다·근거 있음 | `M:packages/core/core-flows/src/order/workflows/complete-orders.ts:23-25,49-71` — `completed` 는 관리자가 부르는 수동 작업 | 다르다·근거 있음 | 정산 대상이 확정 주문이라 서야 한다(`business-model.md` 「대상」) · `D10` |
| ⑩ 반품 진행 | 「반품 상태 — 진행」 여섯 · 접수 취소 없음 | `S:return.rb:29-30,42-43` · `returns_controller.rb:31-37` — `requested`→`approved`→`received`→`refunded`·`canceled`, 수령 때 센 수량과 재판매 여부, 판정 단계 없음 | 다르다·근거 있음 | `M:status.ts:38-59` · `return/cancel-return.ts:74-93` · `R:confirm-return-receive.ts:190-201` — 수령 때 받은·훼손 수량, 수령분이 있으면 취소를 막는다, 검수·판정 분리 없음 | 다르다·근거 있음 | `inspected` 는 제17조제5항 · 접수 취소 없음은 제17조제4항·민법 제543조제2항(`43a-2`) |
| ⑪ 어긋남 방벽 | 「어긋남은 트리거가 막되 상태를 안 옮긴다」 | 마이그레이션이 받은 범위 밖 | 범위 밖 | 주문 모듈·Mercur 모듈에 트리거 0건 — 워크플로 검증이 든다 | 다르다·근거 있음 | `coding-rules.md` 축 2 — DB 제약이 앱 검증보다 아래 |
| ⑫ 재고 차감 | 「재고 차감 — 조건부 UPDATE」 | `S:stock_level.rb:117-121,245-258` — 수량은 `with_lock` 읽고 쓰기, 예약 수는 0 에서 멈추는 단일 UPDATE | 다르다·근거 있음 | `M:inventory-module.ts:126-134,973-1005` — 읽고 앱이 비교한 뒤 쓴다, 모듈 안 잠금 없음 | 다르다·근거 있음 | 「Read Committed 인데 왜 안전한가」 |
| ⑬ 재시도 | 「재시도 — 다시 해서 달라지는 것만 3회」 | `S:payment.rb:128-162` · `seller_payout.rb:101-120` — 실패 결제를 같은 행으로 다시 잡는다, 횟수 재시도 없음, 결과를 모르는 지급은 `unresolved` | 다르다·근거 있음 | `M:packages/modules/payment/src/services/payment-module.ts:88-94,1002-1033` — 환불 재시도가 같은 멱등키로 실패 행을 잇는다, 15분 안은 충돌 | 다르다·근거 있음 | 같은 키로 다시 부르는 것은 같다(「결제사 무응답에서 키를 새로 만들지 않는다」). 횟수·SQLSTATE 갈래는 우리 쪽 근거 |
| ⑭ 트랜잭션 경계 | 「트랜잭션 경계」 — 바깥 호출은 밖, 기록은 짧은 멱등 트랜잭션 | `S:return.rb:8-11` · `payment/processing.rb:53-69` · `refund.rb:113-126` — 게이트웨이 호출을 트랜잭션 밖에서 한다 | 같다 | `M:payment-module.ts:711-780,881-897,927-985` — 행을 먼저 쓰고 공급자 호출은 밖에서 멱등키로 | 같다 | 한 줄 |
| ⑮ 수수료 박제 | `business-model.md` 「언제 박제하나」 — 요율과 금액을 주문 시점에 | `S:commission_line.rb:4-19,31-40` — 요율·금액·세액·합계를 주문 때 박제, 요율을 고쳐도 안 바뀐다 | 같다 | `R:modules/commission/models/commission-line.ts:3-15` · `subscribers/order-edit-confirmed.ts:8-35` — 품목마다 두되 주문 수정·반품 수령 때마다 다시 계산한다 | 다르다·근거 있음 | 「언제 박제하나」 — 다시 계산하면 요율을 바꾼 날 과거가 바뀐다 |
| ⑯ 정산 | 「정산」 — 월 1회 · 구매확정만 · 지급액 식 | `S:seller_transfer.rb:4-8` · `seller.rb:54,376-377` — 출고 때 적립, 주기는 셀러별(일·주·격주·월·수동) | 다르다·근거 있음 | `R:workflows/payout/workflows/create-order-payouts.ts:40-45,87-93` · `utils/payout-amounts.ts:200-244` — 주문 하나에 지급 하나, 배송·매입이 끝나면 합계 − 수수료 | 다르다·근거 있음 | 「대상」 — 철회 기간이 지나야 확정이라 정산 뒤 환불이 준다 · 「주기」 — 월 1회라 두 번 돌아도 두 배가 안 되는지 잴 수 있다 |
| ⑰ 정산 후 환불 | 「환불」 — 수수료도 돌려준다 · 다음 정산 차감 · 음수 이월 | `S:seller_transfer.rb:17-19,31` · `seller_payout.rb:9-12` — 음수 반제 행, 닫힌 기간 뒤는 다음 기간으로. 수수료 몫은 계산 작업이 받은 범위 밖 | 같다 | `R:payout-amounts.ts:247-276` · `modules/payout/models/payout.ts:7-24` — 지급 뒤 환불은 「환불액 − 수수료 비례분」을 회수 행으로, 이월·음수 잔액 없음 | 다르다·근거 있음 | 「정산 후 환불」 — 회수 경로가 없는 경우를 음수 잔액으로 표현한다. 수수료를 돌려주는 것은 셋이 같다 |

**셈**: 판정 칸 서른넷 — 같다 9 · 다르다·근거 있음 23 · 다르다·근거 없음 0 · 범위 밖 2(⑤ M · ⑪ S) · 우리에 없음 0. 신규 행 0 — 대역 `Q331`~`Q340` 은 안 썼다.
**읽은 양**: Spree 약 25 파일·1800줄 · Medusa+Mercur 약 32 파일·1500줄(상한 짝당 50·1만 안).

### 열일곱 밖에서 눈에 띈 것

| 무엇 | 어디 | 우리 |
|---|---|---|
| Spree 는 결제 한 건의 자식 주문별 승인·매입·환불 몫을 행으로 남긴다(「비율은 부분 처리가 생기는 순간 깨진다」) | `S:payment_split.rb:4-15` | 환불은 `refund_item` 이 항목마다 든다. 결제는 주문에 한 건이라 몫을 가를 행이 없다 — 셀러별 부분 매입·취소가 생기는 날 다시 본다 |
| Spree 는 지급 결과를 모르면 `unresolved` 로 붙잡아 다음 스윕의 이중 지급을 막는다(공급자 멱등키 보존이 월 정산보다 짧다) | `S:seller_payout.rb:101-120` | 지급은 모의다(`business-model.md` 「정산 후 환불」). 묶음 5 ⑩ 이 봤다(2026-10-31 `점검 AD`) — 다르다·근거 있음 |
| Spree 는 수수료에 붙는 부가세를 따로 계산해 박제한다(`tax_amount`) | `S:commission_line.rb:16-19` | 수수료가 부가세 포함가다(`Q274`) — 세율·공급가액은 정산 마감 때 박제한다(`Q320`) |
| Spree 는 원물을 돌려받지 않는 해결(환불·대체품)을 반품과 다른 모델(`Claim`)로 둔다 | `S:claim.rb:1-4,28` | 교환은 「안 다루는 것」에 근거가 있다(`45`). 원물 없는 환불은 없다 |
| Mercur 는 셀러 지급 계좌가 없거나 꺼져 있으면 지급을 보류하고 다음 실행이 다시 판정한다 | `R:workflows/payout/utils/payout-amounts.ts:219-224` | 지급 대기는 「정산 지급 상태」(`21`). 묶음 5 ⑩ 이 봤다(2026-10-31 `점검 AD`) — 다르다·근거 있음 |

## 묶음 4 — 보안(`점검 AC`, 2026-10-31)

**짝**: Saleor(`saleor/saleor` `cc4aea76e397`, BSD-3 — 설계 해시 그대로) · Medusa(`medusajs/medusa` `da4480ae9a01` 기본 가지 `develop`, MIT) · Spree v3(`spree/spree` `4e46436f3363`, BSD-3). **Medusa·Spree 는 설계 해시(`12a55804efc9`·`8890e33bae96`)에서 움직여 받은 HEAD 를 적었다.** 받은 파일 Saleor 47 · Medusa 357 · Spree 442(희소 받기의 원뿔 모드가 상위 디렉터리 파일을 같이 받는다) — 판정에 연 파일은 엔진당 스물 안팎이다. Spree `core/lib/spree/core/configuration.rb` 하나는 받은 디렉터리 밖이라 같은 해시로 `gh api` 에서 읽었다.
**표기**: `A:` 는 Saleor 의 `saleor/` 아래, `M:` 은 Medusa 의 저장소 루트 아래, `S:` 는 Spree 의 `spree/` 아래 경로다.
**우리 쪽**: `security-baseline.md`·`permission-rules.md`·`media-rules.md` 의 절 이름으로 적는다.
**공식 문서**: 안 열었다 — **판정은 코드가 근거다.**
**보충**(`Q352`, 2026-10-31): 받지 않고 `gh api search/code` 로 범위 밖 다섯 칸(① S · ⑪ 셋 · ⑰ S)과 ⑮ 장수 상한을 봤다 — 검색은 기본 가지 인덱스라 찾은 파일은 위 해시로 읽었다. 검색 27회(분당 한도로 5회를 다시 냈다) · 읽은 파일 8.
**셋 다 없는 것**: 동시접속 제한 · 거부의 감사 기록. 원본 EXIF 를 안 지우는 것은 셋 다 확인됐다(Spree 는 보충 `Q352`). 우리 쪽은 규제 등급·법(안전성 확보조치 기준·개인정보법)에서 나온 근거가 있다.

| 항목 | 우리 | Saleor | A 판정 | Medusa | M 판정 | Spree | S 판정 | 처분 |
|---|---|---|---|---|---|---|---|---|
| ① 비밀번호 해시 | 「비밀번호 해시 — bcrypt」 cost 10 | `A:settings.py:263-267` — Django 기본 목록(PBKDF2 가 앞) + bcrypt + 자체 SHA512 PBKDF2 | 다르다·근거 있음 | `M:packages/modules/providers/auth-emailpass/src/services/emailpass.ts:51` — scrypt `logN 15, r 8, p 1` | 다르다·근거 있음 | `S:core/app/models/spree/customer.rb:13`·`S:core/app/models/spree/admin_user.rb:7` — `has_secure_password`(bcrypt, cost 는 안 정해 gem 기본값) · 최대 길이는 `S:core/lib/spree/core/configuration.rb:187` 이 bcrypt 72바이트 상수에서 받는다(보충 `Q352`) | 다르다·근거 있음 | 그 절 |
| ② 비밀번호 규칙 | 「비밀번호 규칙 — 길이만 본다」 15자·블록리스트 | `A:settings.py:477-480` — 최소 8, 검증기는 길이 하나 | 다르다·근거 있음 | `M:…/emailpass.ts:107` — 문자열인지만 본다 | 다르다·근거 있음 | `S:core/lib/spree/core/configuration.rb:184-187` · `core/app/validators/spree/password_length_validator.rb:13-19` — 최소 8·최대 72 | 다르다·근거 있음 | NIST SP 800-63B-4(표준) — 그 절 |
| ③ 세션 | 「세션 — Redis」 무활동 30분·절대 12시간·로그인 때 ID 재발급 | `A:settings.py:992-999` — JWT access 5분·refresh 30일 | 다르다·근거 있음 | `M:packages/core/framework/src/http/express-loader.ts:70-79,98-103` — `express-session` + Redis 10시간. 로그인이 세션 ID 를 안 간다(`M:packages/medusa/src/api/auth/session/route.ts:11`) | 다르다·근거 있음 | `S:api/lib/spree/api/configuration.rb:6-14` — JWT 1시간(관리자 5분)·refresh 30일 | 다르다·근거 있음 | `PLAN.md` 「인증은 세션 쿠키, JWT 아님」 · ID 재발급은 그 절 |
| ④ 쿠키 속성 | 「쿠키 속성」 `HttpOnly`·`SameSite=Lax`·`Secure`(배포) | `A:core/middleware.py:25-43` — refresh 토큰 쿠키 `httponly`·`secure`(DEBUG 밖)·`SameSite=None`(secure 일 때) | 다르다·근거 있음 | `M:…/express-loader.ts:37-41,70-79` — `lax`·`secure` 는 운영만 | 같다 | 쿠키 세션이 없다 — JWT 를 헤더로 싣는다(`S:api/app/controllers/concerns/spree/api/v3/jwt_authentication.rb`) | 범위 밖 | 그 절 |
| ⑤ 로그인 실패 제한 | 「로그인 실패 제한」 (계정, IP) 5회 / 15분 | `A:account/throttling.py:14-16,95-128` — IP 단위(10회마다)와 (IP, 계정) 단위(매회) 지연을 2배씩, 1초~3600초 | 다르다·근거 있음 | 없다 — 시도 수는 MFA 챌린지에만(`M:packages/modules/auth/src/services/auth-module.ts:457-514`) | 다르다·근거 있음 | `S:core/lib/spree/core/configuration.rb:178-179` · `core/app/models/concerns/spree/account_lockout.rb:24-27` — 계정만 5회 / 30분 잠금 | 다르다·근거 있음 | 그 절 — 계정만 잠그면 남의 계정을 잠그는 공격이 열린다 · NIST 5.2.2 |
| ⑥ 동시접속 | 「동시접속은 1개다」 | 없다 | 다르다·근거 있음 | 없다 | 다르다·근거 있음 | 없다 | 다르다·근거 있음 | 그 절 — `real-service-comparison.md` 「규제」 등급 |
| ⑦ 계정이 죽은 뒤 세션 | 「계정이 죽었는데 세션이 살아 있는 구간」 두 겹(ADR `0010`) | `A:core/jwt.py:125-136` — 요청마다 `is_active` 와 `jwt_token_key` 를 댄다 | 같다 | `M:packages/core/framework/src/http/middlewares/authenticate-middleware.ts:241-244` — 세션의 `auth_context` 를 다시 안 본다 | 다르다·근거 있음 | `S:api/app/controllers/concerns/spree/api/v3/jwt_authentication.rb:120-142` · `core/app/models/concerns/spree/account_deactivation.rb:21-40` — 요청마다 비활성·익명화를 보고, 비활성화가 토큰을 다 거둔다 | 같다 | ADR `0010` |
| ⑧ 응답 문구 | 「응답 문구는 계정 존재 여부를 안 흘린다」 — 시간도 | `A:account/throttling.py:47-52` · `graphql/account/mutations/authentication/create_token.py:53-61` — 한 문구, 없는 계정도 빈 `User` 로 해시를 돈다. 비활성·미확인 문구는 비밀번호가 맞은 뒤다 | 같다 | `M:…/emailpass.ts:121-144` — 한 문구지만 없는 계정은 해시 없이 끝나 시간이 갈린다 | 다르다·근거 있음 | `S:core/app/models/spree/authentication/strategies/email_password_strategy.rb:11-18` — 없는 계정은 해시 없이 끝나고, 잠긴 계정은 다른 문구(「Account temporarily locked」) | 다르다·근거 있음 | 그 절 |
| ⑨ 판정 모델 | 「판정 순서」 역할×권한, 스코프 `all`>`seller`>`own`, deny 먼저 | `A:permission/utils.py:12-31` · `account/models.py:468-469` — 권한 전부 충족(allow 만), 그룹을 채널로 좁힌다 | 다르다·근거 있음 | `M:packages/modules/rbac/src/models/rbac-policy.ts:5-8` · `M:…/middlewares/check-permissions.ts:29-52` — `resource:operation` allow 만, 역할 상속 | 다르다·근거 있음 | `S:core/app/models/spree/ability.rb:104-105` · `role_user.rb:3,23` — `can`·`cannot`(마지막에 맞는 규칙이 이긴다), 역할에 스토어·셀러 범위 | 다르다·근거 있음 | `permission-models.md` 가 모델을 고른 근거 · 그 절 |
| ⑩ 필드 그룹 | 「필드 그룹」 마스킹 + `_visible_field_groups` | GraphQL 필드 권한은 타입 정의에 있고 그 디렉터리를 안 받았다 | 범위 밖 | `M:packages/core/framework/src/http/utils/policies/rbac-field-filter.ts:404-440` — 관계마다 읽기 권한으로 필드를 거르고, 거른 것을 응답에 안 밝힌다 | 다르다·근거 있음 | `S:api/app/controllers/concerns/spree/api/v3/admin/embed_permissions.rb` 머리 주석 — `expand` 경로 마디마다 키, 기프트카드 코드 마스킹, 밝히는 칸 없음 | 다르다·근거 있음 | 그 절 |
| ⑪ 판정을 안 지나는 입구 | 「판정을 안 지나는 입구」 `ArchitectureTest` 박제 | 목록을 박제한 시험이 없다 — 권한은 뮤테이션마다 `Meta.permissions` 로 적고(`A:graphql/core/mutations.py:195,499-524`) 익명 요청은 기능 시험이 하나씩 본다(`A:graphql/core/tests/test_graphql.py:55`) | 다르다·근거 있음 | 목록을 박제한 시험이 없다 — 라우트마다 `allowUnauthenticated: true` 를 적고(`M:packages/medusa/src/api/store/products/middlewares.ts:70-71,103-104`) 통합 시험은 그 끝점을 하나씩 본다(`M:integration-tests/http/__tests__/auth/providers.spec.ts` 등 셋) | 다르다·근거 있음 | 목록을 박제한 시험이 없다 — 컨트롤러마다 `require_authentication!` 을 부르고(`S:api/app/controllers/concerns/spree/api/v3/jwt_authentication.rb:40-47`) 시험에 인증을 건너뛰는 목록 검색 0 | 다르다·근거 있음 | 그 절 — 셋 다 입구마다 선언이라 새 입구가 선언을 빠뜨려도 시험이 안 잡는다. 우리는 목록을 `ArchitectureTest` 로 박제한다(사용자 결정 2026-09-14). 보충 `Q352` |
| ⑫ 거부 기록 | 「판정 결과의 종류」 `decide` 만 감사 로그에 | `A:graphql/core/mutations.py:499-524` — `PermissionDenied` 를 던지기만 | 다르다·근거 있음 | `M:…/check-permissions.ts:42-52` — `FORBIDDEN` 을 던지기만 | 다르다·근거 있음 | `S:api/app/controllers/concerns/spree/api/v3/error_handler.rb:109-110` — 응답만 | 다르다·근거 있음 | 접속기록(안전성 확보조치 기준 — 법) |
| ⑬ 요청 제한 | `RateLimitFilter` 주소 단위 1분 창, 인증 경로 20·나머지 120, 429 + `Retry-After` 60, 남은 양 헤더 없음 | 없다(로그인 지연 가중만, ⑤) | 다르다·근거 있음 | 없다(`modules/search/src/utils/rate-limit.ts` 는 검색 공급자 호출용) | 다르다·근거 있음 | `S:api/lib/spree/api/configuration.rb:17-23` · `api/app/controllers/concerns/spree/api/v3/rate_limit_headers.rb:39-61` — 공개 키+IP 300·비밀 키 600 / 분, 로그인 5·가입 3·재설정 3 / IP. `X-RateLimit-Limit`·`X-RateLimit-Remaining` 을 모든 응답에, 넘으면 `Retry-After` | 다르다·근거 있음 | 값의 근거가 `RateLimitFilter` Javadoc 에만 있었다 → `security-baseline.md` 「요청 제한」 절로 올렸다. **헤더는 우리에 없음 · 1곳**(Spree) — 행을 안 세운다(2026-10-30 사용자). IETF `draft-ietf-httpapi-ratelimit-headers` -11(2026-05-23)은 RFC 가 아니다 — RFC 가 되면 「바깥 근거」 점검이 본다 |
| ⑭ 프록시 뒤 IP | 「프록시 뒤의 클라이언트 IP」 `native` · `X-Real-IP` · 사설·루프백 신뢰 | `A:settings.py:828-830` — 환경변수로 헤더 하나(기본 `REMOTE_ADDR`), 신뢰 대역 없음 | 다르다·근거 있음 | `M:…/express-loader.ts:109` — `trust proxy 1`(한 홉 `X-Forwarded-For`) | 다르다·근거 있음 | `S:…/rate_limit_headers.rb:45-47` — Rails `request.remote_ip` | 다르다·근거 있음 | 그 절(`Q242` — 앞단이 적는 헤더) |
| ⑮ 제한값 | 「받는 것 — 제한값」 5 MiB·10장·JPEG·PNG | `A:settings.py:500-509,1257` — 10 MB · 3천만 화소 · 이미지 MIME 일곱 | 다르다·근거 있음 | 크기 상한 없음(`M:packages/medusa/src/api/admin/uploads/middlewares.ts:14` 메모리 저장) · MIME 목록 없음 | 다르다·근거 있음 | `S:core/lib/spree/core/configuration.rb:116,125` — 직접 업로드 5 GB · 동영상 500 MB | 다르다·근거 있음 | 관례 값이라 **수를 셌다** → `media-rules.md` 「받는 것」에 적었다. 결정은 안 바꾼다. 장수 상한은 셋 다 없다 — 전체 검색(`Q352`, 2026-10-31 — `max_images`·`MAX_IMAGES`·`maxImages`·`images_count`·`max_media`)에도 0 |
| ⑯ 판별 순서 | 매직 바이트 → 확장자 짝 → 크기, 413/415/422 | `A:graphql/core/validators/file.py:26-66,111` — 매직 바이트 + 확장자 짝 + 크기. 오류는 GraphQL 오류 하나라 상태 코드가 없다 | 같다 | `M:packages/medusa/src/api/admin/uploads/route.ts:26` — 보낸 쪽 MIME 을 그대로 쓴다 | 다르다·근거 있음 | `S:core/app/validators/spree/attachment_content_type_validator.rb:1-27` — 매직 바이트(Marcel)는 낮은 신뢰 업로드(구매 주문서·셀러 서류)에만, 직접 업로드는 선언한 `content_type`·`byte_size`(`core/app/models/spree/file_upload.rb:19-37`) | 다르다·근거 있음 | 「클라이언트가 보낸 `Content-Type` 을 안 믿는다」(OWASP) |
| ⑰ EXIF | 「EXIF 를 지운다」 저장 전 다시 인코딩 | `A:thumbnail/utils.py:199-243` — 썸네일은 EXIF 를 안 실어 빠지지만 원본은 받은 대로 두고 연다(`:60-70` 「original-image」) | 다르다·근거 있음 | 지우는 코드 없음 | 다르다·근거 있음 | `S:core/app/models/spree/media.rb:107-130` — 파생본(WebP)은 `saver: { strip: true }` 로 메타데이터를 지우지만 원본 첨부(`:116`)는 받은 대로 둔다(보충 `Q352`) | 다르다·근거 있음 | 그 절 — 개인정보(법) |
| ⑱ 키·여는 법 | 「두는 곳」·「여는 법」 키에 원본 이름·순번 없음, 공개 5분 서명·비공개 판정 프록시 | `A:thumbnail/utils.py:113-116` — 파일 이름이 원본 이름에서 나온다, 이미지는 앱 프록시 주소(`:35-70`) | 다르다·근거 있음 | `M:packages/modules/providers/file-s3/src/services/s3-file.ts:122,196-200` — 키 `{prefix}{원본 이름}-{ulid}{확장자}`, 서명 URL 1시간, 공개·비공개 ACL | 다르다·근거 있음 | `S:core/app/models/spree/file_upload.rb:22,34` · `core/app/models/concerns/spree/signed_id_attachments.rb:2-7` — 공개·비공개 갈래, 첨부는 서명 ID 로 잇는다 | 같다 | 그 절 |

**판정 쉰넷**: 같다 6 · 다르다·근거 있음 46 · 범위 밖 2(④ S 쿠키 세션 없음 · ⑩ A GraphQL 타입 — 보충 `Q352` 가 다섯을 판정했다) · 근거 없이 다른 것 0 · 우리에 없음 0(헤더 하나는 1곳). **신규 행 0** — `Q341`~`Q348` 은 안 썼다. 근거가 문서 밖에 있던 둘(⑬ 값은 Javadoc, ⑮ 값은 「안 셌다」)을 기준 문서로 올렸다.

### 열여덟 밖에서 눈에 띈 것

| 무엇 | 어디 | 우리 |
|---|---|---|
| Saleor 는 디코딩 화소 수를 따로 막는다 — 파일 크기 상한은 디코딩 메모리를 못 막는다(8000×8000 WebP 예) | `A:settings.py:505-509` | **`Q351` 이 3천만으로 세웠다(2026-10-31) — 값의 근거는 힙**(기본 최대 힙 2 GB, 8 B/화소 × 3천만 = 240 MB). 단색 5 MiB PNG 는 약 13억 화소로 풀린다. 막는 자리는 `ImagePipeline` 의 헤더 폭×높이 검사(`ImageIO.read` 앞, 413) |
| Spree 는 관리자 JWT 를 5분으로 손님(1시간)보다 짧게 둔다(「관리자 토큰은 폭발 반경이 크다」) | `S:api/lib/spree/api/configuration.rb:7` | 세션 만료는 역할로 안 가른다(「세션 — Redis」). 재인증은 「동시접속은 1개다」가 「아직 안 한다」로 적었다 |
| Spree 는 요청 본문을 100KB 로 막는다 | `S:api/lib/spree/api/configuration.rb:26` | 본문 상한은 이 묶음의 자리가 아니다 — 업로드는 ⑮ 가 든다 |

---

## 묶음 5 — 이벤트·배치·알림·데이터 수명(`점검 AD`, 2026-10-31)

**짝**: Medusa(`medusajs/medusa` `7e797dfd591d` 기본 가지 `develop`, MIT) · Saleor(`saleor/saleor` `cc4aea76e397`, BSD-3 — 묶음 4 와 같은 해시). 둘 다 설계 해시 그대로다. 받은 파일 Medusa 166(1.9만 줄) · Saleor 95(2.5만 줄), 판정에 연 파일 Medusa 23(약 2,450줄) · Saleor 27(약 2,700줄) — 상한(엔진당 40·8천) 안이다. 코드 판독은 엔진마다 서브에이전트 하나가 했고 자리 넷을 손으로 다시 봤다.
**표기**: `M:` 은 Medusa 의 저장소 루트 아래, `A:` 는 Saleor 의 `saleor/` 아래 경로다.
**우리 쪽**: `event-catalog.md`·`batch-catalog.md`·`notification-rules.md`·`data-lifecycle.md` 의 절 이름으로 적는다.
**공식 문서**: 안 열었다 — **판정은 코드가 근거다.**
**축이 다르다**: Medusa 의 배치는 잡 로더 + 워크플로 + BullMQ 반복 잡이고 실행 이력은 `workflow_execution` 이다 — ⑨~⑬ M 칸은 그 기준으로 적었다. Saleor 의 이벤트는 외부 앱으로 가는 웹훅 배송이라(행을 만들자마자 Celery 로 보낸다) ④~⑦ A 칸은 「아웃박스 → Kafka → 내부 소비자」와 운반 축이 다르다.
**둘 다 없는 것**: 원천과 같은 트랜잭션의 아웃박스 · 실행 회차 장부(중복 방지·체인) · 배치 분산 잠금 · 거래 통지와 광고의 구분 · 자원별 보존 표(Saleor 는 설정 상수 몇 개뿐). 우리 쪽 근거는 `D11`(트랜잭션 밖 호출 없음)·`D19`·법(정보통신망법 · 전자상거래법 시행령 6조 · 개인정보법)이다.

| 항목 | 우리 | Medusa | M 판정 | Saleor | A 판정 | 처분 |
|---|---|---|---|---|---|---|
| ① 이벤트 이름 | 「이벤트 이름과 원천」 `shop.<자원 단수>.<과거형>`, 원천 일곱의 트리거, 닫힌 목록 | `M:packages/core/utils/src/event-bus/utils.ts:49-60,79-101` — `[<모듈>.]<엔티티>.<동작>`, 동작 여섯(created·updated·deleted·…), 닫힌 목록 없음 | 다르다·근거 있음 | `A:webhook/event_types.py:24-174` — `<자원>_<과거분사>` 비동기 147 + 동기 20, `EVENT_MAP` 이 이름·권한을 든다 | 다르다·근거 있음 | 그 절 — 모양(자원 + 과거형)은 셋이 같다. 원천을 트리거로 두고 목록을 DB 로 닫는 것이 우리 몫이다(앱 검증보다 낮은 층) |
| ② 봉투 | 「봉투」 CloudEvents 1.0 structured, `id`·`source`·`time` | `M:packages/core/utils/src/event-bus/build-event-messages.ts:36-50` — `{name, data, metadata}`, 이벤트 id 없음 | 다르다·근거 있음 | `A:webhook/transport/utils.py:230-243` — JSON 본문 + 헤더 넷(종류·도메인·서명·API), 서명 HMAC-SHA256 | 다르다·근거 있음 | 그 절 — 표준(2순위)이 든다. 짝 둘은 자기 꼴이다 |
| ③ 페이로드 | 「페이로드 규칙」 식별자만, 원천 행 시각 | `M:packages/medusa/src/subscribers/configurable-notifications.ts:26-29,55-65` — `data` 자유, 알림 구독자가 `order.email` 을 읽는다, 시각은 emit 시각 | 다르다·근거 있음 | `A:webhook/payloads.py:242-331,602-639` — 객체를 통째 직렬화(`user_email`·이름·주소), `meta.issued_at` 은 생성 시각 | 다르다·근거 있음 | 그 절 — 개인정보는 식별자만(`D16`·법). 원천 시각은 재시도해도 사건 시각이 안 바뀌게 하려는 것이다 |
| ④ 아웃박스 | 「아웃박스 표」 원천과 같은 트랜잭션, 트리거만 쓴다 | `M:packages/modules/event-bus-redis/src/services/event-bus-redis.ts:280-299,351-410` — 표 없음, 워크플로 이벤트를 Redis `staging:<group>`(TTL 600초)에 모았다 성공 뒤 큐에 넣는다 | 다르다·근거 있음 | `A:webhook/transport/asynchronous/transport.py:375-381,406-415` — `EventPayload`+`EventDelivery` 를 **원천과 별도인** `atomic()` 에서 만든다, 원천 트랜잭션 안이면 경고만 | 다르다·근거 있음 | 그 절 — 둘 다 원천 커밋과 발행 기록이 갈릴 틈이 있다. 우리는 그 틈을 트리거로 닫았다 |
| ⑤ 발행기 | `batch-catalog.md` 「발행기만 잠금이 다르다」 2초 `fixedDelay` · 200건 · `skip locked` · ack 뒤 표시 | `M:…/event-bus-redis.ts:154-162,244-264` — 폴러 없음, `emit` 이 곧 BullMQ `addBulk`, 기본 `attempts: 1` | 다르다·근거 있음 | `A:webhook/transport/asynchronous/transport.py:491-510` — 폴러 없음, 배송 행마다 Celery 태스크를 바로 건다 | 다르다·근거 있음 | 그 절 — 폴링은 ④ 의 결과다. **2초는 짝이 없다**(둘 다 즉시) — `batch-catalog.md` 에 셈을 적었다 |
| ⑥ 전송 | 「전송 — Kafka」 토픽 하나·키 `subject`·`acks: all` | `M:…/event-bus-redis.ts:84-108` — BullMQ(Redis) 큐 `events-queue`, 개발용은 프로세스 안 EventEmitter | 다르다·근거 있음 | `A:webhook/models.py:12-15` — http·https·SQS·Pub/Sub 넷, 타임아웃 연결 2초 + 응답 18초 | 다르다·근거 있음 | 그 절 — 사용자 결정(Kafka, 웹훅도 소비자) |
| ⑦ 소비자 | 「소비자」 시도 3(`FixedBackOff(0,2)`) · DLQ 없음 · 5분 스위퍼 | `M:…/event-bus-redis.ts:417-521` — 시도 기본 1, 늘리면 실패한 구독자만 다시, DLQ 없음 | 다르다·근거 있음 | `A:webhook/transport/asynchronous/transport.py:804-809` — 받는 쪽은 바깥 앱, 5xx 면 재시도 5(10·2^n 초), 소진하면 `FAILED`, DLQ 없음 | 다르다·근거 있음 | 그 절 — DLQ 없음은 셋이 같다. 시도 수는 Medusa 1 · Saleor 6 · 우리 3 |
| ⑧ 버전 | 「버전」 `type` 안 바꿈, `data` 더하기만, 레지스트리 없음 | 버전·레지스트리 없음(이벤트 버스 세 디렉터리 grep 0) | 다르다·근거 있음 | `A:webhook/payload_helpers.py:25` — `meta.version` 에 제품 판, 옛 이름은 `deprecated_event_types.py` 로 남긴다 | 다르다·근거 있음 | 그 절 — 레지스트리 없음은 셋이 같다 |
| ⑨ 카탈로그 | 「카탈로그」 열넷 · cron `Asia/Seoul` 04:00 대 · 등록 한 곳 · 표↔코드 대조 시험 | `M:packages/core/framework/src/jobs/job-loader.ts:24-28,69-93` — 잡 파일마다 `config.schedule`, BullMQ 에 `tz` 안 넘김, 대조 시험·손 실행 입구 없음 | 다르다·근거 있음 | `A:settings.py:673-769` — `CELERY_BEAT_SCHEDULE` 한 곳(스물), `TIME_ZONE="UTC"`, 00:00~03:00 UTC | 다르다·근거 있음 | 그 절 — 등록이 한 곳인 것은 Saleor 와 같다. 시각은 `D10`(04:00 KST) |
| ⑩ 재실행 안전성 | 「재실행 안전성」 수단 넷 + 금액 배치는 `batch_run` 부분 유니크 | `M:packages/core/framework/src/jobs/job-loader.ts:113-119` — 예약 실행에 트랜잭션 id 를 안 준다, 멱등은 TODO | 다르다·근거 있음 | `A:order/tasks.py:144-171,182-226` — 조건으로 다시 고르고 배치 크기로 나눠 자기를 다시 건다, 장부 없음 | 같다 | 그 절 — Saleor 는 우리 수단 1(조건이 곧 처리 조건)과 같고 금액 배치가 없다. **묶음 3 입력 둘**: Spree `unresolved`(지급 결과를 모르면 붙잡는다)·Mercur 지급 보류(계좌 없음) — 우리 지급은 배치가 안 하고 관리자 둘이 `requested`→`paid` 로 올린다(`state-machines.md` 「정산 지급 상태」, `21`). 결과 모름은 지급이 모의라 자리가 없고 음수는 이월이다(`business-model.md` 「정산 후 환불」) — 다르다·근거 있음 |
| ⑪ `batch_run` | 「실행 이력」 시도마다 한 행 · 성공 부분 유니크 · 1년 | `M:packages/modules/workflow-engine-redis/src/models/workflow-execution.ts:4-58` — `workflow_execution`, `retention_time` 기본 없음(없으면 끝나자 지운다), 정리 30분 | 다르다·근거 있음 | `A:schedulers/models.py:84` — 회차 표 없음, `last_run_at`·`total_run_count` 덮어쓰기 | 다르다·근거 있음 | 그 절 — 1년의 짝: Medusa 기본 없음(BullMQ 기록 성공 1일·실패 7일) · Saleor 없음 |
| ⑫ 실패 3층 | 「실패 처리」 건·회차(SQLSTATE, 10분 간격 재시도 2회, 창 24시간)·체인 | `M:packages/core/orchestration/src/transaction/types.ts:44-60` — step `maxRetries` 기본 0·간격 즉시, SQLSTATE 구분·체인 없음 | 다르다·근거 있음 | `A:core/tasks.py:65-92` — beat 태스크 재시도 없음, 실행 상한 1시간, 체인 없음 | 다르다·근거 있음 | 그 절 — **10분·24시간은 짝이 없다** — `batch-catalog.md` 에 셈을 적었다 |
| ⑬ 잠금 | 「서버가 여럿이 될 때」 `pg_try_advisory_lock` 세션 잠금 | `M:packages/modules/workflow-engine-redis/src/utils/workflow-orchestrator-storage.ts:805-808` — cron 잡에 분산 잠금 없음(반복 잡 키 하나에 기댄다), `locking` 모듈은 받은 디렉터리에 쓰는 곳 없음 | 다르다·근거 있음 | `A:settings.py:755-761` — beat 항목에 잠금 없음, 짧은 주기는 `expires` 로 밀린 것을 버린다 | 다르다·근거 있음 | 그 절(`Q50`) — 배포 겹침 창이 근거다 |
| ⑭ 두 종류·채널 | 「두 종류를 먼저 가른다」 거래 vs 광고(동의·야간) · 이메일 하나 | `M:packages/modules/notification/src/loaders/providers.ts:60-83` — 구분·동의·야간 없음, 채널 자유 문자열·채널마다 공급자 하나 | 다르다·근거 있음 | `A:core/notify.py:22-75` — 사용자 15·관리자 5 종 다 거래 통지, 광고·동의 0, SMTP 플러그인 | 다르다·근거 있음 | 그 절 — 법(정보통신망법 제50조) |
| ⑮ 템플릿·이력 | 「템플릿은 판과 시행일로」·「발송 이력」 `(code, version)` · 본문 별표 | `M:packages/modules/notification/src/models/notification.ts:5-37` — 템플릿은 이름 문자열, 이력 한 행에 `data` 가 같이 든다, 상태 셋 | 다르다·근거 있음 | `A:plugins/email_common.py:369-405` — 이벤트마다 설정 필드 하나(`EmailTemplate` 이름), 판·시행일 없음, 이력은 `EMAIL_SENT` 메타만·본문 안 남김 | 다르다·근거 있음 | 그 절 — 상태 셋은 Medusa 와 같다 |
| ⑯ 한 번만 + 보존 | 「같은 사건에 한 번만」 대상별 부분 유니크 넷 · 「보존 기간」 | `M:packages/modules/notification/src/services/notification-module-service.ts:106-138` — `idempotency_key` 유니크 하나 + 앞서 조회(경합 TODO), 보존 없음 | 다르다·근거 있음 | `A:plugins/user_email/tasks.py:169-187` — 중복 방지 없음, 보존 값 없음 | 다르다·근거 있음 | 그 절 — 유니크로 막는 것은 Medusa 와 같다. 보존은 법(시행령 6조) |
| ⑰ 실패·재시도·거부 불가 | 「실패와 재시도」(일시적 재전송은 지금 안 한다) · 「거부할 수 없는 것」 | `M:packages/modules/notification/src/services/notification-module-service.ts:184-233` — 실패면 `failure`, 구분·자동 재전송 없음, 같은 키로 다시 부르면 실패 건만 | 다르다·근거 있음 | `A:plugins/user_email/tasks.py:11-301` — 재시도 없음(SMTP 5초), 수신 거부 0 | 다르다·근거 있음 | 그 절 — 자동 재전송이 없는 것은 셋이 같다(`Q353` 이 우리 쪽을 「지금 안 한다」로 적었다) |
| ⑱ 지우는 방식·자원별 | 「지우는 방식 세 가지」·「자원별 정책」·「보존분 분리」 | `M:packages/core/utils/src/dal/mikro-orm/mikro-orm-soft-deletable-filter.ts:7-18` — `deleted_at` 뿐, 보존 표 없음 | 다르다·근거 있음 | `A:settings.py:614-628,776-778` — `timedelta` 수명(비회원 체크아웃 30일 · 페이로드 14일 · …), 물리 삭제, `User` 에 `deleted_at` 없음 | 다르다·근거 있음 | 그 절 — 법(5년·3년). 비회원 장바구니 30일은 Saleor 비회원 체크아웃 30일과 같다 |
| ⑲ 탈퇴·파기·감사 | 「탈퇴」·「파기 시점」·「감사 로그는 예외다」 | customer·user·auth 를 안 받았다 | 범위 밖 | `A:account/models.py:172,179,404-406` — 이메일 전체 유니크, 삭제하면 고객 이벤트가 `CASCADE` 로 같이 지워진다, 유예·파기 시점 값 없음 | 다르다·근거 있음 | 그 절 — ADR `0007` · 표준 개인정보 보호지침 10조① |

**판정 칸 서른여덟**: 같다 1 · 다르다·근거 있음 36 · 범위 밖 1 · 근거 없이 다른 것 0 · 우리에 없음 0. **신규 행 0** — `Q355`~`Q363` 은 안 썼다. 근거가 문서에 없던 값 둘(발행 2초 · 회차 재시도 10분 간격)은 `batch-catalog.md` 에 「내부 편의 값」으로 적고 남의 수를 붙였다 — 짝 둘 다 그 자리가 없어(0곳) 「설계 필요」 행이 안 선다. ⑤·⑫ 의 「근거 있음」은 폴링·재시도 층 자체의 근거다. 값은 안 바꾼다(관례는 세는 참고).

**내부 편의 값의 짝**:

| 우리 | Medusa | Saleor |
|---|---|---|
| 발행 2초 | 없다 — 즉시 큐 | 없다 — 즉시 Celery |
| 소비자 시도 3 | 1 | 6(10·2^n 초) |
| 회차 재시도 10분·창 24시간 | 재시도 기본 0·즉시 | beat 재시도 없음 |
| 아웃박스 7일 | 잡 완료 즉시 지움 | 성공 배송 즉시, 페이로드 14일 |
| `batch_run` 1년 | `retention_time` 기본 없음(BullMQ 기록 1일·7일) | 없다 |
| 멱등키 24시간 | 알림 키 만료 없음 | 없다 |
| 본문 6개월 | 기간 없음(같은 행에 영구) | 본문을 안 남긴다 |
| 04:00 실행 | `tz` 를 안 넘긴다 | UTC 00:00~03:00 |

### 열아홉 밖에서 눈에 띈 것

| 무엇 | 어디 | 우리 |
|---|---|---|
| Saleor 는 동기 웹훅에 서킷 브레이커를 둔다(창 5분 · 실패 100건 + 35% 면 열림 · 냉각 2분, 기본 꺼짐) | `A:webhook/circuit_breaker/breaker_board.py:28-46` · `A:settings.py:1197-1213` | 우리 웹훅(`30`)은 비동기 하나라 자리가 없다. 1곳 |
| Medusa 의 `locking` 모듈은 Postgres 공급자가 `pg_advisory_xact_lock`(대기 5초)을 쓴다 — 우리 `Q50` 과 같은 수단이지만 잡에는 안 걸었다 | `M:packages/modules/providers/locking-postgres/src/services/advisory-lock.ts:41,50` | 같은 수단이다. 우리는 세션 잠금(회차 내내)이고 저쪽은 트랜잭션 잠금이다 |
