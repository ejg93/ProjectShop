-- 길이 상한을 앱 검증에서 DB 제약으로 내린다(`Q22`, `점검 L` 이 찾았다).
--
-- 열한 자리가 `@Size(max = N)` 에만 걸려 있었다. 앱 검증은 강제 지점 3위라
-- **새 입구가 생기면 빠뜨린다**(`D23` 축 2) — 배치·시드·psql 은 애초에 그 층을 안 지난다.
--
-- 이 저장소는 varchar 를 한 컬럼도 안 쓴다. text + check (length(...)) 가 이미 스물두 곳에
-- 있고(V17·V22·V23·V50·V53·V63 등) 여기는 빠진 자리를 같은 꼴로 채운다.
--
-- **수마다 축이 다르다.** 아래 제약마다 그 수가 어디서 왔는지를 적는다 —
-- 안 적으면 다음 사람이 표준에서 온 값과 우리가 찍은 값을 구분할 수 없고,
-- 제약은 한 번 들어가면 alter 로만 고쳐지므로 그때는 이미 관례가 되어 있다.
--
-- **빈 문자열 허용 여부가 앱과 같아야 한다.** @NotBlank 가 붙은 칸은 between 1 and N 으로
-- 빈 값도 막고, 안 붙은 칸(address2·delivery_memo·source_url)은 <= N 만 본다.
-- 갈리면 앱이 받는 것을 DB 가 거부하고 그 요청은 500 이 된다.
--
-- 넣기 전에 기존 행을 셌다: 전부 상한 한참 아래고 빈 문자열이 0이다.

alter table app_user
    -- 254 는 RFC 5321 §4.5.3.1.3 에서 온다(표준, 2순위). 경로가 꺾쇠를 포함해 256 옥텟이라
    -- 주소 자체는 254 다. local-part 64 와 도메인 255 를 더한 320 은 같은 절이 허용하지 않는다.
    -- 앱 쪽 단일 출처는 `@EmailAddress` 다.
    add constraint app_user_email_length_check
        check (email is null or length(email) between 1 and 254),

    -- 50 은 근거를 못 찾은 값이다(관례, 4순위). 표시 이름 길이를 정한 표준도 관례도 못 봤다.
    add constraint app_user_display_name_length_check
        check (display_name is null or length(display_name) between 1 and 50);

alter table order_shipping
    -- 아래 셋은 **근거 없이 정한 값**이다. 도로명주소 개발자센터는 신청이 있어야 규격을 보고
    -- 택배사 API 규격은 계약자만 본다 — 둘 다 못 봤다.
    --
    -- **택배 연동이 붙는 날 그 규격이 상한을 정한다**(2순위). 우리가 200 을 받아 뒀는데
    -- 택배사가 그보다 짧게 자르면 주소가 조용히 잘리고 물건이 엉뚱한 데로 간다 — 오류가 안 난다.
    -- 그 청크가 이 세 줄을 다시 본다.
    add constraint order_shipping_receiver_name_length_check
        check (length(receiver_name) between 1 and 50),
    add constraint order_shipping_address1_length_check
        check (length(address1) between 1 and 200),

    -- 아래 둘은 @NotBlank 가 없다. 빈 문자열이 앱을 통과하므로 여기서도 막지 않는다.
    add constraint order_shipping_address2_length_check
        check (address2 is null or length(address2) <= 200),
    add constraint order_shipping_delivery_memo_length_check
        check (delivery_memo is null or length(delivery_memo) <= 200);

alter table product
    -- 100 은 관례에서 왔다(4순위). 네이버 스마트스토어가 100 자에서 자르고 Shopify 가 255 다.
    -- 국내 1위 플랫폼에 맞춘 값이고, 검색 노출이 붙으면 그쪽이 실질 규격이 된다.
    -- **200 에서 좁힌 것이다** — 넓히는 것이 좁히는 것보다 쉬워서 지금 관례에 맞춰 둔다.
    add constraint product_name_length_check
        check (length(name) between 1 and 100),

    -- 아래 둘은 셀러에게만 보이는 사유 문구다. 바깥 시스템이 안 봐서 관례가 없고
    -- 우리가 정하면 그것이 전부다(프로젝트 규약, 3순위).
    add constraint product_review_note_length_check
        check (review_note is null or length(review_note) between 1 and 500),
    add constraint product_block_reason_length_check
        check (block_reason is null or length(block_reason) between 1 and 500);

alter table product_option
    -- 50 은 근거를 못 찾은 값이다(관례, 4순위).
    add constraint product_option_name_length_check
        check (length(name) between 1 and 50);

alter table product_substantiation
    -- 500 은 관례다(4순위). RFC 9110 은 8000 옥텟을 SHOULD support 로 권고하고 브라우저 실질
    -- 상한이 2000 근처인데, 우리는 그보다 좁게 잡았다 — 실증 자료 링크라 짧은 것이 정상이고
    -- 넘으면 단축 URL 을 쓰라는 뜻이다. @NotBlank 가 없어서 빈 문자열은 통과시킨다.
    add constraint product_substantiation_source_url_length_check
        check (source_url is null or length(source_url) <= 500);
