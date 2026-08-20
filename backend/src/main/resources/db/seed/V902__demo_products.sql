-- 데모 상품(6b-1). `local` 로 띄웠을 때 `/products` 가 0건이던 것을 채운다.
--
-- **손으로 넣던 것을 옮긴 것이다.** `14d`·`11-7`·`13e`·`14c` 를 밟을 때마다 상품을 SQL 로
-- 만들어 넣었고, 그 값이 사람마다 달라서 **본 화면이 서로 달랐다**(`15-1` 에서 실제로 그랬다).
--
--
-- 고른 기준은 하나다 — **화면에서 갈리는 것을 데이터로 밟는다.**
--
--   옵션 있음 / 없음        조합 라벨이 있는 줄과 없는 줄(`14b`·장바구니)
--   재고 0                  품절 표시와 담기 차단
--   배송비 있음 / 무료      총액 표시(`14d`, R24 1호). 무료는 「무료」로 적는다
--   약정 있음 / 없음        공급시기 고지(`14c`, R21). null 은 법정 3영업일이다
--   청약철회 제한           상품 상세의 제한 고지(`14b`, R4). **주문제작은 동의 칸까지 밟는다**(`Q6`)
--
-- 하나라도 빠지면 그 갈래는 손으로 넣어야 하고, 그러면 다시 사람마다 다른 화면을 본다.
--
--
-- 셀러가 둘인 것도 뜻이 있다 — **한 주문이 묶음 둘로 갈리는 것**을 밟아야 배송비가 셀러마다
-- 붙는 것과 취소·반품의 최소 단위가 묶음이라는 것이 화면에 드러난다(`D7`).
--
-- 상품은 `demo-fashion` 것이 셋, `demo-craft` 것이 둘이다.


-- 상품 다섯.
--
-- `created_by_user_id` 는 그 셀러의 사장이다. 아무나 넣으면 「누가 올렸나」가 거짓이 된다.
insert into product (seller_id, created_by_user_id, name, description, status,
                     is_withdrawal_restricted, withdrawal_restriction_reason,
                     supply_lead_days)
select s.seller_id, u.user_id, v.name, v.description, 'on_sale',
       v.restricted, v.reason, v.lead_days
  from (values
            -- 옵션이 있고 재고가 넉넉한 기본형. 배송비가 붙는다.
            ('demo-fashion', '데모 티셔츠', '면 100%. 색상과 크기를 고르실 수 있습니다.',
             false, null, null::int),

            -- 옵션이 없는 단품. 조합 라벨이 없는 줄이 화면에서 어떻게 보이는지 여기서 밟는다.
            ('demo-fashion', '데모 에코백', '한 가지 크기로만 나옵니다.',
             false, null, null::int),

            -- 재고 0. 품절 표시와 담기 차단이 걸린다.
            ('demo-fashion', '데모 니트 (품절)', '입고 예정입니다.',
             false, null, null::int),

            -- 공급시기를 약정한 상품(R21). 화면이 「7영업일」이라고 적어야 그 약정이 선다.
            ('demo-craft', '데모 원목 도마', '주문을 받고 손으로 깎습니다.',
             true, 'made_to_order', 7),

            -- 청약철회가 제한되는데 **동의를 받는 종류가 아니다**(제17조제2항5호).
            -- 제공이 개시되면 성립하므로 주문서에 동의 칸이 안 나온다 — made_to_order 와 갈린다.
            ('demo-craft', '데모 도안 파일', '결제 후 내려받는 디지털 파일입니다.',
             true, 'digital_content', null::int)
       ) as v (seller_code, name, description, restricted, reason, lead_days)
  join seller s on s.code = v.seller_code
  join app_user u on u.email = case v.seller_code
                                   when 'demo-fashion' then 'fashion-owner@example.com'
                                   else 'craft-owner@example.com'
                               end;


-- 옵션. 티셔츠에만 둔다 — 나머지는 옵션 없는 쪽을 밟는 것이 목적이다.
insert into product_option (product_id, name, sort_no)
select p.product_id, v.name, v.sort_no
  from (values ('색상', 0), ('크기', 1)) as v (name, sort_no)
  join product p on p.name = '데모 티셔츠';

