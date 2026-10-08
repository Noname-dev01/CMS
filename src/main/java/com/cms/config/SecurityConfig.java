package com.cms.config;

import com.cms.admin.permission.AdminFeature;
import com.cms.admin.permission.AdminPermissionEvaluator;
import com.cms.admin.permission.FeatureKind;
import com.cms.admin.permission.PermissionAction;
import com.cms.config.auth.LockingAuthenticationFailureHandler;
import jakarta.servlet.DispatcherType;
import com.cms.config.auth.VisitLoggingAuthenticationSuccessHandler;
import com.cms.config.ratelimit.RateLimitFilter;
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
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.http.HttpMethod;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.csrf.CsrfFilter;
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

    /** 무인증 공개 정적 리소스 경로. 테스트가 이 값과 static/ 디렉터리·컨트롤러 매핑의 일치를 검증한다. */
    static final String[] STATIC_PUBLIC_PATHS = {"/css/**", "/js/**", "/img/**", "/vendor/**", "/favicon.ico"};

    /**
     * 위임 가능 기능의 URL 게이트: ADMIN 또는 해당 기능의 READ 권한이 있는 MANAGER. 같은 {@link AdminPermissionEvaluator}가 메서드 계층
     * ({@code @RequirePermission})에서도 판정하므로 두 계층이 어긋나지 않는다. 익명은 거부 결정이라 기존처럼 로그인 페이지로 보낸다.
     */
    private static AuthorizationManager<RequestAuthorizationContext> featureReadGate(
            AdminPermissionEvaluator evaluator, AdminFeature feature) {
        return (authentication, context) -> new AuthorizationDecision(
                evaluator.allows(authentication.get(), feature, PermissionAction.READ));
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           VisitLoggingAuthenticationSuccessHandler successHandler,
                                           LockingAuthenticationFailureHandler failureHandler,
                                           SessionRegistry sessionRegistry,
                                           RateLimitFilter rateLimitFilter,
                                           AdminPermissionEvaluator permissionEvaluator) throws Exception {
        http
                .authorizeHttpRequests((auth) -> {
                    auth
                        // 컨테이너의 오류 재디스패치(sendError → /error)만 허용한다. 기본 거부(아래 anyRequest)에서
                        // 이 규칙이 없으면 404·429·403 오류 페이지가 로그인 리다이렉트로 뒤바뀐다. `/error` URL 자체는
                        // 공개하지 않는다 — 직접 요청(REQUEST 디스패치)은 기본 거부에 걸린다.
                        // 반드시 맨 앞에 둔다 (PLAN-default-deny-authorization.md 결정 2, 2026-09-29 승인).
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/admin/login", "/admin/login-error").permitAll()
                        // 비밀번호 재설정 — 비로그인 사용자의 유일한 복구 경로 (2026-07-13 인가 정책 변경 승인)
                        .requestMatchers("/admin/password-reset", "/admin/password-reset/confirm").permitAll()
                        .requestMatchers("/admin/api/password-reset-requests", "/admin/api/password-resets").permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**").hasRole("ADMIN");

                    // 기능 카탈로그(AdminFeature)에서 만든 게이트 — 규칙 순서는 기존과 같다(위: 공개·swagger, 아래: /admin/** 캐치올).
                    // 상시 허용 기능(대시보드·내 정보)은 ADMIN·MANAGER 전원, 위임 가능 기능(공지)은 ADMIN 또는
                    // 조회 권한이 있는 MANAGER(기능 단위 READ 게이트 — 동작별 판정은 핸들러의 @RequirePermission이 한다).
                    // PLAN-menu-permission-management.md §3-1. 위임 불가 기능은 게이트 패턴이 없어 아래 캐치올(ADMIN 전용)에 걸린다.
                    for (AdminFeature feature : AdminFeature.ofKind(FeatureKind.ALWAYS)) {
                        if (!feature.getGatePatterns().isEmpty()) {
                            auth.requestMatchers(feature.getGatePatterns().toArray(String[]::new))
                                    .hasAnyRole("ADMIN", "MANAGER");
                        }
                    }
                    for (AdminFeature feature : AdminFeature.ofKind(FeatureKind.DELEGABLE)) {
                        auth.requestMatchers(feature.getGatePatterns().toArray(String[]::new))
                                .access(featureReadGate(permissionEvaluator, feature));
                    }
                    // 게시판 단위 기능(게시글): 게이트는 "ADMIN 또는 어느 게시판이든 READ가 있는 MANAGER"(기능 단위) — 게시판별 판정은
                    // 핸들러의 @RequireBoardPermission이 한다(2026-10-08 승인, PLAN-board.md 쟁점 2·3).
                    for (AdminFeature feature : AdminFeature.ofKind(FeatureKind.BOARD_SCOPED)) {
                        auth.requestMatchers(feature.getGatePatterns().toArray(String[]::new))
                                .access(featureReadGate(permissionEvaluator, feature));
                    }

                    auth
                        // 그 외 admin 전부 ADMIN 전용 (카탈로그에 없는 /admin/** 포함 — 기본 거부 유지)
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        // 공개 공지 페이지: GET/HEAD만 공개, 그 외 메서드는 명시적으로 차단
                        // (2026-07-28 승인 — anyRequest().permitAll()에 기대지 않고 지금 당장
                        // 비-GET/HEAD를 막는다. denyAll()은 인증 여부·역할과 무관하게 전부 거부한다)
                        .requestMatchers(HttpMethod.GET, "/notices", "/notices/**").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/notices", "/notices/**").permitAll()
                        .requestMatchers("/notices", "/notices/**").denyAll()
                        // 편집기 본문 이미지: GET/HEAD만 공개, 그 외 메서드 명시 차단(2026-10-07 승인, PLAN-html-editor.md).
                        // 공개 여부(공개 공지가 참조하거나 NOTICE 조회 권한자)는 PublicContentImageService가 판정한다.
                        .requestMatchers(HttpMethod.GET, "/content-images/*").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/content-images/*").permitAll()
                        .requestMatchers("/content-images", "/content-images/**").denyAll()
                        // actuator: health만 무인증 공개(로드밸런서 헬스체크용), 나머지는 명시 차단.
                        // management.endpoints.web.exposure.include(설정 레벨 제한)에 더해
                        // Security 레이어에서 이중으로 막아, 노출 설정이 실수로 넓어져도
                        // 뚫리지 않게 한다(PLAN-prod-profile.md 결정 3, 2026-07-29 승인).
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/actuator/**").denyAll()
                        // 정적 리소스 — 4개 접두사는 정적 전용 예약 경로다(컨트롤러 매핑 금지, 테스트가 강제).
                        // 접두사 permit은 핸들러 종류를 가리지 않으므로 GET/HEAD로 한정한다.
                        // /favicon.ico는 파일이 없어도 열어 둬 기존 404를 보존한다(거부하면 브라우저가
                        // 로그인 페이지 HTML을 아이콘으로 받아간다).
                        .requestMatchers(HttpMethod.GET, STATIC_PUBLIC_PATHS).permitAll()
                        .requestMatchers(HttpMethod.HEAD, STATIC_PUBLIC_PATHS).permitAll()
                        // 기본 거부 — 위에서 명시적으로 열지 않은 경로는 인증·역할과 무관하게 전부 거부한다
                        // (2026-09-29 승인, 감사 M-08 — 규칙 누락이 조용히 공개되던 fail-open 제거).
                        // 새 엔드포인트는 반드시 위에 접근 규칙을 추가해야 한다.
                        .anyRequest().denyAll();
                })
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
                )
                // 무인증 공개 엔드포인트 레이트리밋. CsrfFilter *다음*에 둔다 — CSRF 검증에 실패해
                // 컨트롤러까지 도달하지 못하는 요청까지 quota를 소비시키면, 외부 사이트가 피해자
                // 브라우저로 토큰 없는 form POST를 반복시켜 피해자 IP의 quota를 고갈시키는 교차
                // 사이트 공격이 가능해진다(PLAN-public-endpoint-rate-limit.md 쟁점 4).
                .addFilterAfter(rateLimitFilter, CsrfFilter.class);
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
