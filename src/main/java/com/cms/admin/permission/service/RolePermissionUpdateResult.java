package com.cms.admin.permission.service;

import com.cms.admin.permission.dto.response.RolePermissionMatrixResponse;
import lombok.Getter;

/**
 * 권한 교체의 서비스 내부 결과. 감사 Aspect가 반환 객체의 getter에서만 targetLabel을 추출하므로 응답 DTO와 감사 라벨을 함께 돌려주고,
 * 컨트롤러는 {@link #getResponse()}만 응답으로 내보낸다(이 객체는 직렬화되지 않는다).
 */
@Getter
public class RolePermissionUpdateResult {

    private final RolePermissionMatrixResponse response;
    private final String auditLabel;

    public RolePermissionUpdateResult(RolePermissionMatrixResponse response, String auditLabel) {
        this.response = response;
        this.auditLabel = auditLabel;
    }
}
