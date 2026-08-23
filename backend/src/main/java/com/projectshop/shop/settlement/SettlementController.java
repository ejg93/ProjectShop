package com.projectshop.shop.settlement;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;

/**
 * 정산서를 조회하는 입구(청크 20).
 *
 * <p><b>경로를 관객별로 안 가른다.</b> {@code 59} 는 갈랐는데 거기는 <b>같은 자원을 보는
 * 사람마다 행 자체가 달라야</b> 했고(비공개 문의), 여기는 <b>범위만 달라진다</b> —
 * 셀러가 보는 정산서와 관리자가 보는 정산서는 같은 것이고 개수만 다르다.
 * 갈리지 않는데 입구를 늘리면 목록 규약을 두 벌 지켜야 한다(`D23` 「어느 쪽을 언제 쓰나」).
 *
 * <p><b>고객은 이 자원에 권한이 없다</b>(`V56`) — 정산은 우리와 셀러 사이의 계산이고
 * 사는 사람이 볼 것이 아니다. 감사자는 읽기가 열려 있다: 돈이 어디로 갔나를 못 보면
 * 감사가 성립을 안 한다.
 */
@RestController
@RequestMapping("/api/settlements")
public class SettlementController {

    private final SettlementQuery query;

    SettlementController(SettlementQuery query) {
        this.query = query;
    }

    /** 볼 수 있는 정산서. 셀러는 자기 것, 관리자·감사자는 전체다 */
    @GetMapping
    public SettlementQuery.Page list(@AuthenticationPrincipal ShopUser user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return query.find(user.id(), page, size);
    }

    /**
     * 정산서 하나와 그 줄 전부.
     *
     * <p>줄이 목록에 안 실리는 이유는 <b>주문 항목 건별</b>이어서다(청크 17) —
     * 목록에 실으면 한 셀러의 한 달치가 수백 줄이 되고 그것을 긁으면 거래 내역 전체가 된다.
     */
    @GetMapping("/{settlementNumber}")
    public SettlementQuery.Detail one(@AuthenticationPrincipal ShopUser user,
            @PathVariable String settlementNumber) {
        return query.findOne(user.id(), settlementNumber);
    }
}
