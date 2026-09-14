# 이벤트 카탈로그

무엇이 일어났나를 바깥에 알리는 단위다 — 발행기(`33`)가 내보내고 웹훅(`30`)·Kafka 가 받는다.
이름·봉투·페이로드·원천·버전을 정한다. 발행기와 전송 수단은 여기서 안 정한다.

## 왜 지금 쓰나

`D12` 는 「이벤트가 0개라 조건 미달」로 서 있었다. 2026-09-14 설계 점검이 그 조건이 **순환**임을 짚었다 —
이벤트는 `D12` 를 기다리고 `D12` 는 이벤트를 기다렸다. 근거는 이미 있었다.
`order_status_history`·`sku_stock_movement`·`batch_run` 이 「무엇이 언제 일어났나」를 넣기만 하고 안 고치는 표로 적고 있다.
이 문서는 그 표들을 이벤트의 원천으로 삼는다.

## 결정 셋

사용자가 골랐다(2026-09-14 설계 세션 — `PROGRESS.md` 이력이 갈림길과 근거를 든다).

| 결정 | 고른 것 | 버린 것 | 왜 |
|---|---|---|---|
| **원천** | 표준 아웃박스 표 하나 + **원천 표의 `after` 트리거가 채운다** | 코드가 전이 자리마다 `insert` · 기존 표를 발행기가 직접 읽기 | 앱이 넣으면 새 전이 자리가 빠뜨린다(앱 검증 3위). 트리거면 DB 가 든다(2위) — `move_stock()` 이 재고에 한 것과 같은 모양. 기존 표를 직접 읽으면 발행기가 표 넷의 모양에 묶인다 |
| **봉투** | CloudEvents 1.0 속성 이름 | 자체 이름 | 웹훅(`29`)이 대외 계약이 될 때 받는 쪽이 기존 라이브러리를 쓴다. `external-references.md` 의 「보류」를 「채택」으로 닫는다 |
| **전달** | 최소 한 번. 중복은 소비자가 `id` 로 거른다 | 정확히 한 번 | 그것은 발행기와 소비자 둘이 같이 만드는 성질이라 표 하나로 못 준다(`34`) |

## 이벤트 이름과 원천

`type` 은 `shop.<자원>.<사건>` 이다. 자원은 `D22` 의 표 이름(단수), 사건은 과거형이다.

| `type` | 원천 표 | 언제 | `subject` |
|---|---|---|---|
| `shop.order.status_changed` | `order_status_history` (`order_id` 가 있는 행) | 결제 층 전이(`D7`) | `order_number` |
| `shop.seller_order.status_changed` | `order_status_history` (`seller_order_id` 가 있는 행) | 배송 층 전이 | `seller_order_number` |
| `shop.sku.stock_moved` | `sku_stock_movement` | `move_stock()` 이 돌 때 | `sku_id` — 노출 번호가 없다. **웹훅으로는 안 나간다**(`D9`) |
| `shop.refund.status_changed` | `refund` (`status` 가 바뀔 때) | 환불 전이 | `refund_number` |
| `shop.return_request.status_changed` | `return_request` (`status` 가 바뀔 때) | 반품 전이 | `return_request_id` — 노출 번호가 아직 없다. 웹훅에 내보내려면 번호부터다(`43a-5` 자리) |
| `shop.settlement.payout_changed` | `settlement` (`payout_status` 가 바뀔 때) | 지급 전이 | `settlement_number` |
| `shop.batch.run_finished` | `batch_run` | 회차가 끝났을 때 | `batch_name` |

**원천이 둘로 갈린다.** 앞 셋은 이미 넣기만 하는 표라 `after insert` 다. 뒤 셋은 상태를 한 행에서 고치는 표라
`after update of status ... when (old.status is distinct from new.status)` 다 — 같은 값으로 다시 쓰면 사건이 아니다.

**안 만드는 것**

| 무엇 | 왜 |
|---|---|
| 알림 발송(`notification`) | 사건이 아니라 사건의 소비자다. 알림이 나갔다는 것을 또 알리면 끝이 없다 |
| 문의(`inquiry`) | 받을 상대가 없다. 셀러 웹훅이 문의를 원하면 그때 더한다 |
| 상품 상태(`product`) | 셀러 자기 것이라 알릴 상대가 없다. 검수 결과를 셀러에게 알리는 자리가 생기면 그때 |

## 봉투 — CloudEvents 1.0

| 속성 | 값 | 어디서 오나 |
|---|---|---|
| `specversion` | `1.0` | 상수 |
| `id` | `outbox_event_id` 를 문자열로 | 표. 한 `source` 안에서 유일하다 |
| `source` | `tag:projectshop.example,2026:shop` | `ErrorCode` 의 `tag:` 체계와 같다(`D5`). 진짜 도메인이 생기면 같이 바뀌고 그것은 계약 변경이다 |
| `type` | 위 표 | 트리거 |
| `subject` | 위 표 | 트리거 |
| `time` | `occurred_at`, UTC `Z`(`D10`) | **원천 행의 시각이다.** 발행 시각이 아니다 |
| `datacontenttype` | `application/json` | 상수 |
| `data` | 아래 | 트리거 |

## 페이로드 규칙

