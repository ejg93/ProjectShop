package com.projectshop.shop.auth;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * 로그인할 때 계정을 DB 에서 찾는다.
 *
 * <p>{@code deleted_at is null} 을 조건에 넣는다. `ADR 0007` 은 파기(10a) 후 이메일이
 * {@code null} 이 되므로 탈퇴 계정이 로그인 경로에서 자연히 떨어진다고 봤지만,
 * <b>탈퇴부터 파기까지는 이메일이 살아 있다</b> — 주문 기록 보존 때문에 그 구간이 필요하다.
 *
 * <p>이 조건은 로그인 시점만 막는다. 이미 로그인한 다른 기기는 `5h` 의 생존 확인이 막는다(`ADR 0010`).
 */
@Service
public class ShopUserDetailsService implements UserDetailsService {

    private final JdbcClient jdbc;

    public ShopUserDetailsService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        Optional<ShopUser> found = jdbc.sql("""
                        select user_id, email, password_hash, status
                          from app_user
                         where lower(email) = lower(:email)
                           and deleted_at is null
                        """)
                .param("email", email)
                .query((rs, rowNum) -> new ShopUser(
                        rs.getLong("user_id"),
                        rs.getString("email"),
                        rs.getString("password_hash"),
                        UserStatus.of(rs.getString("status")) == UserStatus.ACTIVE))
                .optional();

        // 없는 계정과 틀린 비밀번호가 같은 결과로 나가야 한다(D14).
        // 여기서 던지는 예외는 컨트롤러가 하나의 문구로 뭉친다.
        return found.orElseThrow(() -> new UsernameNotFoundException("계정이 없다"));
    }

    /**
     * 권한을 여기 안 담는다.
     *
     * <p>판정은 {@link PermissionEvaluator} 하나가 한다. Spring 의 authority 에도 역할을 담으면
     * 같은 판단이 두 벌이 되고, 한쪽만 고치는 날 둘이 어긋난다.
     * 이 객체가 답하는 것은 <b>누구인가</b>까지고, <b>무엇을 할 수 있는가</b>는 판정 엔진이 답한다.
     *
     * <p><b>{@code record} 가 아니라 클래스인 이유는 비밀번호 해시를 지워야 해서다.</b>
     * 이 객체는 인증이 끝나면 세션에 principal 로 들어가 앉는다. 해시를 든 채로 앉으면
     * 세션이 사는 내내 메모리에 남는데, Spring 은 그러라고 {@link CredentialsContainer} 를 두고
     * {@code ProviderManager} 가 인증 직후 {@code eraseCredentials()} 를 부른다.
     * {@code record} 는 불변이라 그 요청에 응답할 방법이 없어서 해시가 그대로 남았다(`D14`).
     *
     * <p>{@code equals} 를 <b>id 로만</b> 본다. 해시까지 비교하면 비밀번호를 바꾸거나 해시가 지워진 뒤에
     * {@code SessionRegistry} 에서 같은 사람을 못 찾는다 — 탈퇴가 세션을 못 끊는다는 뜻이다.
     */
    public static final class ShopUser implements UserDetails, CredentialsContainer {

        private final long id;
        private final String email;
        private final boolean active;

        /** 인증이 끝나면 지워진다. 그 뒤로 이 값을 읽는 코드가 있으면 안 된다 */
        private String passwordHash;

        public ShopUser(long id, String email, String passwordHash, boolean active) {
            this.id = id;
            this.email = email;
            this.passwordHash = passwordHash;
            this.active = active;
        }

        public long id() {
            return id;
        }

        public String email() {
            return email;
        }

        @Override
        public void eraseCredentials() {
            this.passwordHash = null;
        }

        @Override
        public Collection<? extends GrantedAuthority> getAuthorities() {
            return List.of();
        }

        @Override
        public String getPassword() {
            return passwordHash;
        }

        @Override
        public String getUsername() {
            return email;
        }

        @Override
        public boolean isEnabled() {
            return active;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ShopUser that && this.id == that.id;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(id);
        }

        /** 해시는 안 넣는다. 로그·디버거에 찍히는 자리다(`D16`) */
        @Override
        public String toString() {
            return "ShopUser[id=%d, email=%s]".formatted(id, email);
        }
    }
}
