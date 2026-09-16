# 이벤트 카탈로그

무엇이 일어났나를 바깥에 알리는 단위다 — 발행기(`33`)가 내보내고 웹훅(`30`)·Kafka 가 받는다.
이름·봉투·페이로드·원천·버전·전송 수단을 정한다. 발행기의 구현(`33`)은 여기서 안 정한다.

## 왜 지금 쓰나

`D12` 는 「이벤트가 0개라 조건 미달」로 서 있었다. 2026-09-14 설계 점검이 그 조건이 **순환**임을 짚었다 —
이벤트는 `D12` 를 기다리고 `D12` 는 이벤트를 기다렸다. 근거는 이미 있었다.
`order_status_history`·`sku_stock_movement`·`batch_run` 이 「무엇이 언제 일어났나」를 넣기만 하고 안 고치는 표로 적고 있다.
이 문서는 그 표들을 이벤트의 원천으로 삼는다.

## 결정 셋

사용자가 골랐다(2026-09-14 설계 세션 — `PROGRESS.md` 이력이 갈림길과 근거를 든다).

| 결정 | 고른 것 | 버린 것 | 왜 |
|---|---|---|---|
| **원천** | 표준 아웃박스 표 하나 + **원천 표의 `after` 트리거가 채운다** | 코드가 전이 자리마다 `insert` · 기존 표를 발행기가 직접 읽기 | 앱이 넣으면 새 전이 자리가 빠뜨린다(앱 검증 3위). 트리거면 DB 가 든다(2위) — `move_stock()` 이 재고에 한 것과 같은 모양. 기존 표를 직접 읽으면 발행기가 원천 표 여섯의 모양에 묶인다 |
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

## 전송 — Kafka

발행기(`33`)가 `outbox_event` 를 Kafka 로 보낸다. 사용자가 골랐고 갈림길(웹훅 직접 · Kafka 가운데 웹훅도 소비자)은
`PROGRESS.md` 이력이 든다.

| 항목 | 값 | 왜 |
|---|---|---|
| 토픽 | `shop.events` 하나 | 소비자마다 토픽을 가르면 순서 보장 단위가 갈린다. 종류는 헤더 `type` 으로 거른다 |
| 파티션 키 | `subject` | 같은 주문의 사건이 같은 파티션에 들어가 **발행 순서대로 소비된다.** 위 「순서는 `subject` 단위로만」이 그대로 Kafka 의 보장이 된다 |
| 파티션 수 | 3 | 키별 순서가 실제로 갈리는 것을 로컬에서 볼 수 있는 최소 |
| 메시지 본문 | 봉투 JSON 그대로(CloudEvents structured) | 받는 쪽이 봉투만 알면 된다 |
| 헤더 | `type` · `id` | 본문을 안 열고 거른다 |
| 전달 | **최소 한 번.** 발행기는 브로커 ack 뒤에만 `published_at` 을 채운다 | ack 전에 죽으면 다음 회차가 다시 보낸다 → 중복. 소비자가 거른다 |
| 소비자 멱등 | 소비자마다 **자기 표의 유니크**로 | 거래 통지는 `notification` 부분 유니크(`54a`). 오프셋은 처리 뒤에 커밋한다 |
| 스위치 | `shop.events.sink` = `none`(기본) / `kafka` | `none` 이면 **발행기와 토픽 빈이 안 선다** — 브로커로 나가는 연결이 안 열린다. **배포(`Q39`)와 빠른 레인은 `none`** |

**「빈을 아예 안 만든다」가 아니다**(`33` 실측). Boot 의 자동 설정은 `spring-boot-starter-kafka` 가 클래스패스에 있으면 무조건 돌아서 `KafkaTemplate` 빈이 선다 — 그것을 끄려면 `spring.autoconfigure.exclude` 에 적어야 하는데, 그 값은 환경변수 하나로 같이 못 움직인다(`EVENTS_SINK=kafka` 로 켜는 사람이 제외 목록도 같이 비워야 한다). **막으려던 것은 브로커로 나가는 연결이고 그것은 우리 빈에서 막힌다** — 프로듀서는 처음 보낼 때 붙고, 보내는 자리가 `sink` 로 잠겨 있다.

**Kafka 는 로컬에서만 돈다.** Railway 에 브로커가 없고 관리형은 과금이라 안 올린다(사용자 결정). 이 저장소에
처음으로 「로컬에서만 도는 축」이 생겼다 — `none` 이면 스위퍼가 하던 대로 5분마다 집는다.
두 경로가 같은 결과를 내는지는 `33a` 가 잰다.

**스위퍼는 안전망으로 남는다.** 소비자가 죽어 있는 동안 못 받은 사건을 5분 뒤 스위퍼가 집는다.
둘이 같은 사건을 잡아도 유니크가 하나만 남긴다 — 데드레터 큐를 따로 안 두는 이유다.


## 소비자 — 누가 읽나

| 소비자 | 그룹 | 무엇을 받나 | 무엇을 하나 |
|---|---|---|---|
| `NotificationConsumer`(`33a`) | `shop-notification` | 토픽 전부(종류는 헤더 `type` 으로 거른다) | 청약 접수·대금 지급·환급 통지를 **그 자리에서** 남긴다 |

**넷 중 둘이 안 온다.** 공급 지연은 「기한이 지났다」는 시각 조건이라 전이가 아니고, 그래서 사건이 없다 —
스위퍼 몫으로 남는다. **청약 접수는 온다** — `33a` 가 주문 생성에 이력 행을 더해서 사건이 생겼다.

**새 소비자는 자기 그룹으로 같은 토픽을 읽는다.** 그룹이 다르면 오프셋이 따로 흘러서
서로를 안 막는다 — 토픽을 하나로 둔 이유다.

## 버전

- **`type` 이름은 안 바꾼다.** `data` 에 필드를 **더하는 것만** 허용한다. 빼거나 뜻을 바꾸면 사건 이름을 다시 짓는다 —
  `_v2` 를 안 붙인다. 버전 번호가 이름에 들어가면 받는 쪽이 둘을 다 구독하게 된다(Zalando Event 장)
- `specversion` 은 CloudEvents 의 것이지 우리 것이 아니다. 우리 계약의 판은 `type` 이 든다

## 지금 안 하는 것

| 항목 | 왜 |
|---|---|
| 데드레터 큐 | 스위퍼가 그 역할이다 — 위 「전송」 |
| 관리형 Kafka(배포) | 과금. 올리고 싶어지면 `shop.events.sink` 와 `KAFKA_BOOTSTRAP` 만 바꾼다 |
| 웹훅 서명 | `30`. 웹훅이 Kafka 소비자로 설지 발행기가 직접 보낼지는 그때 정한다 |
| 재생(replay) | 발행된 행을 7일 뒤 지우므로 못 한다. 필요해지면 수명을 늘린다 — `D13` 의 그 행 하나다 |
| 스키마 레지스트리 | 소비자가 우리 발행기 하나뿐이다 |
| 문의·상품 이벤트 | 위 「안 만드는 것」 |

## 이 문서를 고칠 때

새 `type` 은 셋을 같이 고친다 — 위 표, `outbox_event_type_check`, `EventType`. 뒤 둘은 `EnumConstraintTest` 가 대조하고
앞은 사람이 든다. 페이로드에 개인정보를 실어야 할 이유가 생기면 이 문서를 먼저 고치고 그 근거를 여기 적는다.
