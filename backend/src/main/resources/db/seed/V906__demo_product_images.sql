-- 데모 상품 다섯에 사진을 박는다(`Q141`).
--
-- **사진이 없으면 화면이 `picsum.photos` 를 그린다**(`products/page.tsx`). 상품과 아무 관계
-- 없는 남의 스톡 사진이라 **원목 도마 자리에 딸기가 떴다.** 외부 서비스가 죽으면 그 자리가 빈칸이 된다.
--
-- **왜 시드까지 오나.** 배포 DB 에만 올리면 `backend/README.md` 배포 6단계(데모 데이터를 붓는다)가
-- 그것을 지운다 — 마무리 39차 독립 리뷰가 「닫힘이 설계상 썩는다」고 짚은 자리다.
--
--
-- **열쇠는 손으로 짓지 않았다.** `POST /api/seller/products/{id}/images` 로 올려서
-- **서버가 만든 값을 그대로 옮겨 적었다**(`Q139` 가 연 `GET .../images` 로 읽었다).
-- 손으로 지으면 `V78` 이 요구하는 썸네일 열쇠가 실물과 어긋나고, 그때 화면은
-- **깨진 이미지를 그리면서도 `picsum` 으로 안 떨어진다** — URL 이 `null` 이 아니어서다.
-- 그것이 이 청크의 닫힘을 거짓으로 통과시키는 자리였다.
--
-- **열 개 열쇠가 다 200 인 것을 보고 썼다**(원본 다섯·썸네일 다섯, 2026-09-21 배포 R2 실측).
--
--
-- **사진 출처와 라이선스는 `external-references.md` 가 든다.** 전부 파생이 허용되는 것만 골랐다 —
-- **`ND` 는 쓸 수 없다.** 업로드가 썸네일을 만드는데 그 자체가 파생물이라 조건을 어긴다.
--
-- **`V900`~`V905` 를 직접 안 고친다**(`Q51`). 배포 기준점 뒤라 체크섬이 남의 DB 에 박혀 있다.
--
-- **`V905` 에서 마지막 자리를 넘겨받는다.** 그쪽이 마지막이었던 이유는 재고를 움직여서
-- 아웃박스에 사건을 남기기 때문인데, 이 파일이 뒤에 서면 그 그물이 여기를 본다 — 파일 끝을 본다.
insert into product_image (product_id, object_key, thumbnail_key, original_name,
                           content_type, byte_size, sort_no)
select p.product_id, v.object_key, v.thumbnail_key, v.original_name,
       v.content_type, v.byte_size, 0
  from (values
            ('데모 티셔츠',
             'product/063d5e6d-30e2-49d9-b165-11f397fb5d61/original.jpg',
             'product/063d5e6d-30e2-49d9-b165-11f397fb5d61/thumbnail.jpg',
             'p1.jpg', 'image/jpeg', 630016),

            ('데모 에코백',
             'product/626a42af-0ad0-4563-916e-720c5cf0ff5b/original.png',
             'product/626a42af-0ad0-4563-916e-720c5cf0ff5b/thumbnail.jpg',
             'p2.png', 'image/png', 119808),

            ('데모 니트 (품절)',
             'product/197feaa9-0eaf-43f1-840b-044c5cbd5f53/original.jpg',
             'product/197feaa9-0eaf-43f1-840b-044c5cbd5f53/thumbnail.jpg',
             'p3.jpg', 'image/jpeg', 279552),

            ('데모 원목 도마',
             'product/ddba6f34-ae75-43aa-a4e4-f99a6392c8ba/original.jpg',
             'product/ddba6f34-ae75-43aa-a4e4-f99a6392c8ba/thumbnail.jpg',
             'p4.jpg', 'image/jpeg', 263168),

            ('데모 도안 파일',
             'product/7c07a811-b876-41b6-a0bb-f8dea378ad93/original.jpg',
             'product/7c07a811-b876-41b6-a0bb-f8dea378ad93/thumbnail.jpg',
             'p5.jpg', 'image/jpeg', 312320)
       ) as v (product_name, object_key, thumbnail_key, original_name, content_type, byte_size)
  join product p on p.name = v.product_name
 where not exists (select 1 from product_image i
                    where i.product_id = p.product_id and i.object_key = v.object_key);


-- 아웃박스를 걷는다. **지금은 지울 것이 없다** — `product_image` 에는 사건을 내는 트리거가
-- 없어서(`V70` 은 주문·재고·환불·반품·정산·배치만 본다) 위 `insert` 는 한 건도 안 만든다.
--
-- 그래도 두는 이유가 둘이다. **`SeedOutboxTest` 가 마지막 시드에서 이 문장을 찾는다** —
-- 그 그물은 글자로 재므로 「사건을 안 낸다」를 스스로 못 안다. 그리고 **나중에 이 표에
-- 트리거가 붙는 날** 이 자리가 이미 있어야 한다.
--
-- **범위를 좁혀 지운다**(`V905` 와 같은 이유). 이 파일은 이미 돌고 있는 DB 에도 들어가므로
-- `delete from outbox_event` 를 통째로 쓰면 아직 안 보낸 진짜 사건까지 날아간다.
do $$
declare
    v_mark bigint;
begin
    select coalesce(max(outbox_event_id), 0) into v_mark from outbox_event;

    delete from outbox_event where outbox_event_id > v_mark;
end $$;
