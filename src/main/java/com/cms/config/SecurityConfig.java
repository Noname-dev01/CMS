package com.cms.config;

import com.cms.config.auth.VisitLoggingAuthenticationSuccessHandler;
import com.cms.config.security.ApiAccessDeniedHandler;
import com.cms.config.security.ApiAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

@Configuration
public class SecurityConfig {

    // Security 6.5에서 AntPathRequestMatcher가 removal 예정으로 deprecated → PathPatternRequestMatcher로 교체
    private static final RequestMatcher API_MATCHER = PathPatternRequestMatcher.withDefaults().matcher("/admin/api/**");
    private static final ApiAuthenticationEntryPoint API_AUTH_ENTRY_POINT = new ApiAuthenticationEntryPoint();
    private static final ApiAccessDeniedHandler API_ACCESS_DENIED_HANDLER = new ApiAccessDeniedHandler();
    private static final LoginUrlAuthenticationEntryPoint LOGIN_ENTRY_POINT = new LoginUrlAuthenticationEntryPoint("/admin/login");
    private static final AccessDeniedHandlerImpl DEFAULT_ACCESS_DENIED_HANDLER = new AccessDeniedHandlerImpl();

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           VisitLoggingAuthenticationSuccessHandler successHandler) throws Exception {
        http
                .authorizeHttpRequests((auth) -> auth
                        .requestMatchers("/admin/login", "/admin/login-error").permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**").hasRole("ADMIN")
                        // MANAGER 허용 범위: 대시보드 + 자기 자신 내 정보(페이지 + self API)
                        .requestMatchers("/admin").hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers("/admin/member/info").hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers("/admin/api/members/me", "/admin/api/members/me/**").hasAnyRole("ADMIN", "MANAGER")
                        // 그 외 admin 전부 ADMIN 전용
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .anyRequest().permitAll()
                )
                .formLogin((form) -> form
                        .loginPage("/admin/login")
                        .loginProcessingUrl("/admin/login")
                        .successHandler(successHandler)
                        .failureUrl("/admin/login-error")
                        .permitAll()
                )
                .logout((logout) -> logout
                        .logoutUrl("/admin/logout")
                        .logoutSuccessUrl("/admin/login")
                        .permitAll())
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
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

}
