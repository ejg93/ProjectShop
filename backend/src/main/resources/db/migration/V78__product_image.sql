-- 상품 사진을 담는다(`27`, `media-rules.md`).
--
-- **제한값을 앱이 아니라 여기에도 건다**(`D23` 축 2). 업로드 입구가 하나뿐인 지금은 앱 검증으로
-- 충분해 보이지만, **새 입구가 생기면 빠뜨리는 것이 3위의 성질**이다 — 관리자 일괄 등록이든
-- 이관 스크립트든 `insert` 를 하는 다음 자리는 이 제약을 못 지나간다.

create table product_image (
    product_image_id bigint not null generated always as identity primary key,

    -- 상품이 없으면 그 상품의 사진은 무의미하다. 애그리거트 안쪽이라 cascade 다.
    product_id bigint not null references product (product_id) on delete cascade,

    -- 저장소의 열쇠. 앱이 만든 것만 들어온다(`media-rules.md` 「두는 곳」) —
    -- `{자원}/{UUID}/{이름}.{확장자}` 고 버킷 이름은 설정이 든다.
    --
    -- **원본 이름을 여기 안 쓴다.** 쓰면 경로 탈출과 이름 충돌이 성립한다.
    object_key text not null,

    -- 썸네일의 열쇠. 원본과 한 행에 둔다 — 갈라 두면 「썸네일 없는 이미지」 상태가 생기고,
    -- 그것을 목록 화면이 또 다뤄야 한다(`media-rules.md` 「썸네일을 못 만들면」).
    thumbnail_key text not null,

    -- 사람에게 보여 줄 이름. **표시에만 쓴다** — 저장 위치를 정하는 데 안 쓴다.
    original_name text not null,

    -- 매직 바이트로 판별한 값이다. 요청 헤더의 `Content-Type` 이 아니다(`media-rules.md`).
    content_type text not null,

    byte_size bigint not null,

    -- 화면에 뿌리는 순서. 입력 순서에 기대면 행을 고칠 때마다 순서가 흔들린다.
    sort_no int not null default 0,

    created_at timestamptz not null default now(),

    -- 허용 목록이다. 자유 텍스트로 두면 **우리가 안 받기로 한 형식이 데이터로 들어온다** —
    -- SVG 는 스크립트를 품고(OWASP), GIF 는 썸네일이 한 장을 전제하며,
    -- WebP 는 JDK 가 읽지도 쓰지도 못한다(네이티브 라이브러리를 안 들였다).
    constraint product_image_content_type_check
        check (content_type in ('image/jpeg', 'image/png')),

    -- 5 MiB. 값의 출처는 `media-rules.md` 「받는 것 — 제한값」이고, 고칠 때 둘을 같이 고친다.
    constraint product_image_byte_size_check
        check (byte_size > 0 and byte_size <= 5242880),

    -- 길이 상한은 앱과 DB 양쪽에 둔다(coding-rules.md). 열쇠는 앱이 만들어서
    -- 모양이 정해져 있고(product/{UUID}/{이름}.{확장자}) 200 이면 두 배 넉넉하다.
    constraint product_image_object_key_length check (length(object_key) <= 200),
    constraint product_image_thumbnail_key_length check (length(thumbnail_key) <= 200),
    -- 파일 이름. 흔한 파일 시스템의 상한과 같은 값으로 둔다.
    constraint product_image_original_name_length check (length(original_name) <= 255),

    -- 같은 열쇠가 두 행에 있으면 하나를 지울 때 다른 하나가 없는 파일을 가리킨다.
    constraint product_image_object_key_unique unique (object_key),
    constraint product_image_thumbnail_key_unique unique (thumbnail_key)
);

-- 상품 하나의 사진을 순서대로 읽는다. 목록·상세가 둘 다 이 모양으로 묻는다.
create index product_image_by_product on product_image (product_id, sort_no);
