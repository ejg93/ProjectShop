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
| Spree 는 요청 제한 헤더를 `X-RateLimit-Limit`·`X-RateLimit-Remaining` 으로 모든 응답에 싣는다(`rate_limit_headers.rb:5`) | Medusa 범위 밖 | `X-` 는 RFC 6648 로 막는다(D5 「헤더」). 남은 양을 싣는 칸은 없다 — **묶음 4(보안)** 가 요청 제한 줄에서 본다 |
| Spree 주문에 철회 기한 칸이 있다(`order_serializer.rb:17` `withdrawal_period_ends_at`·`within_withdrawal_period`) | — | 청약철회(R3) 기한을 응답에 싣나 — **묶음 3(거래)** 의 입력 |
</content>
</invoke>

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
