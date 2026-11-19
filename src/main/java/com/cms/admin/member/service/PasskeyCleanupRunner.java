package com.cms.admin.member.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 기동 때 무효 패스키(현재 TOTP가 없거나 그 등록보다 먼저 만든 것)를 지운다(PLAN-passkey-second-factor.md 결정 1).
 * 이전 앱으로 롤백한 동안 앱 밖에서 일어난 TOTP 해제·재등록의 찌꺼기다 — 정리 전에도 유효 조건 쿼리가 쓰지 않으므로 보안 판정은 이것에 기대지 않는다.
 *
 * <p><b>예외를 밖으로 내지 않는다</b>({@code TotpSecretRewrapRunner}와 같은 규칙) — 정리 실패로 공개 사이트 기동을 막지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PasskeyCleanupRunner implements ApplicationRunner {

    private final MemberPasskeyService passkeyService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            int deleted = passkeyService.deleteStalePasskeys();
            if (deleted > 0) {
                log.warn("무효 패스키 {}건을 지웠습니다(현재 2단계 인증 등록 이전에 만든 것)", deleted);
            }
        } catch (RuntimeException e) {
            // 메시지는 남기지 않는다 — DB 오류 문구에 값이 섞일 수 있다
            log.error("무효 패스키 정리 실패 (예외={})", e.getClass().getName());
        }
    }
}
