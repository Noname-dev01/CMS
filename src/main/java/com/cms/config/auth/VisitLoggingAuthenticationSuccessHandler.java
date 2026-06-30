package com.cms.config.auth;

import com.cms.admin.visit.domain.VisitLog;
import com.cms.admin.visit.repository.VisitLogRepository;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Collection;

/**
 * 로그인 성공 시 ROLE_ADMIN·ROLE_MANAGER 계정의 방문을 VisitLog에 기록한다.
 * 기존 defaultSuccessUrl("/admin", true) 동작을 보존하면서 방문 기록을 추가한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VisitLoggingAuthenticationSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {

    private static final int MAX_IP_LENGTH = 45;

    private final VisitLogRepository visitLogRepository;

    @PostConstruct
    public void init() {
        // 기존 SecurityConfig의 defaultSuccessUrl("/admin", true) 동작 보존
        setDefaultTargetUrl("/admin");
        setAlwaysUseDefaultTargetUrl(true);
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws ServletException, IOException {
        // ROLE_ADMIN·ROLE_MANAGER 로그인만 방문으로 기록한다.
        if (hasAdminOrManagerRole(authentication.getAuthorities())) {
            tryLogVisit(request, authentication);
        }

        super.onAuthenticationSuccess(request, response, authentication);
    }

    private boolean hasAdminOrManagerRole(Collection<? extends GrantedAuthority> authorities) {
        return authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_ADMIN") || a.equals("ROLE_MANAGER"));
    }

    /**
     * 방문 로그 저장을 시도한다.
     * 저장 실패가 로그인 자체를 막지 않도록 예외를 격리하되, 에러 로그로 누락을 추적한다.
     */
    private void tryLogVisit(HttpServletRequest request, Authentication authentication) {
        try {
            String ip = extractClientIp(request);
            VisitLog visitLog = VisitLog.builder()
                    .visitorUserId(authentication.getName())
                    .requestIp(truncateIp(ip))
                    .visitAt(LocalDateTime.now())
                    .build();
            visitLogRepository.save(visitLog);
        } catch (Exception e) {
            log.error("방문 로그 저장 실패 (user={})", authentication.getName(), e);
        }
    }

    /**
     * IP를 추출한다.
     * X-FORWARDED-FOR(마지막 홉) → X-Real-IP → RemoteAddr 순으로 시도한다.
     * AdminActionLogAspect.getClientIp()와 동일 로직(기존 Aspect는 private이라 직접 재사용 불가).
     */
    private String extractClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-FORWARDED-FOR");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            String[] ips = xForwardedFor.split(",");
            return ips[ips.length - 1].trim();
        }

        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) {
            return xRealIp;
        }

        return request.getRemoteAddr();
    }

    /**
     * IP를 컬럼 최대 길이(45)로 절단한다.
     * 조작된 헤더나 과도하게 긴 값이 들어와도 DataException으로 인한 무음 누락을 방지한다.
     */
    private String truncateIp(String ip) {
        if (ip == null) {
            return null;
        }
        return ip.length() > MAX_IP_LENGTH ? ip.substring(0, MAX_IP_LENGTH) : ip;
    }
}
