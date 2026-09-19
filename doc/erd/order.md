# ERD — order

**생성물이다. 손으로 고치지 않는다** — `SchemaErdTest` 가 스키마에서 뽑고,
갈리면 빨개진다. 갱신은 `gradlew integrationTest -Dsnapshot.update=true` 다.

```mermaid
erDiagram
    cart }o..|| app_user : "user_id"
    cart_item }o--|| cart : "cart_id"
    cart_item }o..|| sku : "sku_id"
    compensation }o..|| app_user : "decided_by_user_id"
    compensation }o..|| inquiry : "inquiry_id"
    compensation }o--|| seller_order : "seller_order_id"
    compensation_note }o--|| compensation : "compensation_id"
    idempotency_key }o..|| app_user : "user_id"
    inquiry }o..|| seller_order : "seller_order_id"
    notification }o..|| shop_order : "order_id"
    notification }o..|| seller_order : "seller_order_id"
    order_contract_document }o..|| consent_item : "consent_item_id"
    order_contract_document }o--|| shop_order : "order_id"
    order_contract_document }o..|| policy_document : "policy_document_id"
    order_item }o--|| seller_order : "seller_order_id"
    order_item }o..|| sku : "sku_id"
    order_shipping }o--|| shop_order : "order_id"
    order_status_history }o..|| app_user : "actor_user_id"
    order_status_history }o--|| shop_order : "order_id"
    order_status_history }o--|| seller_order : "seller_order_id"
    order_status_history_note }o--|| order_status_history : "order_status_history_id"
    payment }o--|| shop_order : "order_id"
    payment_card }o--|| payment : "payment_id"
    refund }o..|| seller_order : "seller_order_id"
    refund_item }o..|| order_item : "order_item_id"
    return_note }o--|| return_request : "return_request_id"
    return_pickup }o--|| return_request : "return_request_id"
    return_request }o..|| app_user : "decided_by_user_id"
    return_request }o..|| app_user : "inspected_by_user_id"
    return_request }o..|| app_user : "requested_by_user_id"
    return_request }o--|| seller_order : "seller_order_id"
    return_request_item }o--|| order_item : "order_item_id"
    return_request_item }o--|| return_request : "return_request_id"
    seller_order }o--|| shop_order : "order_id"
    seller_order }o..|| seller : "seller_id"
    settlement_item }o..|| compensation : "compensation_id+compensation_bearer"
    settlement_item }o..|| order_item : "order_item_id"
    settlement_item }o..|| seller_order : "seller_order_id"
    shop_order }o..|| app_user : "user_id"
    sku_stock_movement }o..|| shop_order : "order_id"
```

점선은 이 묶음 밖으로 나가는 외래키다. 상자만 있고 선이 없는 표는
외래키로 아무것도 안 가리키고 아무도 안 가리키는 표다.
