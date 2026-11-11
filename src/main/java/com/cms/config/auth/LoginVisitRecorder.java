package com.cms.config.auth;

import com.cms.admin.notification.service.NotificationRecorder;
import com.cms.admin.visit.domain.VisitLog;
import com.cms.admin.visit.repository.VisitLogRepository;
import com.cms.common.web.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 로그인 성공 1회 = 방문 1건 기록 + 읽은 지 오래된 본인 알림 정리 + 만료된 remember-me 토큰 행 정리(전 회원). ROLE_ADMIN·ROLE_MANAGER만 대상이다.
 * 폼 로그인(성공 핸들러)과 쿠키 자동 로그인(사후 검증 필터)이 함께 쓴다 — 둘 다 <b>인증 결정이 끝난 뒤</b> 부른다
 * (PLAN-remember-me-login.md 쟁점 4). 실패해도 로그인을 막지 않는다(예외 격리, 에러 로그로 누락 추적).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginVisitRecorder {

    private final VisitLogRepository visitLogRepository;
    private final NotificationRecorder notificationRecorder;
    private final RememberMeTokenRepository rememberMeTokenRepository;
    private final Clock clock;

    public void record(HttpServletRequest request, Authentication authentication) {
        boolean adminOrManager = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_ADMIN") || a.equals("ROLE_MANAGER"));
        if (!adminOrManager) {
            return;
        }
        tryLogVisit(request, authentication);
        tryPurgeOldReadNotifications(authentication);
        tryPurgeExpiredRememberMeTokens(authentication);
    }

    /**
     * 만료된 remember-me 토큰 행 정리(PLAN-remember-me-token-purge.md) — 행은 로그인할 때만 생기므로 로그인이 정리도 맡는다(스케줄러 없음).
     * 버려진 기기의 주인은 다시 로그인하지 않을 수 있어 이 회원 것만이 아니라 전부 대상이다. 대기 상한은 저장소가 묶고, 실패는 로그인을 막지 않는다.
     */
    private void tryPurgeExpiredRememberMeTokens(Authentication authentication) {
        try {
            rememberMeTokenRepository.purgeExpired(clock.instant());
        } catch (Exception e) {
            log.error("remember-me 만료 토큰 정리 실패 — 다음 로그인에서 다시 시도합니다 (user={})", authentication.getName(), e);
        }
    }

    /**
     * 읽은 지 90일 지난 본인 알림 정리(D7·D8). 로그인 경로는 {@code REQUIRES_NEW}로 요청당 커넥션 2개를 잡지 않는다(풀 고갈 규칙) —
     * 호출 시점에 열린 트랜잭션이 없으므로 REQUIRED가 새 트랜잭션을 연다.
     */
    private void tryPurgeOldReadNotifications(Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof CustomUserDetails details) || details.getId() == null) {
            return;
        }
        try {
            notificationRecorder.purgeOldReadNotifications(details.getId());
        } catch (Exception e) {
            log.error("읽은 알림 정리 실패 (user={})", authentication.getName(), e);
        }
    }

    private void tryLogVisit(HttpServletRequest request, Authentication authentication) {
        try {
            // 앱 KST Clock으로 기록 — 대시보드 집계(오늘 카운트·일별 추이)와 같은 시간원.
            VisitLog visitLog = VisitLog.builder()
                    .visitorUserId(authentication.getName())
                    .requestIp(ClientIpResolver.resolve(request))
                    .visitAt(LocalDateTime.now(clock))
                    .build();
            visitLogRepository.save(visitLog);
        } catch (Exception e) {
            log.error("방문 로그 저장 실패 (user={})", authentication.getName(), e);
        }
    }
}
