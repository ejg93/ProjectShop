# ERD — settlement

**생성물이다. 손으로 고치지 않는다** — `SchemaErdTest` 가 스키마에서 뽑고,
갈리면 빨개진다. 갱신은 `gradlew integrationTest -Dsnapshot.update=true` 다.

```mermaid
erDiagram
    notification }o..|| refund : "refund_id"
    refund }o..|| app_user : "approved_by_user_id"
    refund }o..|| app_user : "requested_by_user_id"
    refund }o..|| seller_order : "seller_order_id"
    refund_item }o..|| order_item : "order_item_id"
    refund_item }o--|| refund : "refund_id"
    refund_note }o--|| refund : "refund_id"
    seller_daily_sales }o..|| seller : "seller_id"
    settlement }o..|| app_user : "payout_decided_by_user_id"
    settlement }o..|| app_user : "payout_requested_by_user_id"
    settlement }o..|| seller : "seller_id"
    settlement }o--|| settlement_cycle : "settlement_cycle_id"
    settlement_item }o--|| settlement : "carried_from_settlement_id"
    settlement_item }o..|| compensation : "compensation_id+compensation_bearer"
    settlement_item }o..|| order_item : "order_item_id"
    settlement_item }o--|| refund_item : "refund_item_id"
    settlement_item }o..|| seller_order : "seller_order_id"
    settlement_item }o--|| settlement : "settlement_id"
```

점선은 이 묶음 밖으로 나가는 외래키다. 상자만 있고 선이 없는 표는
외래키로 아무것도 안 가리키고 아무도 안 가리키는 표다.
