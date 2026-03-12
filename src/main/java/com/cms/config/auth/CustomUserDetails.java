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

    public CustomUserDetails(Member member) {
        this.member = member;
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
