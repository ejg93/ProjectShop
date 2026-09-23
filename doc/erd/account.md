# ERD — account

**생성물이다. 손으로 고치지 않는다** — `SchemaErdTest` 가 스키마에서 뽑고,
갈리면 빨개진다. 갱신은 `gradlew integrationTest -Dsnapshot.update=true` 다.

```mermaid
erDiagram
    cart }o..|| app_user : "user_id"
    compensation }o..|| app_user : "decided_by_user_id"
    consent_item }o--|| consent_item : "depends_on_id"
    copyright_report }o..|| app_user : "decided_by_user_id"
    coupon }o..|| seller : "seller_id"
    coupon_issue }o..|| app_user : "user_id"
    email_change_request }o--|| app_user : "user_id"
    idempotency_key }o..|| app_user : "user_id"
    inquiry }o..|| app_user : "user_id"
    notification }o..|| user_consent : "user_consent_id"
    notification }o..|| app_user : "user_id"
    order_contract_document }o..|| consent_item : "consent_item_id"
    order_contract_document }o..|| policy_document : "policy_document_id"
    order_status_history }o..|| app_user : "actor_user_id"
    password_reset_token }o--|| app_user : "user_id"
    product }o..|| app_user : "created_by_user_id"
    product }o..|| seller : "seller_id"
    refund }o..|| app_user : "approved_by_user_id"
    refund }o..|| app_user : "requested_by_user_id"
    return_request }o..|| app_user : "decided_by_user_id"
    return_request }o..|| app_user : "inspected_by_user_id"
    return_request }o..|| app_user : "requested_by_user_id"
    review }o..|| app_user : "user_id"
    review_reply }o..|| app_user : "user_id"
    review_report }o..|| app_user : "reporter_user_id"
    review_report }o..|| app_user : "resolved_by_user_id"
    seller_daily_sales }o..|| seller : "seller_id"
    seller_invitation }o--|| app_user : "accepted_user_id"
    seller_invitation }o--|| app_user : "invited_by_user_id"
    seller_invitation }o..|| role : "role_id"
    seller_invitation }o--|| seller : "seller_id"
    seller_member }o--|| seller : "seller_id"
    seller_member }o--|| app_user : "user_id"
    seller_order }o..|| seller : "seller_id"
    settlement }o..|| app_user : "payout_decided_by_user_id"
    settlement }o..|| app_user : "payout_requested_by_user_id"
    settlement }o..|| seller : "seller_id"
    shop_order }o..|| app_user : "user_id"
    user_consent }o--|| consent_item : "consent_item_id"
    user_consent }o--|| app_user : "user_id"
    user_role }o..|| seller : "seller_id"
    user_role }o..|| app_user : "user_id"
    webhook_delivery }o..|| outbox_event : "outbox_event_id"
    webhook_delivery }o--|| webhook_endpoint : "webhook_endpoint_id"
    webhook_endpoint }o--|| seller : "seller_id"
```

점선은 이 묶음 밖으로 나가는 외래키다. 상자만 있고 선이 없는 표는
외래키로 아무것도 안 가리키고 아무도 안 가리키는 표다.
