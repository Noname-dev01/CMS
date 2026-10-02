package com.cms.admin.permission.dto.request;

import com.cms.admin.permission.AdminFeature;
import com.cms.admin.permission.PermissionAction;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "역할 권한 교체 요청 — grants는 그 역할의 위임 가능 기능 허용 집합 전체(빈 배열이면 전부 회수)")
public class RolePermissionUpdateRequest {

    @NotNull
    @Schema(description = "화면이 조회한 시점의 버전. 다르면 409", example = "3")
    private Long version;

    @NotNull
    @Valid
    @Schema(description = "허용할 (기능, 동작) 목록")
    private List<@NotNull @Valid Grant> grants;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Grant {

        @NotNull
        @Schema(description = "기능", example = "NOTICE")
        private AdminFeature feature;

        @NotNull
        @Schema(description = "동작", example = "READ")
        private PermissionAction action;
    }
}