1. **식별자만 싣는다.** 이름·이메일·전화·주소는 안 싣는다 — `D16` 이 로그에 정한 것과 같은 방향이다.
   받는 쪽이 필요하면 API 로 다시 읽는다. 그래야 이벤트가 새도 개인정보는 안 샌다
2. **노출 번호를 쓴다**(`D9`). 내부 id 는 `sku_id` 처럼 노출 번호가 없는 것만
3. 전이면 `from_status`·`to_status`·`actor_type` 을 싣는다. `actor_user_id` 는 안 싣는다 — 1번이다
4. **표기는 저장값 그대로다** — `data` 의 키는 snake_case, 열거값은 소문자. 트리거가 만들어서 Java 의 대문자 변환(`EnumValue`)을 못 지난다.
   **API 응답(`D5` 「값의 형식」)과 다르다.** 웹훅이 대외 계약이 될 때 대문자로 바꿀지 그때 정하고, 바꾼다면 발행기(`33`)가 한다 — 트리거는 안 한다

```json
{
  "specversion": "1.0",
  "id": "4213",
  "source": "tag:projectshop.example,2026:shop",
  "type": "shop.seller_order.status_changed",
  "subject": "S-20260914-7K3MNP",
  "time": "2026-09-14T08:11:02.417Z",
  "datacontenttype": "application/json",
  "data": {
    "seller_order_number": "S-20260914-7K3MNP",
    "order_number": "20260914-Q8W2XZ",
    "from_status": "shipping",
    "to_status": "delivered",
    "actor_type": "seller"
  }
}
```

## 아웃박스 표 — `Q57` 이 세운다(`32` 를 대신한다)

```sql
create table outbox_event (
    outbox_event_id bigint not null generated always as identity primary key,
    type        text not null,
    source      text not null default 'tag:projectshop.example,2026:shop',
    subject     text not null,
    occurred_at timestamptz not null,
    data        jsonb not null,
    created_at  timestamptz not null default now(),
    published_at timestamptz,
    constraint outbox_event_type_check check (type in (
        'shop.order.status_changed', 'shop.seller_order.status_changed', 'shop.sku.stock_moved',
        'shop.refund.status_changed', 'shop.return_request.status_changed',
        'shop.settlement.payout_changed', 'shop.batch.run_finished'))
);
create index outbox_event_unpublished_idx on outbox_event (outbox_event_id) where published_at is null;
```

| 규칙 | 강제 지점 |
|---|---|
| `type` 은 닫힌 목록이다 | `check`(2위). Java `EventType` 과 `EnumConstraintTest` 가 대조한다 |
| **앱은 이 표에 직접 `insert` 하지 않는다** | 트리거가 `set_config('shop.outbox_write', '1', true)` 를 켜고 넣는다. 안 켜진 `insert` 는 거부 — `sku_stock_requires_move` 와 같은 꼴 |
| 원천 행과 같은 트랜잭션이다 | 트리거라 저절로 그렇다. 롤백되면 같이 사라진다 — `D11` 「한 트랜잭션」이 공짜다 |
| 발행은 커밋 뒤다 | 발행기(`33`)가 `published_at is null` 을 읽는다. 트랜잭션 안에서 바깥을 안 부른다(`D11`)가 그대로 성립한다 |
| 발행된 행은 **7일** 뒤 물리 삭제 | `D13` 자원별 정책에 행을 둔다. 미발행은 안 지운다 — 지우면 「일어났는데 안 알린」 사건이 된다 |

**순서는 `subject` 단위로만 약속한다.** `outbox_event_id` 는 넣은 순서지 커밋 순서가 아니다 —
두 트랜잭션이 엇갈려 커밋하면 작은 id 가 나중에 보일 수 있다. 발행기는 id 오름차순으로 보내되,
한 주문의 전이가 뒤바뀌어 도착하지 않는다는 것까지만 든다. 소비자는 `time` 과 `to_status` 로 판단한다.

## 버전

- **`type` 이름은 안 바꾼다.** `data` 에 필드를 **더하는 것만** 허용한다. 빼거나 뜻을 바꾸면 사건 이름을 다시 짓는다 —
  `_v2` 를 안 붙인다. 버전 번호가 이름에 들어가면 받는 쪽이 둘을 다 구독하게 된다(Zalando Event 장)
- `specversion` 은 CloudEvents 의 것이지 우리 것이 아니다. 우리 계약의 판은 `type` 이 든다

## 지금 안 하는 것

| 항목 | 왜 |
|---|---|
| Kafka | `33` 의 전송 수단 선택이다. 이 문서는 봉투와 표만 정한다 — 발행기가 Kafka 로 가든 웹훅으로 가든 표는 같다 |
| 웹훅 서명 | `30` |
| 재생(replay) | 발행된 행을 7일 뒤 지우므로 못 한다. 필요해지면 수명을 늘린다 — `D13` 의 그 행 하나다 |
| 스키마 레지스트리 | 소비자가 우리 발행기 하나뿐이다 |
| 문의·상품 이벤트 | 위 「안 만드는 것」 |

## 이 문서를 고칠 때

새 `type` 은 셋을 같이 고친다 — 위 표, `outbox_event_type_check`, `EventType`. 뒤 둘은 `EnumConstraintTest` 가 대조하고
앞은 사람이 든다. 페이로드에 개인정보를 실어야 할 이유가 생기면 이 문서를 먼저 고치고 그 근거를 여기 적는다.
