-- 저작권 게시 중단을 숨김으로(`Q464`, 저작권법 제103조③ · 시행령 제41~43조 `D2` R42).
--
-- 지금까지 중단은 객체와 행을 지웠다 — 그러면 복제·전송자가 재개를 요구해도(제103조③) 되돌릴 것이 없다.
-- 이제 객체를 비공개 버킷으로 옮기고(같은 키) 행은 남겨 숨긴 시각을 적는다. 공개 읽기는 전부 `hidden_at is null` 이다.
-- 47일(통보 3 + 요구 30 + 예정일 14) 뒤 재개 예정이 없으면 `copyright_hidden_purge` 배치가 객체와 행을 지운다.
alter table product_image add column hidden_at timestamptz;
alter table review_image add column hidden_at timestamptz;

comment on column product_image.hidden_at is
    '저작권 신고로 게시 중단된 시각(Q464, R42). 객체는 비공개 버킷에 같은 키로 있다. 47일 뒤 재개 예정이 없으면 배치가 지운다';
comment on column review_image.hidden_at is
    '저작권 신고로 게시 중단된 시각(Q464, R42). 객체는 비공개 버킷에 같은 키로 있다. 47일 뒤 재개 예정이 없으면 배치가 지운다';

-- 파기 배치와 숨김 수 지표가 숨긴 것만 훑는다. 숨긴 사진은 드물어 부분 인덱스가 작다.
create index product_image_hidden_idx on product_image (hidden_at) where hidden_at is not null;
create index review_image_hidden_idx on review_image (hidden_at) where hidden_at is not null;
