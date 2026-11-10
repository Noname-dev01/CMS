package com.cms.config.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * remember-me 기기 하나를 폐기한다 = <b>시리즈 삭제 → 같은 시리즈를 가진 활성 세션 전부 만료</b>.
 * 명시적 로그아웃과 수동 단일 만료가 함께 쓴다(PLAN-remember-me-login.md v7). 삭제가 먼저라 실패하면 세션도 그대로이고 재시도할 수 있다.
 * 이 순서 덕분에 자동 로그인 사후 검증과 경합하지 않는다 — 검증이 행을 봤다면 그 뒤의 만료가 이미 등록된 세션을 찾는다.
 */
@Component
@RequiredArgsConstructor
public class RememberMeDeviceRevoker {

    private final RememberMeTokenRepository tokens;
    private final AdminSessionService sessions;

    public void revokeDevice(String series) {
        if (series == null) {
            return;
        }
        tokens.removeSeries(series);
        sessions.expireSessionsWithSeries(series);
    }
}
