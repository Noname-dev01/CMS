package com.cms.admin.session.dto.response;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 세션 만료 서비스의 결과. {@code AdminActionLogAspect}가 {@code getMemberId()}·{@code getAuditLabel()} 리플렉션으로
 * 감사 행의 대상 회원·라벨을 뽑으므로 record가 아니라 getter 클래스다(권한관리 결과와 같은 계약).
 * 라벨은 상수·숫자뿐이다 — 세션 ID·핸들은 넣지 않는다.
 */
@Getter
@RequiredArgsConstructor
public class SessionExpireResult {

    private final Long memberId;
    private final int expiredCount;
    private final boolean currentExcluded;

    public String getAuditLabel() {
        return "세션 " + expiredCount + "개 만료" + (currentExcluded ? "(현재 세션 제외)" : "");
    }
}
