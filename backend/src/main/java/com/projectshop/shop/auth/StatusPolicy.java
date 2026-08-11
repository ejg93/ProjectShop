package com.projectshop.shop.auth;

/**
 * 자원의 상태가 어떤 동작을 열어 두나. 판정의 여섯 번째 축이다(`D6`).
 *
 * <p><b>구현은 그 자원을 아는 패키지에 둔다.</b> 여기는 인터페이스만이고
 * 주문의 표는 {@code order} 가 들고 있다 — 반대로 하면 {@code auth → order} 의존이 생겨서
 * {@code auth ↔ audit} 에 이어 순환이 둘이 되고, 모듈을 쪼갤 때 막히는 자리가 는다(`D23`).
 *
 * <p>구현이 하나도 없으면 상태 축이 아무 동작에도 안 걸린다. 그것이 이 축을 넣기 전의 상태다.
 */
public interface StatusPolicy {

    /**
     * 이 동작이 허용되는 상태.
     *
     * <p><b>{@link Allowed} 로 답한다.</b> "상태를 안 보는 동작" 과 "허용 상태가 하나도 없는 동작" 이
     * 빈 집합 하나로 겹치면 안 된다(`D23` 「빈 값에 뜻을 싣지 않는다」).
     *
     * @return 상태 축이 안 걸린 동작이면 {@code Everything}, 걸리면 허용 상태 코드의 {@code Only}
     */
    Allowed<String> allowedStatuses(String resource, String action);
}
