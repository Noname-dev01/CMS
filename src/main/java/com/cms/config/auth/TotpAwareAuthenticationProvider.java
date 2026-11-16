package com.cms.config.auth;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 폼 로그인의 비밀번호 판정 — 2단계를 켠 회원이면 역할 권한이 없는 중간 상태 토큰({@link TotpAuthorities#PENDING})을 만든다
 * (PLAN-totp-two-factor.md 결정 1).
 *
 * <p>권한 축소를 여기서 하는 이유: 성공 처리기는 컨텍스트가 세션에 저장된 <b>뒤</b>에 돌아서, 그때 줄이면 완전 권한이 잠시
 * 세션에 남는다. 중간 상태 토큰은 역할 권한이 없어 기존 모든 게이트가 거부한다.
 *
 * <p><b>애플리케이션의 유일한 {@code AuthenticationProvider} 빈이어야 한다</b> — Spring Security 7.0.7의 전역 인증 관리자는
 * 이 타입의 빈이 정확히 하나일 때만 그 빈을 쓴다({@code InitializeAuthenticationProviderBeanManagerConfigurer}, 둘 이상이면 INFO
 * 로그만 남기고 쓰지 않는다). 그러면 2단계가 조용히 사라지므로 {@code TotpPendingAuthorizationIntegrationTest}가 고정한다.
 * {@code CompromisedPasswordChecker}는 설정하지 않는다 — 로그인마다 유출 검사를 하지 않는다(member/CLAUDE.md "유출 비밀번호 차단").
 */
@Component
public class TotpAwareAuthenticationProvider extends DaoAuthenticationProvider {

    public TotpAwareAuthenticationProvider(UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        super(userDetailsService);
        setPasswordEncoder(passwordEncoder);
    }

    @Override
    protected Authentication createSuccessAuthentication(Object principal, Authentication authentication, UserDetails user) {
        if (user instanceof CustomUserDetails details && details.isTotpEnabled()) {
            UsernamePasswordAuthenticationToken pending = UsernamePasswordAuthenticationToken.authenticated(
                    principal, null, List.of(new SimpleGrantedAuthority(TotpAuthorities.PENDING)));
            pending.setDetails(authentication.getDetails());
            return pending;
        }
        return super.createSuccessAuthentication(principal, authentication, user);
    }
}
