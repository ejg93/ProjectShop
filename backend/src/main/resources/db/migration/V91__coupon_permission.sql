-- 쿠폰을 만들고 받는 권한(`Q163`).
--
-- **`49`·`50`·`51` 이 표·계산·정산을 세우면서 권한을 하나도 안 만들었다.** 그래서 쿠폰은
-- `psql` 로만 생기고 받는 길도 없었다 — 입구가 빠진 것이 권한부터였다는 뜻이다.
--
-- **자원을 표대로 둘로 가른다.** 정의(`coupon`)는 관리자가 만들고, 발급(`coupon_issue`)은
-- 본인이 받는다. 한 자원으로 합치면 **「쿠폰을 받는다」와 「쿠폰을 만든다」가 같은 권한**이 되고,
-- 그때 `own` 은 아무것도 못 가른다 — 정의에는 주인이 없어서다.

-- `kind` 는 기본값이 없다(`V75`). `write` 면 감사자 거부가 트리거로 자동으로 붙는다.
insert into permission (resource, action, kind, description) values
    ('coupon',       'create', 'write', '할인 쿠폰을 만든다'),
    ('coupon',       'read',   'read',  '쿠폰 정의를 조회한다'),
    ('coupon',       'delete', 'write', '쿠폰을 내려 더 이상 발급되지 않게 한다'),
    ('coupon_issue', 'create', 'write', '쿠폰 코드를 받아 자기 쿠폰함에 담는다'),
    ('coupon_issue', 'read',   'read',  '자기 쿠폰함을 본다');

-- **내리는 권한이 같이 있어야 한다.** 처음에 뺐다가 시험이 되돌렸다 — 기본 창으로 만든 쿠폰
-- (`issue_start_at = now()`, `issue_end_at` 없음)은 **발급을 멈출 길이 없다.**
-- `coupon_issue_window_check` 가 `issue_end_at > issue_start_at` 을 요구해서 창을 뒤로 못 당기고,
-- 그러면 잘못 만든 쿠폰이 영영 열려 있다. `deleted_at` 이 그 유일한 문이라 입구를 같이 낸다.
--
-- 만드는 것은 관리자뿐이다. **셀러 부담 쿠폰도 여기서 난다** — 셀러가 스스로 만들게 하려면
-- 「누가 그 부담을 승인했나」가 정산에 남아야 하고, 그 자리는 이 청크가 안 연다.
insert into role_permission (role_id, permission_id, scope)
select r.role_id, p.permission_id, 'all'
from permission p
join role r on r.code = 'admin'
where p.resource in ('coupon', 'coupon_issue');

-- 사는 사람. **받는 것과 자기 것을 보는 것이 `own` 이다** — 발급 행의 주인이 자기일 때만 덮는다.
--
-- **정의 조회는 `all` 이 아니다.** 코드로 받는 방식이라 목록을 뿌릴 이유가 없고,
-- 뿌리면 아직 안 알린 쿠폰의 코드가 통째로 샌다(`D14`). 그래서 `coupon:read` 를 안 준다 —
-- 받을 때 쓰는 코드는 사람이 밖에서 듣고 와서 친다.
insert into role_permission (role_id, permission_id, scope)
select r.role_id, p.permission_id, 'own'
from permission p
join role r on r.code = 'customer'
where p.resource = 'coupon_issue';

-- 감사자의 조회 허용만 손으로 넣는다. 쓰기의 거부는 위 `insert into permission` 이
-- 트리거를 깨워 이미 들어갔다(`Q59`).
--
-- **발급 조회는 안 준다.** 누가 무슨 쿠폰을 받았나는 감사가 아니라 마케팅 자료고,
-- `coupon_issue` 는 사람마다 행이 서는 표라 전부 열면 그것이 곧 명단이다.
insert into role_permission (role_id, permission_id, scope, effect)
select r.role_id, p.permission_id, 'all', 'allow'
from permission p
join role r on r.code = 'auditor'
where p.resource = 'coupon' and p.action = 'read';
