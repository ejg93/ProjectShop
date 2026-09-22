# ERD — 묶음 사이

**생성물이다. 손으로 고치지 않는다** — `SchemaErdTest` 가 스키마에서 뽑는다.

```mermaid
flowchart LR
    account -->|1| permission
    ops -->|3| account
    ops -->|3| order
    ops -->|1| product
    ops -->|1| settlement
    order -->|11| account
    order -->|1| ops
    order -->|2| product
    permission -->|2| account
    product -->|7| account
    product -->|2| order
    settlement -->|5| account
    settlement -->|5| order
```

화살표는 **가리키는 쪽 → 가리켜지는 쪽**이고, 수는 그 방향의 외래키 수다.

- [[order]] — 표 18개
- [[product]] — 표 13개
- [[account]] — 표 9개
- [[settlement]] — 표 6개
- [[permission]] — 표 6개
- [[ops]] — 표 8개
