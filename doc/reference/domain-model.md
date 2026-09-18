# 도메인 모델

무엇이 무엇을 참조하고, 무엇이 같이 태어나고 같이 죽는지 정한다.
지우는 정책은 `data-lifecycle.md`(D13)에 있다. 이 문서는 구조다.

## 수명과 업무 상태를 섞지 않는다

**이 문서에서 제일 중요한 규칙이다.**

자원에 상태가 둘 있는데 성격이 다르다.

| 축 | 예 | 컬럼 |
|---|---|---|
| 업무 상태 | 판매중 / 품절 / 판매중지 | `status` |
| 수명 | 존재함 / 삭제됨 | `deleted_at` |

**둘은 서로 독립이다.** "품절이면서 삭제됨" 이 성립한다. 한 컬럼에 넣으면 표현이 안 된다.

### 섞으면 무너지는 것

**복구할 때 이전 상태를 잃는다.**

```
상품이 '품절' → 삭제 → status = 'deleted'
                          ↑ '품절' 이 덮여서 사라졌다
복구하면 뭘로 돌아가나. 알 방법이 없다
```

**살아 있는 것을 고르는 조건이 흔들린다.**

```sql
where status = 'active'      -- 품절 상품이 빠진다. 의도와 다르다
where status != 'deleted'    -- 맞지만 상태가 늘 때마다 이 조건을 다시 봐야 한다
where deleted_at is null     -- 업무 상태가 몇 개로 늘든 안 바뀐다
```

### 이미 섞여 있던 것을 고쳤다

`app_user.status` 의 `withdrawn` 과 `seller.status` 의 `closed` 를 `deleted_at` 으로 옮겼다.
`suspended`(정지)는 복구되는 업무 상태라 `status` 에 남는다.

**두 컬럼은 각 테이블의 `create` 문에 있다**(`V2`·`V4`). 처음에는 별도 마이그레이션이
`alter` 로 붙였는데, 테이블 모양이 두 파일로 갈려서 `create` 로 합치고 그 파일을 지웠다(`D23`).

### 수명 컬럼을 안 두는 자원

삭제 개념이 없는 자원에는 안 둔다. 전역으로 강제하면 모든 조인에 조건이 붙고 하나 빠뜨리면 지운 데이터가 샌다.

| 자원 | 수명 컬럼 | 왜 |
|---|---|---|
| `app_user`, `seller` | 있다 | 탈퇴·폐업이 있다 |
| `product`, `sku` | 있다 (청크 6) | 셀러가 상품을 내린다 |
| `order`, `payment` | **없다** | 5년 보존이라 지우는 개념이 없다 |
| `cart`, `cart_item` | **없다** | 그냥 지운다 |
| `role`, `permission` | **없다** | 관리 데이터다. 지울 일이 생기면 그때 판단한다 |
| `refund`, `return_request`, `compensation`, `settlement` 넷 | **없다** | 보존 기간(환불·반품은 거래 종료 5년, 정산·배상은 세법 기산 — `43a-28`)이 차면 파기 배치가 통째로 지운다. 되살릴 일이 없어 표시할 것이 없다(`D13`) |
| `inquiry`, `notification` | **없다** | 기간이 차면 물리 삭제다. 되살릴 일이 없어서 표시할 것이 없다 |

## 애그리거트

같이 태어나고 같이 죽는 덩어리다. 경계가 정해지면 `on delete` 규칙과 트랜잭션 범위가 따라 나온다.

```
Seller ─┬─ SellerMember          소속. 셀러가 사라지면 의미가 없다
        └─ Product ─┬─ ProductOption
                    └─ Sku

Order ──┬─ OrderItem              주문 없이 항목이 의미가 없다
        └─ OrderShippingAddress   수명이 다르다. 아래 참조

AppUser ─── UserRole              계정이 사라지면 역할 부여도 사라진다
```

### 상품은 한 덩어리다

`Product` · `ProductOption` · `Sku` 가 같이 산다. 상품을 내리면 그 상품의 SKU 도 같이 내려간다.

