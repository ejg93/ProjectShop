# ERD — permission

**생성물이다. 손으로 고치지 않는다** — `SchemaErdTest` 가 스키마에서 뽑고,
갈리면 빨개진다. 갱신은 `gradlew integrationTest -Dsnapshot.update=true` 다.

```mermaid
erDiagram
    role_permission }o--|| permission : "permission_id"
    role_permission }o--|| role : "role_id"
    role_permission_field }o--|| permission_field_group : "permission_field_group_id"
    role_permission_field }o--|| role_permission : "role_id+permission_id+effect"
    seller_invitation }o..|| role : "role_id"
    user_role }o--|| role : "role_id"
    user_role }o..|| seller : "seller_id"
    user_role }o..|| app_user : "user_id"
```

점선은 이 묶음 밖으로 나가는 외래키다. 상자만 있고 선이 없는 표는
외래키로 아무것도 안 가리키고 아무도 안 가리키는 표다.
