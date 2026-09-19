# ERD — product

**생성물이다. 손으로 고치지 않는다** — `SchemaErdTest` 가 스키마에서 뽑고,
갈리면 빨개진다. 갱신은 `gradlew integrationTest -Dsnapshot.update=true` 다.

```mermaid
erDiagram
    cart_item }o..|| sku : "sku_id"
    copyright_report }o..|| app_user : "decided_by_user_id"
    copyright_report }o--|| product : "product_id"
    copyright_report }o--|| product_image : "product_image_id"
    inquiry }o..|| product : "product_id"
    order_item }o..|| sku : "sku_id"
    product }o..|| app_user : "created_by_user_id"
    product }o..|| seller : "seller_id"
    product_image }o--|| product : "product_id"
    product_option }o--|| product : "product_id"
    product_option_value }o--|| product_option : "product_option_id"
    product_substantiation }o--|| product : "product_id"
    sku }o--|| product : "product_id"
    sku_option_value }o--|| product_option_value : "product_option_value_id"
    sku_option_value }o--|| sku : "sku_id"
    sku_stock }o--|| sku : "sku_id"
    sku_stock_movement }o..|| shop_order : "order_id"
    sku_stock_movement }o--|| sku : "sku_id"
```

점선은 이 묶음 밖으로 나가는 외래키다. 상자만 있고 선이 없는 표는
외래키로 아무것도 안 가리키고 아무도 안 가리키는 표다.
