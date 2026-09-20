-- 데모 재고를 눌러 봐도 안 닳게 올린다(`Q128`).
--
-- **시드 중 마지막이다.** 사건을 낳는 시드가 뒤에 오면 그것이 걷어야 하고, `SeedOutboxTest` 가
-- 마지막 파일에서 그 문장을 찾는다. 새 시드를 더할 때 사건을 낳으면 이 뒤에 두고 같이 걷는다.
--
-- **보여 주려고 올린 데이터가 보여 주기를 막고 있었다.** `V902` 의 수량은 실제 판매를 흉내 낸
-- 것이라(티셔츠 조합마다 20, 도마 5) 배포한 사이트에서 몇 번 주문해 보면 `409` 가 난다.
-- 포폴 방문자에게는 그것이 「고장」으로 보인다 — 무엇을 밟는 중이었는지 모르기 때문이다.
--
-- **`V902` 를 직접 안 고친다**(`Q51`). 배포 기준점 뒤라 체크섬이 남의 DB 에 박혀 있다.
-- 되돌리는 대신 덧대는 것은 `V903` 이 이미 쓴 길이다.
--
--
-- **0 인 둘은 그대로 둔다.** 「데모 니트」와 티셔츠 검정 L 은 품절 화면을 밟으려고 0 인 것이라,
-- 채우면 그 갈래를 볼 데이터가 사라진다. 아래 조건이 `on_hand > 0` 인 이유다.
--
-- **`update` 를 직접 안 쓴다.** `V41` 의 `sku_stock_requires_move` 가 `move_stock()` 을
-- 안 지나온 변경을 거부한다 — 이력에 구멍을 안 내려고 건 것이고, 시드라고 비켜 가지 않는다.
--
--
-- **아웃박스를 같이 걷는다.** 이유는 `V903` 과 같다 — 시드가 만든 재고 이동은 사실이 아니라서
-- 발행기가 내보내면 받는 쪽이 진짜 이동과 못 가른다.
--
-- **`V903` 처럼 통째로 안 지운다.** 저쪽은 빈 DB 에서 도는 것이 전제라 표에 있는 것이 전부
-- 시드 것이었는데, **이 파일은 이미 돌고 있는 DB 에도 들어간다.** `delete from outbox_event`
-- 를 쓰면 아직 안 보낸 진짜 사건까지 같이 지운다. 그래서 **이 블록이 만든 것만** 지운다.
do $$
declare
    v_target constant int := 9999;
    v_mark   bigint;
    v_row    record;
begin
    select coalesce(max(outbox_event_id), 0) into v_mark from outbox_event;

    for v_row in
        select ss.sku_id, ss.on_hand
          from sku_stock ss
          join sku s      on s.sku_id = ss.sku_id
          join product p  on p.product_id = s.product_id
          join seller se  on se.seller_id = p.seller_id
         where se.code in ('demo-fashion', 'demo-craft')
           and ss.on_hand > 0
           and ss.on_hand < v_target
    loop
        perform move_stock(v_row.sku_id, v_target - v_row.on_hand, 'adjustment', null);
    end loop;

    delete from outbox_event
     where outbox_event_id > v_mark
       and type = 'shop.sku.stock_moved';
end $$;