SKU 를 독립 개체로 볼 수도 있었다. 재고가 붙고 주문이 직접 참조하기 때문이다.
그런데 SKU 는 **상품 없이 존재할 이유가 없다.** "검정 M" 은 그 티셔츠의 조합이지 그 자체로 상품이 아니다.

**재고는 그 덩어리에 안 든다.** `sku_stock` 이 따로 있다(청크 52) — 상품 정의는 사람이 가끔 고치고
재고는 주문마다 바뀐다. 한 행에 두면 상품을 고친 시각과 물건이 나간 시각이 같은 칸에 섞이고,
이동 이력(청크 53)이 가리킬 곳도 정해지지 않는다. 배송지를 뺀 것과 같은 이유다 — **수명이 다르다.**

특정 색상만 단종하는 경우는 SKU 의 **업무 상태**(판매중지)로 표현한다. 수명이 아니다.
여기서도 두 축을 가르는 규칙이 그대로 적용된다.

### 주문과 배송지는 한 덩어리가 아니다

ADR 0007 이 배송지를 별도 테이블로 뺐다. 소유는 주문이지만 **수명이 다르다.**

```
주문      5년 보존 (D2 R6)
배송지    개인정보라 파기 대상 (D2 R9)
```

파기 시점이 오면 배송지 행만 지우고 주문은 남는다. 주문을 조인해서 배송지가 없으면 파기된 것이다.

## 참조 규칙

### 외래키를 건다

주문이 가리키는 SKU 를 지울 수 없게 한다. `order_item.sku_id` 는 `restrict` 다.

SKU 를 물리 삭제하지 않으므로(수명 컬럼을 쓴다) 실제로 걸릴 일은 없다.
그래도 외래키를 두는 이유는 **"이 주문이 어떤 SKU 였나" 를 끝까지 따라갈 수 있어야** 해서다.

주문 항목에 상품명과 가격을 박제하므로 SKU 가 없어도 주문은 읽힌다.
박제는 **표시용**이고 외래키는 **추적용**이다. 둘 다 필요하다.

### cascade 를 쓰는 곳

애그리거트 안쪽만이다.

| 관계 | 규칙 | 왜 |
|---|---|---|
| `seller_member` → `seller` | cascade | 셀러가 없으면 소속이 무의미 |
| `seller_member` → `app_user` | cascade | 계정이 없으면 소속이 무의미 |
| `user_role` → `app_user` | cascade | 같은 이유 |
| `user_role` → `role` | **restrict** | 사용자가 달린 역할은 회수부터 하게 만든다 |
| `role_permission` → `role` | cascade | 역할이 사라지면 권한 부여도 사라진다 |
| `order_item` → `order` | cascade | 애그리거트 안쪽 |
| `order_item` → `sku` | **restrict** | 애그리거트를 넘는 참조 |

**애그리거트를 넘는 참조에는 cascade 를 쓰지 않는다.** 한 덩어리를 지웠는데 다른 덩어리가 같이 사라지면
어디까지 지워질지 예측이 안 된다.

다만 위 표의 cascade 는 **물리 삭제할 때의 규칙**이다.
수명 컬럼을 쓰는 자원은 물리 삭제를 안 하므로 이 규칙이 발동하지 않는다.
개발 중에 데이터를 정리하거나 파기 배치가 실제로 행을 지울 때만 쓰인다.

## 소유 방향

**상품의 주인은 사람이 아니라 셀러다.** `product.seller_id` 는 `seller` 를 가리킨다.

담당자가 바뀌어도 상품은 셀러에 남는다. 사람에 매달면 퇴사할 때마다 상품을 옮겨야 한다.
ADR 0004 가 정한 것이고, 조직 축(청크 3a)이 생긴 이유이기도 하다.

## 주문 뒤에 붙는 덩어리 — 결제·환불·반품·손해배상

주문이 두 층이라(`state-machines.md` 「주문은 두 층이다」) 뿌리도 둘이다.
**결제는 `shop_order` 에, 환불·반품·배상은 `seller_order` 에 붙는다.**

