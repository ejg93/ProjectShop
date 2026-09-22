-- 후기를 쓸 자격(`47`).
--
-- **권한 조건에 데이터가 들어오는 첫 자리다**(`D14`). 그전까지 판정은 역할·스코프·상태로만
-- 갈렸는데, 여기서는 「이 사람이 이 줄을 샀나」와 「몇 번 썼나」가 조건이 된다.
--
-- 셋을 내린다 — 한 줄에 하나, 받아 본 뒤에만, 그리고 쓸 수 있는 사람이 누구인가.

-- ---------------------------------------------------------------------------
-- 1. 주문 줄 하나에 살아 있는 후기 하나
-- ---------------------------------------------------------------------------

-- **앱에서만 세면 두 번 눌러 두 건이 된다.** 목록에 같은 사람의 같은 후기가 둘 뜨고,
-- 평점 평균도 그만큼 기운다.
--
-- **부분 유니크다.** 지운 뒤에는 다시 쓸 수 있다 — 전체 유니크로 두면 실수로 지운 사람이
-- 영영 못 쓰고, 그 구제가 운영 문의가 된다. 낮은 별을 지우고 다시 쓰는 길이 열리지만
-- 자기 후기는 어차피 고칠 수 있어서(`review:update`) 그 길로 새로 얻는 것이 없다.
create unique index review_live_per_order_item
    on review (order_item_id)
    where deleted_at is null;

-- ---------------------------------------------------------------------------
-- 2. 받아 본 뒤에만 쓴다
-- ---------------------------------------------------------------------------

-- `46` 이 세운 함수에 조건 하나를 더한다. **상태 축(`StatusPolicy`)이 이미 막는데도 내린다** —
-- 저쪽은 판정을 지나는 입구에만 걸리고, 배치나 시드가 표를 직접 만지면 안 지난다.
--
-- **안 받은 물건의 후기가 제일 비싼 거짓이다.** 다른 소비자가 그것을 근거로 산다.
create or replace function check_review_target() returns trigger as $$
declare
    item_product_id bigint;
    buyer_user_id   bigint;
    shipment_status text;
begin
    select s.product_id, o.user_id, so.status
      into item_product_id, buyer_user_id, shipment_status
      from order_item oi
      join sku s          on s.sku_id = oi.sku_id
      join seller_order so on so.seller_order_id = oi.seller_order_id
      join shop_order o    on o.order_id = so.order_id
     where oi.order_item_id = new.order_item_id;

    if item_product_id is distinct from new.product_id then
        raise exception '후기의 상품이 주문 줄과 다르다 (order_item_id=%, product_id=%)',
            new.order_item_id, new.product_id;
    end if;

    if buyer_user_id is distinct from new.user_id then
        raise exception '후기를 쓴 사람이 주문자가 아니다 (order_item_id=%, user_id=%)',
            new.order_item_id, new.user_id;
    end if;

    -- 반품으로 끝난 줄도 막는다. 물건을 안 가진 사람의 후기라 「써 본 사람의 말」이 아니다.
    if shipment_status not in ('delivered', 'confirmed') then
        raise exception '받아 본 뒤에만 후기를 쓴다 (order_item_id=%, status=%)',
            new.order_item_id, shipment_status;
    end if;

    return new;
end;
$$ language plpgsql;

-- ---------------------------------------------------------------------------
-- 3. 누가 쓰고 누가 보나
-- ---------------------------------------------------------------------------

-- `kind` 는 기본값이 없다(`V75`). `write` 면 감사자 거부가 트리거로 자동으로 붙는다.
insert into permission (resource, action, kind, description) values
    ('review', 'create', 'write', '산 상품에 후기를 쓴다'),
    ('review', 'read',   'read',  '후기를 조회한다'),
    ('review', 'update', 'write', '자기가 쓴 후기를 고친다'),
    ('review', 'delete', 'write', '자기가 쓴 후기를 내린다');

-- 사는 사람. **쓰기 셋이 전부 `own` 이다** — 대상 행의 주인이 자기일 때만 덮는다(`Scope.OWN`).
-- 조회가 `all` 인 것은 후기가 공개 글이라서다. 안 산 사람도 읽고 그것이 후기의 목적이다.
insert into role_permission (role_id, permission_id, scope)
select r.role_id, p.permission_id, v.scope
from (values
    ('review', 'create', 'own'),
    ('review', 'read',   'all'),
    ('review', 'update', 'own'),
    ('review', 'delete', 'own')
) as v (resource, action, scope)
join permission p on p.resource = v.resource and p.action = v.action
join role r on r.code = 'customer';

-- 셀러와 담당자는 읽는다. **답글·신고는 `48` 이 따로 연다** — 읽는 것과 끼어드는 것은
-- 동작이 달라서, 여기서 같이 열면 그 청크가 무엇을 새로 정하는지가 흐려진다.
insert into role_permission (role_id, permission_id, scope)
select r.role_id, p.permission_id, 'all'
from permission p
join role r on r.code in ('seller_owner', 'seller_staff')
where p.resource = 'review' and p.action = 'read';

-- 관리자는 새 권한을 그때마다 받는다(`V3` 의 전체 부여는 그때 있던 권한만 덮었다).
insert into role_permission (role_id, permission_id, scope)
select r.role_id, p.permission_id, 'all'
from permission p
join role r on r.code = 'admin'
where p.resource = 'review';

-- 감사자의 조회 허용만 손으로 넣는다. 쓰기 셋의 거부는 위 `insert into permission` 이
-- 트리거를 깨워 이미 들어갔다(`Q59`).
insert into role_permission (role_id, permission_id, scope, effect)
select r.role_id, p.permission_id, 'all', 'allow'
from permission p
join role r on r.code = 'auditor'
where p.resource = 'review' and p.action = 'read';
