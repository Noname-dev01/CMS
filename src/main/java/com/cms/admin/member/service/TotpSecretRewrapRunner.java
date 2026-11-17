package com.cms.admin.member.service;

import com.cms.admin.member.service.TotpSecretRewrapService.RewrapReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 기동 때 2단계 인증 비밀값을 현재 키로 다시 암호화하고 점검 요약을 남긴다(PLAN-totp-key-rotation.md 결정 3·5).
 *
 * <p><b>예외를 밖으로 내지 않는다</b>(사용자 결정 D2) — 재암호화가 실패해도 옛 키가 설정돼 있으면 로그인은 정상이고,
 * 2단계 비밀값 문제로 공개 사이트 기동이나 복원 스크립트의 자동 기동을 막지 않는다. 실패는 요약(WARN)과 운영 확인 절차가 드러낸다.
 * 웹 서버가 뜬 뒤에 실행되므로(Spring Boot 4.0.8 {@code SpringApplication.run}: refresh → runner) health 200 시점에 끝나지 않았을 수 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TotpSecretRewrapRunner implements ApplicationRunner {

    private final TotpSecretRewrapService rewrapService;

    @Override
    public void run(ApplicationArguments args) {
        RewrapReport report;
        try {
            report = rewrapService.rewrapAll();
        } catch (RuntimeException e) {
            // 메시지는 남기지 않는다 — DB 오류 문구에 값이 섞일 수 있다
            log.error("TOTP 키 점검 실패 — 재암호화·검증을 하지 못했습니다 (예외={})", e.getClass().getName());
            return;
        }
        String summary = String.format("TOTP 키 점검: 현재 키 ID=%s, 옛 키 ID=%s, 전체 %d건, 재암호화 %d건, 복호 불가 %d건, 오류 %d건, 현재 키로 복호 가능 %d/%d",
                report.currentKeyId(), report.previousKeyId().orElse("없음"), report.total(), report.rewrapped(),
                report.unreadable(), report.errors(), report.readableWithCurrentKey(), report.total());
        if (report.healthy()) {
            log.info(summary);
        } else {
            log.warn(summary);
        }
    }
}