```
Order ──┬─ OrderItem                 seller_order 를 거쳐 매달린다
        ├─ OrderShipping             (order_shipping) 수명이 다르다. 위 참조
        ├─ OrderContractDocument     (order_contract_document) 계약 시점의 문서 판. 주문과 같이 산다
        ├─ OrderStatusHistory        (order_status_history · order_status_history_note) append-only
        └─ Payment ─ PaymentCard     (payment · payment_card) 승인 시도 한 건. 카드 조각은 먼저 사라진다

SellerOrder ─┬─ Refund ─┬─ RefundItem          → order_item
             │          └─ RefundNote
             ├─ ReturnRequest ─┬─ ReturnRequestItem  → order_item
             │                 ├─ ReturnPickup
             │                 └─ ReturnNote
             └─ Compensation ─ CompensationNote     → seller_order 가 restrict 다. 아래
```

| 관계 | 규칙 | 왜 |
|---|---|---|
| `payment` → `shop_order` | cascade | 승인 시도는 주문 없이 뜻이 없다 |
| `refund`·`return_request` → `seller_order` | cascade | 묶음 안쪽이다 |
| `compensation` → `seller_order` | **restrict** | 정산이 이 행을 `(번호, 부담 주체)` 로 가리킨다(`43a-4b`). 장부의 근거는 안쪽이어도 못 지운다 |
| `refund_item`·`return_request_item` → `order_item` | cascade | 항목이 사라지면 그 항목의 환불·반품 줄도 뜻이 없다 |
| `order_status_history` → `shop_order`·`seller_order` | **restrict** | 지우는 쪽은 파기 배치다(`D13`). cascade 로 조용히 따라가게 두지 않는다 |
| `*_note` → 부모 | cascade | 자유 텍스트는 부모보다 먼저 사라진다(`D13`). cascade 는 부모가 먼저 갈 때의 보험이다 |
| `compensation` → `inquiry` | set null | 배상이 문의에서 왔다는 표시일 뿐이다. 문의가 3년 뒤 사라져도 판정은 남는다 |

이 절의 표: `order_shipping` · `order_contract_document` · `order_status_history` · `order_status_history_note` · `payment` · `payment_card` ·
`refund` · `refund_item` · `refund_note` · `return_request` · `return_request_item` · `return_pickup` · `return_note` · `compensation` · `compensation_note`.

**같은 뿌리 안에 cascade 와 restrict 가 섞인 이유는 정산이다.** `settlement_item` 이 `order_item`·`seller_order`·`refund_item`·`compensation` 을
전부 restrict 로 가리켜서 **5년 주문 파기가 정산 줄에 막힌다** — 법 둘이 부딪치는 자리고 `43a-28` 이 정한다.

## 정산 — 장부는 바깥을 restrict 로만 가리킨다

```
SettlementCycle ─ Settlement ─ SettlementItem ─→ order_item · seller_order · refund_item · compensation   전부 restrict
                                              └→ settlement (이월, carried_from)                         restrict
```

정산은 덩어리 하나고 **다른 덩어리를 소유하지 않는다.** 줄이 가리키는 것은 근거지 소속이 아니다.
근거가 사라지면 줄이 남의 것이 되므로 전부 restrict 다. `settlement` → `settlement_cycle`·`seller` 도 restrict 다.

## 문의·알림·동의·장바구니 — 독립 뿌리

| 덩어리 | 표 | 참조 | 규칙 |
|---|---|---|---|
| Inquiry | `inquiry` | → `app_user`·`product`·`seller_order` 전부 restrict | 뿌리다. 상품·주문에 붙어 보이지만 **소유는 쓴 사람**이다 — 상품이 내려가도 문의는 남는다(3년, `D13`) |
| Notification | `notification` ─ `notification_body` | → `shop_order`·`seller_order`·`refund`·`user_consent` cascade / → `notification_template` restrict | 사건의 기록이라 사건을 따라간다. 판은 지우면 그때 보낸 본문을 복원할 수 없어 restrict |
| Consent | `consent_item`(판) / `user_consent`(이력) | `user_consent` → `app_user` cascade, → `consent_item` restrict. `consent_item.depends_on_id` 도 restrict | 판과 이력을 가른다. `order_contract_document` 가 판을 restrict 로 박제한다 |
| Cart | `cart` ─ `cart_item` | `cart` → `app_user` cascade(비로그인은 null), `cart_item` → `sku` cascade | 거래 기록이 아니라 그냥 지운다. SKU 가 사라지면 담긴 것도 사라진다 |