insert into product_option_value (product_option_id, value, sort_no)
select po.product_option_id, v.value, v.sort_no
  from (values ('색상', '검정', 0), ('색상', '흰색', 1),
               ('크기', 'M', 0),   ('크기', 'L', 1)) as v (option_name, value, sort_no)
  join product p on p.name = '데모 티셔츠'
  join product_option po on po.product_id = p.product_id and po.name = v.option_name;


-- 조합. 티셔츠는 색상 2 × 크기 2 = 넷이다.
--
-- **넷 중 하나만 재고를 0 으로 둔다**(검정 L). 상품 전체가 품절인 것과
-- 고른 조합만 품절인 것은 화면에서 다르게 보여야 한다.
--
-- **조합마다 넣고 그 자리에서 선택지를 묶는다.** 한 번에 넣으면 어느 sku 가 어느 조합인지를
-- 이어 줄 값이 없다 — sku 에는 조합을 적는 컬럼이 없고(그것이 `sku_option_value` 의 일이다)
-- 가격도 재고도 조합을 가리키는 이름이 아니다.
do $$
declare
    v_product_id bigint;
    v_sku_id     bigint;
    v_combo      record;
begin
    select product_id into v_product_id from product where name = '데모 티셔츠';

    for v_combo in
        select * from (values ('검정', 'M', 20), ('검정', 'L', 0),
                              ('흰색', 'M', 20), ('흰색', 'L', 20)) as t (color, size, stock)
    loop
        insert into sku (product_id, price_incl_vat, stock_count)
        values (v_product_id, 29000, v_combo.stock)
        returning sku_id into v_sku_id;

        insert into sku_option_value (sku_id, product_option_value_id)
        select v_sku_id, pov.product_option_value_id
          from product_option po
          join product_option_value pov on pov.product_option_id = po.product_option_id
         where po.product_id = v_product_id
           and ((po.name = '색상' and pov.value = v_combo.color)
             or (po.name = '크기' and pov.value = v_combo.size));
    end loop;
end $$;

-- 옵션 없는 상품의 조합.
--
-- **옵션이 없어도 sku 는 있다.** 파는 단위가 sku 라 그것이 없으면 살 수가 없다 —
-- 「옵션 없음」은 조합이 하나뿐인 상태지 조합이 없는 상태가 아니다(`D4`).
insert into sku (product_id, price_incl_vat, stock_count)
select p.product_id, v.price, v.stock
  from (values ('데모 에코백', 12000, 50),
               ('데모 니트 (품절)', 89000, 0),
               ('데모 원목 도마', 45000, 5),
               ('데모 도안 파일', 8000, 999)) as v (name, price, stock)
  join product p on p.name = v.name;


-- 배송비를 셀러마다 다르게 둔다.
--
-- 둘 다 기본값(3,000원)이면 **무료배송이 화면에서 어떻게 보이는지를 못 밟는다.**
-- `14d` 가 「무료배송은 그렇게 적는다 — 줄을 빼면 「없나」와 「안 적었나」가 안 갈린다」고
-- 정해 놨는데, 그 줄을 볼 데이터가 없었다.
update seller set default_shipping_fee = 0 where code = 'demo-craft';


-- 시행 전인 동의 판을 걷어낸다.
--
-- `V900` 의 마지막 insert 가 `join consent_item ci on ci.is_required` 라 **판을 안 가린다.**
-- 개정판을 미리 넣어 두는 설계(`V27` 불변 트리거)에서 그러면 **아직 시행 안 된 판에
-- 동의한 것으로 기록된다** — `V36`(약관 제3판, 시행 이레 뒤)이 들어오면서 실제로 그렇게 됐다.
--
-- **운영 경로 둘은 이 함정을 안 밟는다.** `SignupService.currentConsentItems` 와
-- `ConsentService.findItem` 이 둘 다 `effective_at <= now()` 를 본다.
-- 같은 실수가 `MeConsentTest` 의 픽스처에도 있었고 `D2-7` 에서 고쳤다 —
-- **판을 가리는 것을 빠뜨리기 쉬운 자리가 셋이었다는 뜻이다.**
--
-- `V900` 을 직접 안 고친다. 이미 적용된 파일을 고치면 그 체크섬으로 도는 로컬 DB 가 깨진다 —
-- 시행된 판을 못 고치게 한 것과 같은 이유고(`policy_document_immutable`), 되돌리는 대신 덧댄다.
delete from user_consent uc
 using consent_item ci
 where ci.consent_item_id = uc.consent_item_id
   and ci.effective_at > now();
