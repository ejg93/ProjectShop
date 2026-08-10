package com.projectshop.shop.support;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 배치를 돌리는 스위치. 이 클래스가 없으면 {@code @Scheduled} 가 붙어 있어도 한 번도 안 돈다.
 *
 * <p><b>기동 클래스에 안 붙였다.</b> 붙이면 테스트가 컨텍스트를 띄우는 순간 배치가 같이 돌기 시작해서,
 * 테스트가 만든 데이터를 배치가 건드린다. 별도 설정으로 두면 필요할 때 프로필로 끌 자리가 생긴다.
 *
 * <p>서버가 여럿이 되면 <b>같은 배치가 대수만큼 동시에 돈다.</b> 지금은 1대라 문제가 없고,
 * 늘어나는 순간 잠금이 필요하다 — 그때 볼 것이 이 주석이다.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfig {
}
