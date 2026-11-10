package com.cms.config.auth;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

@Getter
public class CustomUserDetails implements UserDetails {

    private final Member member;

    /**
     * 이 로그인이 쓰는 remember-me 기기(시리즈, DB의 정식 값). 없으면 {@code null}.
     * 세션 레지스트리가 이 principal을 쥐고 있어 수동 강제 만료·로그아웃이 기기 토큰을 찾는 데 쓴다 — 인증 판단에는 쓰지 않는다
     * (PLAN-remember-me-login.md 쟁점 2). 세션 등록 뒤에 기록될 수 있어(폼 로그인) 다른 스레드에서 읽도록 {@code volatile}.
     */
    private volatile String rememberMeSeries;

    public CustomUserDetails(Member member) {
        this.member = member;
    }

    public String getRememberMeSeries() {
        return rememberMeSeries;
    }

    public void setRememberMeSeries(String rememberMeSeries) {
        this.rememberMeSeries = rememberMeSeries;
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
