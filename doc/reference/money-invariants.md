# 불변식 카탈로그 — 돈 축

돈 축에서 절대 깨지면 안 되는 등식을 한 자리에 모으고, 등식마다 어디서 강제할지 같이 정한다.
강제 지점의 우선순위(`check` → 트리거 → 테스트)는 `coding-rules.md`(D23)가 정했고 여기서는 그 순서를 적용만 한다.

**주문(청크 10) 앞에 왔다.** 스키마를 만들고 나서 등식을 세우면 `check` 로 걸 수 있었던 자리가
이미 지나가 있다. 제약은 만들 때 붙이는 것이 가장 싸다.

## 컬럼 이름은 여기서 정하지 않는다

등식을 쓰려면 값을 가리켜야 해서 이름을 적었지만, **확정은 청크 10·17 이 한다**(`naming-rules.md`).
여기서 고정하는 것은 **어떤 값이 어떤 값의 합인가** 이지 그 값의 이름이 아니다.

## 주문 — 청크 10

### 한 행 안에서 끝나는 것

| 등식 | 강제 |
|---|---|
| `order_item.line_amount = order_item.unit_price_incl_vat * order_item.quantity` | `check` |
| `order_item.commission_amount = order_item.line_amount * order_item.commission_bp / 10000` | `check` |
| `order_item.quantity >= 1` | `check` |
| `order_item.unit_price_incl_vat >= 0` | `check` |
| `order_item.commission_bp between 0 and 10000` | `check` |
| `seller_order.shipping_fee >= 0` | `check` |
| `shop_order.payable_amount = shop_order.total_amount + shop_order.shipping_fee_total` | `check` |

**결제 금액이 여기 있는 것은 배송비 합을 컬럼으로 뒀기 때문이다.** 처음에는 셋 다 트리거로 잡았는데,
합을 컬럼에 두면 등식이 한 행 안에서 끝나서 한 칸 아래로 내려간다(`coding-rules.md` 「가장 낮은 층에 건다」).
남은 트리거는 그 컬럼이 셀러 주문의 배송비 합과 맞는지만 본다.

수수료 등식이 `check` 로 서는 것은 **금액을 원 단위 정수로 저장하기 때문**이다(`money-rules.md`).
정수 나눗셈이 곧 버림이라 절사 규칙이 등식 안에 들어간다. 나눗셈을 따로 표현할 필요가 없다.

**대가를 같이 적는다.** 절사 규칙을 버림에서 반올림으로 바꾸면 이 제약이 기존 행과 충돌한다.
그때는 제약을 갈아 끼우고 새 제약을 `not valid` 로 붙여 과거 행을 검사에서 뺀다.
바꾼 뒤에도 과거 수수료가 안 변하는 것은 값이 박제돼 있어서고(`money-rules.md`), 변하는 것은 제약뿐이다.

### 여러 행에 걸친 것

| 등식 | 강제 |
|---|---|
| `shop_order.total_amount = sum(order_item.line_amount)` | 지연 트리거 |
| `shop_order.commission_total = sum(order_item.commission_amount)` | 지연 트리거 |
| `shop_order.shipping_fee_total = sum(seller_order.shipping_fee)` | 지연 트리거 |
| 주문에 셀러 주문이 하나 이상 있다 | 지연 트리거 |
| 셀러 주문에 항목이 하나 이상 있다 | 지연 트리거 |

배송비가 따로 더해지는 것은 **수수료를 안 매기기 때문**이다(`business-model.md`).
상품 금액에 합쳐 두면 수수료 계산에서 다시 빼야 하고, 빼는 자리가 하나라도 새면 셀러가 손해를 본다.

뒤 둘은 등식이 아니라 존재 조건이다. **빈 주문은 합이 0 으로 맞아떨어져서 금액 등식에 안 걸린다** —
같은 트리거가 보지 않으면 아무것도 안 산 주문이 남는다.

## 합계를 저장하기로 했다

`money-rules.md` 가 청크 10 으로 미뤄 둔 결정이다. **저장하고 DB 가 강제한다**(사용자 선택).

