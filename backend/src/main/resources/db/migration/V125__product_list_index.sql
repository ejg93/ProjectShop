-- 상품 공개 목록의 정렬 인덱스(`Q243b`). 공개 목록은 파는 중이고 살아 있는 상품만 읽고
-- `order by created_at desc, product_id desc`(기본) 또는 `name`(이름순)에 `limit` 이다.
--
-- **조건을 단 부분 인덱스다.** 조건이 공개 목록의 조건(`ProductQuery.PUBLIC_WHERE`)과 글자까지 같아야 탄다.
-- 조회기가 최저가를 조인·집계 대신 줄마다의 부분질의로 붙이게 바뀌어서 정렬이 먼저 설 수 있게 됐다 —
-- 로컬 10만 건에서 기본 정렬 28ms → 0.3ms, 이름순 10ms → 0.1ms(`doc/notes/perf-local-100k.md`, 2026-10-03 롤백 실험).
create index product_on_sale_created_at_idx on product (created_at desc, product_id desc)
    where status = 'on_sale' and deleted_at is null;

-- 이름순. 오름·내림 둘 다 이 인덱스를 거꾸로 읽어 탄다(동점은 `Incremental Sort` 가 몇 줄만 다시 센다).
create index product_on_sale_name_idx on product (name)
    where status = 'on_sale' and deleted_at is null;
