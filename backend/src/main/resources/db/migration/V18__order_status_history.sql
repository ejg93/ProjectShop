-- 주문 상태 이력. 전이마다 한 행을 남긴다(ADR 0007).
--
-- 현재 상태만 들고 있으면 "언제 배송됐나" 를 답할 수 없다. 청약철회 기간은 배송완료 시각부터
-- 세므로(D2 R3) 시각이 없으면 분쟁에서 근거가 없다.
--
-- 전이마다 시각 컬럼을 다는 방법도 있었다. 전이가 11개인데 그러면 상태머신을 컬럼으로 펼치게 된다.
--
-- 법이 이 테이블을 두 갈래로 건드린다(D2 R6).
--   보존 — 취소·청약철회·구매확정 전이는 "계약 또는 청약철회 등에 관한 기록" 이라 5년이다.
--          주문과 같은 기간이고 파기도 주문과 같이 돈다(청크 10a).
--   열람 — 제6조 제3항이 소비자에게 거래기록을 열람할 방법을 제공하라고 한다.
--          주문 상세 화면이 이 테이블을 시간순으로 그대로 읽는다.
--
-- 감사 로그(V10)와 겹쳐 보이지만 대상이 다르다. 감사 로그는 권한 변경과 거부를 남기고
-- 이건 주문의 생애를 남긴다. "누가 이 주문을 취소했나" 는 여기서 답한다.

create table order_status_history (
    order_status_history_id bigint not null generated always as identity primary key,

    -- 두 층 중 한쪽을 가리킨다(D7). 결제는 shop_order 에, 배송은 seller_order 에 붙는다.
    --
    -- 한 테이블로 받은 이유는 소비자가 보는 것이 주문 하나의 생애 전체라서다.
    -- 층마다 나누면 타임라인을 만들 때마다 union 이고, 새 조회에서 한쪽을 빠뜨리면
    -- 이력이 반만 보인다. 그 빠짐은 화면에 오류로 안 드러난다.
    --
    -- restrict 다. 주문을 못 지우는 것이 아니라, 지울 때 이력부터 지우게 만드는 것이다 —
    -- cascade 를 파기 수단으로 쓰지 않는다(D23).
    order_id        bigint references shop_order (order_id)          on delete restrict,
    seller_order_id bigint references seller_order (seller_order_id) on delete restrict,

    -- 최초 행은 이전 상태가 없다. 주문이 생기는 순간이 그것이다.
    from_status text,
    to_status   text not null,

    -- 누가 옮겼나(D7 「누가 옮기나」).
    --   customer  구매자
    --   seller    셀러
    --   admin     관리자 강제 전이
    --   system    결제 모듈과 배치. 남길 사람이 없다
    actor_type    text not null,
    actor_user_id bigint references app_user (user_id) on delete restrict,

    -- 관리자 강제 전이의 사유. 정상 경로가 아니라서 왜 그랬는지가 안 남으면
    -- 나중에 데이터가 왜 이 모양인지 아무도 모른다(D7).
    reason text,

    -- 전이가 일어난 시각. 행을 넣는 시각과 다를 수 있다 —
    -- 결제 만료 배치는 5분마다 돌아서 30분이 찬 시점과 기록하는 시점이 갈린다(D7).
    occurred_at timestamptz not null default now(),

    -- 정확히 한 층을 가리킨다. 둘 다 비면 무엇의 이력인지 모르고,
    -- 둘 다 차면 결제 이력인지 배송 이력인지 갈리지 않는다.
    constraint order_status_history_target_check
        check ((order_id is not null) <> (seller_order_id is not null)),

    -- 상태 값 목록이 층마다 다르다. 한 테이블에 받았으므로 어느 층인지 보고 가른다 —
    -- 안 가르면 배송 상태가 결제 이력에 들어가도 통과한다.
    constraint order_status_history_status_check
        check (
            case when order_id is not null then
                to_status in ('payment_pending', 'paid', 'payment_expired', 'payment_failed')
                and (from_status is null or from_status in
                     ('payment_pending', 'paid', 'payment_expired', 'payment_failed'))
            else
                to_status in ('preparing', 'shipping', 'delivered', 'confirmed',
                              'cancelled', 'return_requested', 'returned')
                and (from_status is null or from_status in
                     ('preparing', 'shipping', 'delivered', 'confirmed',
                      'cancelled', 'return_requested', 'returned'))
            end
        ),

    -- 같은 상태로 옮기는 것은 전이가 아니다. 남으면 타임라인에 같은 줄이 반복된다.
    constraint order_status_history_moved_check
        check (from_status is distinct from to_status),

    constraint order_status_history_actor_type_check
        check (actor_type in ('customer', 'seller', 'admin', 'system')),

    -- 사람이 한 전이는 누구인지 남는다. 시스템은 지목할 사람이 없다.
    -- 한쪽만 걸면 "관리자가 옮겼는데 누구인지 모르는" 행이 생긴다.
    constraint order_status_history_actor_user_check
        check ((actor_type = 'system') = (actor_user_id is null)),

    -- 강제 전이에 사유를 안 적으면 남는 것이 "관리자가 바꿨다" 뿐이다.
    constraint order_status_history_admin_reason_check
        check (actor_type <> 'admin' or reason is not null)
);

-- 타임라인을 시간순으로 읽는 조회가 이 테이블의 주 용도다.
create index order_status_history_order_idx
    on order_status_history (order_id, occurred_at)
 where order_id is not null;

create index order_status_history_seller_order_idx
    on order_status_history (seller_order_id, occurred_at)
 where seller_order_id is not null;

-- 고친 이력은 이력이 아니다. 잘못 넣었으면 반대 전이를 새 행으로 남긴다.
--
-- 파기(청크 10a)는 delete 라 막지 않는다. 5년이 지나 주문과 같이 사라지는 것은 정상 경로다.
create or replace function reject_status_history_update() returns trigger
language plpgsql as $$
begin
    raise exception '상태 이력은 고칠 수 없다. 잘못된 전이는 새 행으로 되돌린다';
end;
$$;

create trigger order_status_history_no_update
    before update on order_status_history
    for each row execute function reject_status_history_update();
