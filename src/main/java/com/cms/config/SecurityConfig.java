package com.cms.config;

import com.cms.config.auth.LockingAuthenticationFailureHandler;
import com.cms.config.auth.VisitLoggingAuthenticationSuccessHandler;
import com.cms.config.security.AdminSessionExpiredStrategy;
import com.cms.config.security.ApiAccessDeniedHandler;
import com.cms.config.security.ApiAuthenticationEntryPoint;
import static com.cms.common.api.GlobalApiExceptionHandler.API_MATCHER;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.http.HttpMethod;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.session.HttpSessionEventPublisher;

@Configuration
public class SecurityConfig {

    // API_MATCHER는 GlobalApiExceptionHandler가 소유한다(정적 임포트로 재사용) —
    // 핸들러 없는 경로의 404 JSON/HTML 분기도 동일 매처를 써야 컨텍스트 경로·매트릭스
    // 파라미터가 섞인 경로에서 인가 판정과 어긋나지 않는다.
    private static final ApiAuthenticationEntryPoint API_AUTH_ENTRY_POINT = new ApiAuthenticationEntryPoint();
    private static final ApiAccessDeniedHandler API_ACCESS_DENIED_HANDLER = new ApiAccessDeniedHandler();
    private static final LoginUrlAuthenticationEntryPoint LOGIN_ENTRY_POINT = new LoginUrlAuthenticationEntryPoint("/admin/login");
    private static final AccessDeniedHandlerImpl DEFAULT_ACCESS_DENIED_HANDLER = new AccessDeniedHandlerImpl();

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           VisitLoggingAuthenticationSuccessHandler successHandler,
                                           LockingAuthenticationFailureHandler failureHandler,
                                           SessionRegistry sessionRegistry) throws Exception {
        http
                .authorizeHttpRequests((auth) -> auth
                        .requestMatchers("/admin/login", "/admin/login-error").permitAll()
                        // 비밀번호 재설정 — 비로그인 사용자의 유일한 복구 경로 (2026-07-13 인가 정책 변경 승인)
                        .requestMatchers("/admin/password-reset", "/admin/password-reset/confirm").permitAll()
                        .requestMatchers("/admin/api/password-reset-requests", "/admin/api/password-resets").permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**").hasRole("ADMIN")
                        // MANAGER 허용 범위: 대시보드 + 자기 자신 내 정보(페이지 + self API)
                        .requestMatchers("/admin").hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers("/admin/member/info").hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers("/admin/api/members/me", "/admin/api/members/me/**").hasAnyRole("ADMIN", "MANAGER")
                        // 공지사항 관리: ADMIN·MANAGER 모두 CRUD 가능 (2026-07-20 승인)
                        .requestMatchers("/admin/notice/manage", "/admin/notice/**").hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers("/admin/api/notices", "/admin/api/notices/**").hasAnyRole("ADMIN", "MANAGER")
                        // 그 외 admin 전부 ADMIN 전용
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        // 공개 공지 페이지: GET/HEAD만 공개, 그 외 메서드는 명시적으로 차단
                        // (2026-07-28 승인 — anyRequest().permitAll()에 기대지 않고 지금 당장
                        // 비-GET/HEAD를 막는다. denyAll()은 인증 여부·역할과 무관하게 전부 거부한다)
                        .requestMatchers(HttpMethod.GET, "/notices", "/notices/**").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/notices", "/notices/**").permitAll()
                        .requestMatchers("/notices", "/notices/**").denyAll()
                        // actuator: health만 무인증 공개(로드밸런서 헬스체크용), 나머지는 명시 차단.
                        // management.endpoints.web.exposure.include(설정 레벨 제한)에 더해
                        // Security 레이어에서 이중으로 막아, 노출 설정이 실수로 넓어져도
                        // 뚫리지 않게 한다(PLAN-prod-profile.md 결정 3, 2026-07-29 승인).
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/actuator/**").denyAll()
                        .anyRequest().permitAll()
                )
                .formLogin((form) -> form
                        .loginPage("/admin/login")
                        .loginProcessingUrl("/admin/login")
                        .successHandler(successHandler)
                        .failureHandler(failureHandler)
                        .permitAll()
                )
                .logout((logout) -> logout
                        .logoutUrl("/admin/logout")
                        .logoutSuccessUrl("/admin/login")
                        .permitAll())
                // 타 관리자 상태·권한 변경 시 대상자 세션 강제 만료를 위한 세션 등록·추적.
                // maximumSessions(-1): 동시 세션 수를 제한하지 않아(로그인 정책 무변경)
                // SessionRegistry 등록과 만료 추적만 활성화한다.
                .sessionManagement((session) -> session
                        .maximumSessions(-1)
                        .sessionRegistry(sessionRegistry)
                        .expiredSessionStrategy(new AdminSessionExpiredStrategy()))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            if (API_MATCHER.matches(request)) {
                                API_AUTH_ENTRY_POINT.commence(request, response, authException);
                            } else {
                                LOGIN_ENTRY_POINT.commence(request, response, authException);
                            }
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            if (API_MATCHER.matches(request)) {
                                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                                if (auth == null || auth instanceof AnonymousAuthenticationToken) {
                                    // CsrfFilter가 인증 체크보다 먼저 동작하므로, 미인증 요청의 CSRF 실패는
                                    // accessDeniedHandler로 도달한다. 이 경우 403 대신 401을 반환해야 한다.
                                    API_AUTH_ENTRY_POINT.commence(request, response,
                                            new InsufficientAuthenticationException("인증이 필요합니다."));
                                } else {
                                    API_ACCESS_DENIED_HANDLER.handle(request, response, accessDeniedException);
                                }
                            } else {
                                DEFAULT_ACCESS_DENIED_HANDLER.handle(request, response, accessDeniedException);
                            }
                        })
                );
        return http.build();
    }

    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    /**
     * 세션 소멸(로그아웃·타임아웃) 이벤트를 SessionRegistry에 전달해
     * 레지스트리의 세션 정보가 실제 세션 수명과 동기화되도록 한다.
     */
    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

}
