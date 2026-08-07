package com.projectshop.shop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 스프링 배선이 실제로 뜨는지만 본다.
 *
 * <p>단언이 없는 것이 맞다. 컨텍스트가 안 뜨면 이 테스트가 예외로 죽는다 —
 * 빈 하나를 잘못 등록했을 때 <b>여기가 제일 먼저</b> 깨져서 원인을 좁혀 준다.
 */
class BackendApplicationTests extends PostgresTestBase {

    @Test
    @DisplayName("스프링 컨텍스트가 뜬다")
    void contextLoads() {
    }
}
