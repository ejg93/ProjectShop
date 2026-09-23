-- 후기 사진(`Q159`).
--
-- **`46` 이 범위에서 뗀 것이다.** 사진은 컬럼이 아니라 파이프라인이라(열쇠·썸네일·형식 판별·크기·장수)
-- 스키마 청크에 넣으면 아무도 못 채우는 표가 된다. 파이프라인은 상품 사진(`V78`·`V80`)이 이미 세웠고
-- 앱 쪽은 `ImagePipeline` 으로 뺐다 — 이 표는 그 모양을 그대로 받는다.
--
-- **공개 게시물로 다룬다**(사용자 선택 2026-09-23). 후기 글과 같은 급이다 — 쓴 사람이 스스로 공개하고,
-- 남의 개인정보가 담기면 이미 있는 신고 사유(`privacy`)로 내린다. 그래서 동의 항목을 새로 안 세우고
-- 보유기간은 후기와 같다(`data-lifecycle.md`). **대신 파기가 저장소까지 간다** — 후기가 보존기간 끝에
-- 지워질 때 `TransactionPurgeService` 가 이 표의 객체를 먼저 지운다(cascade 를 파기 수단으로 안 쓴다, `D23`).
create table review_image (
    review_image_id bigint not null generated always as identity primary key,

    -- 후기가 없으면 그 사진은 무의미하다. 애그리거트 안쪽이라 cascade 다 — 파기는 위 주석대로
    -- 명시적으로 지우고, cascade 는 개발 중 정리에만 쓰인다.
    review_id bigint not null references review (review_id) on delete cascade,

    -- 저장소의 열쇠. 앱이 만든 것만 들어온다 — `review/{UUID}/{이름}.{확장자}` 다(`media-rules.md` 「두는 곳」).
    object_key text not null,
    thumbnail_key text not null,

    -- 사람에게 보여 줄 이름. **표시에만 쓴다** — 저장 위치를 정하는 데 안 쓴다.
    original_name text not null,

    -- 매직 바이트로 판별한 값이다. 요청 헤더의 `Content-Type` 이 아니다.
    content_type text not null,
    byte_size bigint not null,
    sort_no int not null default 0,
    created_at timestamptz not null default now(),

    -- 목록과 상한의 출처는 `media-rules.md` 「받는 것 — 제한값」이다. 상품 사진과 같은 값이다.
    constraint review_image_content_type_check
        check (content_type in ('image/jpeg', 'image/png')),
    constraint review_image_byte_size_check
        check (byte_size > 0 and byte_size <= 5242880),
    constraint review_image_object_key_length check (length(object_key) <= 200),
    constraint review_image_thumbnail_key_length check (length(thumbnail_key) <= 200),
    constraint review_image_original_name_length check (length(original_name) <= 255),
    constraint review_image_object_key_unique unique (object_key),
    constraint review_image_thumbnail_key_unique unique (thumbnail_key)
);

create index review_image_by_review on review_image (review_id, sort_no);

-- 후기 하나에 10장까지(`media-rules.md`, 사용자 선택 — 상품과 같은 값). 고칠 때
-- `ImagePipeline.MAX_IMAGES_PER_OWNER` 와 그 문서를 같이 고친다 — 세 자리가 갈리면 앱이 받은 것을 DB 가 거절한다.
--
-- **행 잠금을 안 건다** — `V80` 과 같은 판단이다. 여기서 막는 것은 「빠뜨린 입구」지 경합이 아니다.
create or replace function reject_review_image_overflow() returns trigger as $$
declare
    max_images constant int := 10;
    current_count int;
begin
    select count(*) into current_count
      from review_image
     where review_id = new.review_id;
    if current_count >= max_images then
        raise exception '후기 하나에 사진은 % 장까지다(Q159): review_id=%', max_images, new.review_id;
    end if;
    return new;
end;
$$ language plpgsql;

create trigger review_image_limit
    before insert on review_image
    for each row
    execute function reject_review_image_overflow();
