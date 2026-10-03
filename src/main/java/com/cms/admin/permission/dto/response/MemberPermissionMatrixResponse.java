package com.cms.admin.permission.dto.response;

import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.permission.AdminFeature;
import com.cms.admin.permission.FeatureKind;
import com.cms.admin.permission.PermissionAction;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
@Schema(description = "회원의 권한 매트릭스 — 카탈로그(기능·동작) + 현재 유효 허용값 + 버전")
public class MemberPermissionMatrixResponse {

    private final Long memberId;
    private final String userId;
    private final String userName;
    private final MemberStatus status;
    private final Long version;
    private final List<ActionColumn> actions;
    private final List<FeatureRow> features;

    @Getter
    @AllArgsConstructor
    public static class ActionColumn {
        private final PermissionAction action;
        private final String label;
    }

    /** grantedActions는 판정기와 같은 의미의 <b>유효 허용값</b>이다(상시 허용=지원 동작 전부, 위임 불가=빈 배열, 위임 가능=DB 행 ∩ 지원 동작 ∩ READ 의존). */
    @Getter
    @AllArgsConstructor
    public static class FeatureRow {
        private final AdminFeature feature;
        private final String label;
        private final FeatureKind kind;
        private final List<PermissionAction> supportedActions;
        private final List<PermissionAction> grantedActions;
    }
}
