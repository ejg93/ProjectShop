# ERD — ops

**생성물이다. 손으로 고치지 않는다** — `SchemaErdTest` 가 스키마에서 뽑고,
갈리면 빨개진다. 갱신은 `gradlew integrationTest -Dsnapshot.update=true` 다.

```mermaid
erDiagram
    compensation }o..|| inquiry : "inquiry_id"
    inquiry }o..|| product : "product_id"
    inquiry }o..|| seller_order : "seller_order_id"
    inquiry }o..|| app_user : "user_id"
    notification }o--|| notification_template : "notification_template_id"
    notification }o..|| shop_order : "order_id"
    notification }o..|| refund : "refund_id"
    notification }o..|| seller_order : "seller_order_id"
    notification }o..|| user_consent : "user_consent_id"
    notification }o..|| app_user : "user_id"
    notification_body }o--|| notification : "notification_id"
    audit_log {
    }
    batch_run {
    }
    outbox_event {
    }
    holiday {
    }
```

점선은 이 묶음 밖으로 나가는 외래키다. 상자만 있고 선이 없는 표는
외래키로 아무것도 안 가리키고 아무도 안 가리키는 표다.
