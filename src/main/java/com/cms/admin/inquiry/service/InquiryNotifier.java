package com.cms.admin.inquiry.service;

import com.cms.admin.notification.service.NotificationRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 새 문의 알림 호출자(PLAN-inquiry.md R4-1). <b>트랜잭션이 없다</b> — 컨트롤러가 {@link InquiryService#submit}(트랜잭션 프록시, 반환 시점에 커밋·커넥션 반환 완료)가
 * 끝난 <b>뒤에</b> 부른다. {@code AFTER_COMMIT} 리스너에서 {@code REQUIRES_NEW}로 저장하면 제출 트랜잭션의 커넥션을 쥔 채 두 번째 커넥션을 요구해, 풀 크기만큼
 * 동시 제출이 오면 풀 획득 대기로 응답이 막힌다(무인증 공개 경로라 동시성을 통제할 수 없다). 로그인 경로({@code VisitLoggingAuthenticationSuccessHandler})와 같은 모양이다.
 *
 * <p>알림 실패는 <b>삼키고 ERROR 로그만</b> 남긴다 — 문의는 이미 커밋됐다(최선 노력). 로그에는 예외 클래스명만 남기고 메시지·원인 체인은 남기지 않는다
 * (데이터 접근 예외 메시지에 값이 섞일 수 있다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InquiryNotifier {

    private final NotificationRecorder notificationRecorder;

    public void notifyReceived() {
        try {
            notificationRecorder.recordInquiryReceived();
        } catch (Exception e) {
            log.error("새 문의 알림 저장 실패 — 문의는 이미 저장됐다 ({})", e.getClass().getName());
        }
    }
}