파생으로 두면 어긋날 수 없지만 두 가지를 잃는다. 주문 목록마다 항목을 조인해 집계해야 하고,
**결제가 PG 에 보낸 금액을 박제할 자리가 없어진다** — 주문 시각의 값을 복사해 두는 원칙이
금액 자체에는 안 걸리는 모양이 된다.

### 왜 지연 트리거인가

`order` 행이 먼저 들어가고 `order_item` 이 뒤에 붙는다. 참조 방향이 그 반대라서 순서를 바꿀 수 없다.
즉시 트리거를 걸면 **`order` 를 넣는 순간 항목 합이 0 이라 언제나 깨진다.**

`constraint trigger ... deferrable initially deferred` 로 검사를 커밋 시점까지 미룬다.
트랜잭션 중간에는 합이 안 맞아도 되고, 커밋할 때 맞으면 된다.

`order` 와 `order_item` 양쪽에 건다. 항목만 보면 항목을 안 건드리고 합계만 고치는 경로가 빠져나간다.

**애플리케이션에 두지 않은 이유**는 입구가 하나가 아니어서다. 배치·`psql`·나중의 관리자 도구가
같은 테이블을 쓴다. 정산은 1원만 어긋나도 사고다.

## 정산 — 청크 17~21

| 등식 | 강제 |
|---|---|
| `settlement.payout_amount = sum(settlement_item.amount)` | 지연 트리거(`V52`) |
| `sum(settlement_item.amount where 종류 = 상품대금) = 구매확정 주문 항목 금액 합` | `SettlementCloseBatchTest`(청크 19) |
| `sum(settlement_item.amount where 종류 = 수수료) = 그 항목들의 박제된 수수료 합` | `SettlementCloseBatchTest`(청크 19) |
| `settlement.carried_over = least(0, payout_amount)` | **`check`**(`V52`) |
| `(셀러, 정산 주기)` 조합은 하나 | `unique`(`V52`) |
| **근거 하나는 평생 한 번만 실린다** | 부분 `unique`(`V52`) |

**넷째가 테스트에서 `check` 로 내려갔다**(청크 17). 한 행 안에서 결정되는 값이라
더 낮은 자리에 걸 수 있었다 — `D23` 축 2 는 걸 수 있는 자리가 여럿이면 아래를 고르라고 한다.

**여섯째는 청크 17 에서 나왔다.** 위의 유일성은 같은 주기를 두 번 마감하는 것만 막는다.
같은 주문 항목이 **다른 주기의 정산서에** 실리면 두 정산서 각각은 합이 맞고 셀러는 돈을 두 번 받는다.
그래서 근거 참조(`order_item_id`·`seller_order_id`·`refund_item_id`·`carried_from_settlement_id`)마다
**정산서를 넘어선 전역 유니크**를 건다.

### 부호를 종류가 정한다

셀러에게 주는 것이 양수, 우리가 떼거나 물리는 것이 음수다(`settlement_item_amount_sign_check`).
수수료를 양수로 담고 뺄셈을 앱이 하면 **「합이 곧 지급액」이 성립을 안 하고**, 그 순간 첫째 줄의
지연 트리거가 아무것도 못 막는다. 위 표의 둘째·셋째 줄을 셀 때는 그 부호를 감안한다.

지급액 공식 자체(`구매확정 합 - 수수료 - 회수 - 이월`)는 `business-model.md` 에 있다.
**여기서 고정하는 것은 구한 값이 맞는지 아는 방법**이라 공식을 옮겨 적지 않는다.

마지막 줄이 등식이 아니라 유일성인 것은, 정산 마감 배치가 두 번 돌 때 **지급이 두 배가 되는 사고**가
합계 불일치로는 안 잡히기 때문이다. 두 번째 정산서는 그것대로 합이 맞는다.

### 공급자가 갈리는 것도 불변식이다

상품 대금은 셀러가 고객에게 공급한 것이고 중개수수료는 플랫폼이 셀러에게 공급한 것이다
(`commerce-compliance.md` R17). 한 줄로 뭉치면 성격이 다른 둘이 섞여서 세금계산서를 못 가른다.