## AppUser 에 붙은 것

```
AppUser ─┬─ UserRole                (user_role)
         ├─ SellerMember            (seller_member)
         ├─ UserConsent             (user_consent)
         ├─ PasswordResetToken      (password_reset_token)   30일 · cascade
         ├─ EmailChangeRequest      (email_change_request)   30일 · cascade
         ├─ IdempotencyKey          (idempotency_key)        24시간 · cascade
         └─ Cart                    (로그인 장바구니)
```

이 절의 표: `user_role` · `seller_member` · `user_consent` · `password_reset_token` · `email_change_request` · `idempotency_key`.
토큰 둘과 멱등키는 계정 없이 뜻이 없고 짧게 산다. 파기 배치가 기간으로 지우고, 계정이 먼저 가면 cascade 가 받는다.

## 상품 덩어리의 표

위 「상품은 한 덩어리다」의 실물이다 — `product`·`product_option`·`product_option_value`·`sku`·`sku_option_value` 가 덩어리고,
`sku_stock`·`sku_stock_movement` 는 SKU 를 따라가되 덩어리에 안 든다. `product_substantiation` 은 상품에 cascade 다 —
실증 자료라 상품 없이 뜻이 없다(`13f-1`).

**`product_image` 도 cascade 다**(`27`). 상품이 없으면 그 상품의 사진은 뜻이 없다 —
다만 **행이 사라져도 저장소의 객체는 안 사라진다.** 파일을 지우는 것은 cascade 가 아니라
지우는 코드의 일이고, 그 코드는 `Q94`(신고·삭제)와 파기 배치가 든다(`media-rules.md`).

## 애그리거트 밖 — 참조 값과 기록

| 표 | 왜 덩어리가 아닌가 |
|---|---|
| `holiday` | 영업일 참조 값. 아무도 소유하지 않는다 |
| `policy_document` | 문안의 판. `order_contract_document` 가 restrict 로 가리킨다 |
| `notification_template` | 위와 같다 |
| `batch_run` | 회차 기록. 외래키가 없다 |
| `copyright_report` | 신고 기록(`Q94`, `D2` `R42`). **사진이 사라져도 남는다** — `product_image_id` 가 `set null` 이고 `product_id` 가 남아서 무엇을 내렸는지를 든다. 접수된 사실 자체가 절차를 돌린 증거라 지우지 않는다 |
| `audit_log` | 사건 기록. 외래키가 없다 — 계정이 파기돼도 남아야 한다(`D13` 「감사 로그는 예외다」) |
| `outbox_event` | 바깥에 알릴 사건(`Q57`, `D12`). **외래키가 없다** — 원천 행이 파기돼도 보낸 사실은 남고, 소비자가 이미 받았을 수 있다. 채우는 것은 앱이 아니라 원천 표의 트리거다 |
| `role`·`permission`·`role_permission`·`permission_field_group`·`role_permission_field` | 권한 구성. 배포로 바뀌는 값이다 |

## 이 문서를 고칠 때

새 테이블이 생기면 **어느 애그리거트에 속하는지**와 **수명 컬럼을 두는지**를 여기에 적는다.
안 적으면 다음 사람이 즉흥으로 정하고, 즉흥으로 정한 참조 방향은 나중에 못 바꾼다.

**이 문서가 `V16` 에서 멈춘 채 `V69` 까지 갔다**(2026-09-14 설계 점검). 정산·환불·반품·문의·배상·알림·회차 표 일곱이
여기 없었고, 위 절들이 그날 채운 것이다. **재발은 `Q48` 이 막는다** — 마이그레이션의 `create table` 이름 전부가
이 문서에 있는지 테스트가 잰다. 표를 만들고 여기 안 적으면 빨갛다.
