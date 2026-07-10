package com.cms.config.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AdminSessionService {

    private final SessionRegistry sessionRegistry;

    /**
     * 지정 회원의 등록된 모든 세션을 만료 처리한다.
     * principal의 equals/hashCode에 의존하지 않고(CustomUserDetails가 equals 미구현)
     * member id를 직접 비교해 대상 principal을 찾는다.
     *
     * <p>만료된 세션의 다음 요청은 ConcurrentSessionFilter가 가로채
     * {@code AdminSessionExpiredStrategy}에 따라 거부된다.
     */
    public void expireSessionsFor(Long memberId) {
        if (memberId == null) {
            return;
        }

        sessionRegistry.getAllPrincipals().stream()
                .filter(principal -> principal instanceof CustomUserDetails userDetails
                        && memberId.equals(userDetails.getId()))
                .flatMap(principal -> sessionRegistry.getAllSessions(principal, false).stream())
                .forEach(SessionInformation::expireNow);
    }
}
