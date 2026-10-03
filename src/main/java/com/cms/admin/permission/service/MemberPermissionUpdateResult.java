package com.cms.admin.permission.service;

import com.cms.admin.permission.dto.response.MemberPermissionMatrixResponse;
import lombok.Getter;

/**
 * 권한 교체의 서비스 내부 결과. 감사 Aspect가 반환 객체의 getter에서만 targetId·targetLabel을 추출하므로(없으면 예외를 삼키고 null로 저장한다)
 * {@link #getMemberId()}와 {@link #getAuditLabel()}을 노출한다. 변경 없음 결과도 같다. 컨트롤러는 {@link #getResponse()}만 응답으로 내보낸다(이 객체는 직렬화되지 않는다).
 */
@Getter
public class MemberPermissionUpdateResult {

    private final Long memberId;
    private final MemberPermissionMatrixResponse response;
    private final String auditLabel;

    public MemberPermissionUpdateResult(Long memberId, MemberPermissionMatrixResponse response, String auditLabel) {
        this.memberId = memberId;
        this.response = response;
        this.auditLabel = auditLabel;
    }
}
