-- 길이 상한이 **아예 없던** 자유 텍스트 칸을 채운다(`Q73`, 점검 O 가 찾았다).
--
-- `V65` 가 「앱에만 있던 상한을 DB 로 내린」 것이라면 여기는 **어느 층에도 없던 칸**이다.
-- 그래서 찾는 방법이 달랐다 — `V65` 는 `@Size` 목록에서 내려왔고, 이건 문서(`D23` 「길이 상한은
-- 앱과 DB 양쪽에 둔다」)에서 코드로 내려가야 보인다. **게이트가 못 보던 자리이기도 하다**:
-- `LengthConstraintTest` 는 `pg_constraint` 에서 이미 있는 `%length_check` 만 걷어서
-- 없는 칸은 구조적으로 안 세어졌다. 같은 청크가 그 반대 방향 검사를 세운다.
--
-- 빈 문자열 규칙은 `V65` 와 같다: `@NotBlank` 가 붙은 칸은 `between 1 and N`,
-- 안 붙은 칸은 `is null or length(...) <= N` 이다. 갈리면 앱이 받는 것을 DB 가 거부해 500 이 된다.

alter table return_pickup
    -- 짝인 `order_shipping` 과 같은 수를 쓴다. 두 표가 같은 것을 담는데 상한이 다르면
    -- 배송지에서 되는 주소가 수거지에서 안 되고, 그 차이에 근거가 없다.
    -- 그쪽 수의 출처는 `V65` 에 적혀 있다 — 도로명주소·택배사 규격을 **못 봐서 정한 값**이라
    -- 택배 연동이 붙는 날 여섯 줄을 같이 다시 본다.
    add constraint return_pickup_sender_name_length_check
        check (length(sender_name) between 1 and 50),
    add constraint return_pickup_address1_length_check
        check (length(address1) between 1 and 200),

    -- 아래 둘은 `not null` 이 아니다. 빈 문자열은 앱이 통과시키므로 여기서도 안 막는다.
    add constraint return_pickup_address2_length_check
        check (address2 is null or length(address2) <= 200),
    add constraint return_pickup_pickup_memo_length_check
        check (pickup_memo is null or length(pickup_memo) <= 200);

alter table product_option_value
    -- 옵션 **이름**은 @Size(max = 50) 인데 **값**은 @NotBlank 뿐이었다(점검 O 가 반대 방향 대조를 세우다 찾았다).
    -- 셀러가 상품 등록에서 직접 넣는 칸이라 입구가 있다. 이름과 같은 50 으로 맞춘다 —
    -- 한 줄에 같이 보이는 값이라 한쪽만 길면 화면이 깨진다.
    add constraint product_option_value_value_length_check
        check (length(value) between 1 and 50);

alter table product
    -- 2000 은 **같은 저장소의 자유 텍스트 상한**에서 왔다(관례, 4순위) — `inquiry.question`·
    -- `inquiry.answer` 가 `V53` 에서 2000 이다. 상품 설명 길이를 정한 표준도 업계 규격도 못 봤다.
    -- **상세 페이지가 서는 청크가 이 줄을 다시 본다** — 이미지와 표가 들어오면 글자 수의 뜻이 달라진다.
    -- 넓히는 것이 좁히는 것보다 쉬워서 지금은 형제 칸에 맞춰 둔다.
    add constraint product_description_length_check
        check (description is null or length(description) <= 2000);
