package com.cms.config.auth;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

@Getter
public class CustomUserDetails implements UserDetails {

    private final Member member;

    /**
     * 로그인 시점에 2단계 인증(TOTP)을 쓰고 있었는가 — {@code TotpAwareAuthenticationProvider}가 중간 상태 토큰을 만들지 정하는 데만 쓴다.
     * 비밀값·암호문은 싣지 않는다(PLAN-totp-two-factor.md 결정 1). 로그인 뒤 갱신된 principal에서는 의미가 없다.
     */
    private final boolean totpEnabled;

    public CustomUserDetails(Member member) {
        this(member, false);
    }

    public CustomUserDetails(Member member, boolean totpEnabled) {
        this.member = member;
        this.totpEnabled = totpEnabled;
    }

    public boolean isTotpEnabled() {
        return totpEnabled;
    }

    public Long getId() {
        return member.getId();
    }

    public String getUserName() {
        return member.getUserName();
    }
    public String getEmail() {
        return member.getEmail();
    }

    public String getUserId() {
        return member.getUserId();
    }

    public Member getMember(){
        return member;
    }

    /**
     * 로그인(비밀번호 단계·패스키 식별) 때의 이 principal과 지금 회원 행이 같은 상태인가 — ACTIVE·같은 역할·같은 비밀번호 해시.
     * 2단계 판정({@code TotpLoginService})과 패스키 단독 로그인({@code PasskeyLoginService})이 회원 행 잠금 뒤 재확인에 쓴다
     * (두 곳에 있던 같은 비교식을 옮겼다 — PLAN-solid-small-cleanup.md 결정 3).
     */
    public boolean matchesCurrent(Member current) {
        if (current.getStatus() != MemberStatus.ACTIVE) {
            return false;
        }
        String principalRole = getAuthorities().stream().map(GrantedAuthority::getAuthority).findFirst().orElse(null);
        return current.getUserType().name().equals(principalRole)
                && Objects.equals(current.getPwd(), getPassword());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(member.getUserType().name()));
    }

    @Override
    public String getPassword() {
        return member.getPwd();
    }

    @Override
    public String getUsername() {
        return member.getUserId();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return member.getStatus() != MemberStatus.LOCKED;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return member.getStatus() != MemberStatus.PASSWORD_EXPIRED;
    }

    @Override
    public boolean isEnabled() {
        return member.getStatus() == MemberStatus.ACTIVE;
    }
}
