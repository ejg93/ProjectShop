package com.projectshop.shop.auth;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.projectshop.shop.auth.PermissionEvaluator.Decision;
import com.projectshop.shop.auth.PermissionEvaluator.Rule;
import com.projectshop.shop.auth.PermissionEvaluator.Target;
import com.projectshop.shop.auth.PermissionRuleLoader.ResourceAction;

/**
 * 한 사용자가 지금 무엇을 할 수 있는지를 통째로 계산한다.
 *
 * <p>화면이 역할 이름으로 판단하지 않게 하려고 만든다. `seller_owner 면 이 버튼을 보인다` 가
 * 화면에 박히는 순간 판정이 두 벌이 되고, 규칙이 바뀔 때 한쪽만 고쳐진다.
 *
 * <p><b>판정 로직을 다시 쓰지 않는다.</b> 여기서 규칙을 새로 해석하면 그것이야말로 두 벌이다.
 * 대신 대표 대상 셋을 만들어 {@link PermissionEvaluator#evaluate} 를 그대로 돌리고,
 * 어느 범위가 열리는지를 답에서 읽는다.
 */
@Component
public class PermissionCatalog {

    /**
     * {@code all} 스코프에서만 덮이는 가상의 행.
     *
     * <p>음수 id 는 실제 행과 안 겹친다. {@code own} 은 내 것이 아니어서, {@code seller} 는
     * 내 소속도 부여 셀러도 아니어서 걸리지 않는다. 그래서 여기서 허용이 나오면 그 규칙은 {@code all} 이다.
     */
    private static final Target SOMEONE_ELSES = Target.of(-1L, -1L);

    private final PermissionRuleLoader loader;
    private final JdbcClient jdbc;

    PermissionCatalog(PermissionRuleLoader loader, JdbcClient jdbc) {
        this.loader = loader;
        this.jdbc = jdbc;
    }

    /**
     * @param scopes             이 동작이 열리는 범위. 비어 있으면 목록에 안 들어간다
     * @param visibleFieldGroups 가장 넓은 허용에서 볼 수 있는 필드 그룹.
     *        <b>비어 있으면 아무것도 못 본다는 뜻이다</b>(`13b`) — 제한이 없으면 그 자원의
     *        그룹이 전부 실린다. 그 전에는 빈 목록이 「제한 없음」이어서
     *        <b>「전부 보임」과 「아무것도 안 보임」이 같은 값</b>이었다
     */
    public record Entry(String resource, String action, List<String> scopes,
            List<String> visibleFieldGroups) {
    }

    /**
     * 목록은 <b>근사치다.</b> 실제 판정은 행마다 갈린다.
     *
     * <p>여기 {@code order:read} 가 {@code own} 으로 떠 있어도 남의 주문은 못 본다.
     * 화면이 버튼을 보일지 정하는 데 쓰는 것이지, 이 목록으로 접근을 허용하면 안 된다.
     * 실제 허용은 언제나 그 자원을 만질 때 {@link PermissionEvaluator#decide} 가 정한다.
     */
    public List<Entry> listFor(long userId) {
        Map<ResourceAction, List<Rule>> byPermission = loader.loadAllRules(userId);
        Set<Long> memberOf = loader.loadSellerMemberships(userId);
        Map<String, List<String>> allGroups = loadFieldGroups();

        List<Entry> entries = new ArrayList<>();
        byPermission.forEach((permission, rules) ->
                toEntry(permission, rules, memberOf, userId, allGroups).ifPresent(entries::add));
        return entries;
    }

    /**
     * 자원마다 어떤 필드 그룹이 있나.
     *
     * <p><b>여기서는 표를 읽는다.</b> 자원 이름이 문자열로 도는 자리라 enum 을 고를 수가 없고,
     * { auth} 가 { order}·{ account} 를 가리키면 의존이 거꾸로 선다(`D23`).
     * 표와 enum 이 어긋나는 것은 { FieldGroupTest} 가 따로 막는다.
     */
    private Map<String, List<String>> loadFieldGroups() {
        Map<String, List<String>> byResource = new java.util.HashMap<>();
        jdbc.sql("select resource, code from permission_field_group order by resource, code")
                .query((rs, rowNum) -> Map.entry(rs.getString("resource"), rs.getString("code")))
                .list()
                .forEach(row -> byResource
                        .computeIfAbsent(row.getKey(), key -> new ArrayList<>())
                        .add(row.getValue()));
        return byResource;
    }

    private Optional<Entry> toEntry(ResourceAction permission, List<Rule> rules,
            Set<Long> memberOf, long userId, Map<String, List<String>> allGroups) {

        Set<String> scopes = new TreeSet<>();
        Set<String> fieldGroups = new TreeSet<>();
        boolean unrestricted = false;

        for (Map.Entry<String, Target> probe : probes(memberOf, userId).entrySet()) {
            Decision decision = PermissionEvaluator.evaluate(rules, memberOf, userId, probe.getValue());
            if (!decision.allowed()) {
                continue;
            }
            scopes.add(probe.getKey());
            if (!decision.visibleFieldGroups().restricted()) {
                unrestricted = true;
            }
            fieldGroups.addAll(decision.visibleFieldGroups().values());
        }

        if (scopes.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Entry(
                permission.resource(),
                permission.action(),
                List.copyOf(scopes),
                unrestricted
                        ? allGroups.getOrDefault(permission.resource(), List.of())
                        : List.copyOf(fieldGroups)));
    }

    /**
     * 범위마다 그 범위에서만 걸리는 대표 대상 하나.
     *
     * <p>셀러 소속이 여럿이면 하나만 넣는다. 소속별로 권한이 갈리는 것은 조직 역할인데,
     * 그건 <b>어느 셀러냐</b>를 화면이 이미 알고 물어보는 자리라 목록의 관심사가 아니다.
     */
    private static Map<String, Target> probes(Set<Long> memberOf, long userId) {
        Map<String, Target> probes = new LinkedHashMap<>();
        probes.put("own", Target.ownedBy(userId));
        memberOf.stream().findFirst()
                .ifPresent(sellerId -> probes.put("seller", Target.ofSeller(sellerId)));
        probes.put("all", SOMEONE_ELSES);
        return probes;
    }
}
