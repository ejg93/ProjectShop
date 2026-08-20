-- 배송지의 형식을 규격에 맞춘다(Q7, S4).
--
-- 우편번호는 우정사업본부 고시로 2015-08-01 부터 **국가기초구역번호 5자리**다.
-- 그런데 우리는 `@Size(max = 10)` 자유 텍스트였고 화면도 `maxLength={10}` 이라
-- **어느 층에도 형식이 안 내려가 있었다**(D23 축 2 기준 강제 지점 0).
--
-- 규격이 정한 값을 자유 텍스트로 받으면 나중에 정제를 못 한다. 여섯 자리 옛 번호와
-- 하이픈이 섞인 값과 빈칸이 들어간 값이 같은 컬럼에 쌓이고, 그때는 어느 것이 오타고
-- 어느 것이 옛 형식인지 사람이 하나씩 봐야 한다.


alter table order_shipping add constraint order_shipping_postal_code_check
    check (postal_code ~ '^[0-9]{5}$');

comment on column order_shipping.postal_code is
    '국가기초구역번호 5자리(우정사업본부 고시, 2015-08-01 시행)';


-- 전화번호는 **덜 좁힌다.**
--
-- 우편번호와 달리 형식을 고시가 정하지 않는다. 휴대폰(010)·지역번호(02·031)·
-- 안심번호(050)·인터넷전화(070)가 자릿수가 다 다르고, 그중 무엇이 배송 기사에게
-- 닿는 번호인지는 우리가 정할 일이 아니다.
--
-- 그래서 **숫자와 하이픈만, 9~13자리**로 잡는다. 오타(한글이 섞이거나 자릿수가 어긋난 것)를
-- 거르는 것이 목적이고, 어느 사업자의 번호인지를 판정하려는 것이 아니다.
--
-- 하이픈을 지운 자릿수로 재는 이유는 사람이 넣는 하이픈 위치가 제각각이어서다 —
-- 010-1234-5678 과 01012345678 이 같은 번호이므로 같이 통과해야 한다.
alter table order_shipping add constraint order_shipping_receiver_phone_check
    check (receiver_phone ~ '^[0-9-]+$'
           and length(replace(receiver_phone, '-', '')) between 9 and 13);

comment on column order_shipping.receiver_phone is
    '배송 기사가 연락할 번호. 숫자와 하이픈만 받고 자릿수만 본다(Q7)';