**항목 종류가 공급자를 결정한다.** 종류마다 공급자가 정해져 있으므로 `check` 로 건다 —
공급자 컬럼을 따로 받아서 채우면 종류와 어긋난 행이 생긴다.

## 결제 — 청크 12

| 등식 | 강제 |
|---|---|
| `payment.amount = shop_order.payable_amount` | **구조** |

**트리거가 아니라 구조인 것이 이 줄의 성질이다.** 결제 요청이 금액을 안 받는다 —
서버가 주문에서 읽어서 PG 에 보내고 그 값을 그대로 적으므로, 다른 값이 들어올 경로가 없다.
강제 지점 1위라 앱이 실수해도 어길 방법이 없다(`coding-rules.md` 축 2).

요청에서 금액을 받으면 **그 값이 맞는지 검사하는 코드**가 따로 필요해지고, 빠뜨리면
원하는 금액으로 결제된다. 등식을 트리거로 거는 것은 그 검사를 한 층 아래로 옮긴 것일 뿐이고,
애초에 받지 않는 것이 한 칸 더 아래다.

## 환불 — 청크 12a-1

| 등식 | 강제 |
|---|---|
| `sum(refund.amount) <= payment.amount` | `assert_refund_within_payment` |
| `refund.amount = sum(refund_item.amount) + refund.shipping_fee_refund` | `assert_refund_totals` |
| `sum(refund_item.quantity) <= order_item.quantity` | `assert_refund_item_within_order_item` |
| `sum(refund_item.amount) <= order_item.line_amount` | `assert_refund_item_within_order_item` |
| `sum(refund_item.commission_refund) <= order_item.commission_amount` | `assert_refund_item_within_order_item` |
| 항목을 통째로 환불하면 `sum(commission_refund) = order_item.commission_amount` | 테스트 |

부등호인 것이 여기의 성질이다. 주문·정산 축은 합이 정확히 맞아야 하지만 환불은 **여러 번 날 수 있고
매번 일부만 낼 수 있다.** 그래서 상한만 건다.

**상한은 한 행이 아니라 누계로 센다.** 수량 3개짜리를 2개씩 두 번 환불하면 각각은 상한 안이고
합만 넘는다 — 행마다 보면 안 걸린다. 반려된 요청은 누계에서 뺀다.

### 수량 일부를 환불할 때 절사 잔액

마지막 남은 수량을 환불할 때 **잔액을 전부 싣는다**(청크 12a-1, 사용자 선택).

```
commission_refund = 마지막 수량이면  commission_amount - 이미 나간 합
                    아니면          commission_amount × 이번 수량 / 주문 수량 (버림)
```

수수료가 항목 단위로 이미 잘린 값이라(`money-rules.md`) 수량으로 또 나누면 1원이 남는데,
안 몰아 주면 **통째로 환불했는데 그 1원이 정산에 우리 몫으로 남는다.** 위 표의 마지막 줄이
그것을 잡는 자리고, 상한만 보는 트리거로는 안 걸려서 강제 지점이 테스트다.

배송비는 나누지 않는다. `seller_order` 단위라 항목별로 가를 근거가 없어서,
그 묶음이 비워질 때 전액이고 그전에는 0 이다.

## 지금 못 거는 것

| 등식 | 왜 | 언제 |
|---|---|---|
| ~~정산 축 다섯~~ | ~~`settlement` 테이블이 없다~~ | **완료 — 청크 17.** 표 셋이 서면서 넷이 제약·트리거로 내려갔고, 둘(합계 대조)은 마감 배치가 값을 만들기 시작하는 청크 19 가 건다 |

**적어 두는 것과 거는 것을 가른다.** 등식을 지금 고정해 두면 그 스키마를 만드는 청크가
"무엇을 강제할 수 있는 모양이어야 하나" 를 알고 시작한다. 이 문서가 `17` 앞에 온 이유와 같다.

## 새 등식이 생기는 자리

**합계를 저장하는 순간**이다(`coding-rules.md`). 할인·쿠폰(청크 49~51)과 포인트가 들어오면
주문 금액 등식의 오른쪽이 늘어난다. 그때 이 문서의 주문 절을 고치고, 늘어난 항이
`check` 로 되는지부터 다시 본다.
