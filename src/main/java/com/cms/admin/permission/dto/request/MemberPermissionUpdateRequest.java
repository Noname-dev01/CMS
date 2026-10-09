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
@Schema(description = "회원 권한 교체 요청 — grants는 그 회원의 위임 가능 기능 허용 집합 전체(빈 배열이면 전부 회수)")
public class MemberPermissionUpdateRequest {

    @NotNull
    @Schema(description = "화면이 조회한 시점의 버전. 다르면 409", example = "3")
    private Long version;

    @NotNull
    @Valid
    @Schema(description = "허용할 (기능, 동작) 목록")
    private List<@NotNull @Valid Grant> grants;

    /**
     * 게시판별 허용 집합 전체(PLAN-board.md 쟁점 5). <b>필수</b>(빈 배열 허용) — 이 필드가 없던 화면(배포 전에 열어 둔 권한관리 화면)의
     * 저장이 게시판 권한을 전부 회수하지 않도록 누락은 400이다.
     */
    @NotNull
    @Valid
    @Schema(description = "허용할 (게시판, 동작) 목록 — 필수, 빈 배열이면 게시판 권한 전부 회수")
    private List<@NotNull @Valid BoardGrant> boardGrants;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Grant {

        @NotNull
        @Schema(description = "기능", example = "BOARD")
        private AdminFeature feature;

        @NotNull
        @Schema(description = "동작", example = "READ")
        private PermissionAction action;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class BoardGrant {

        @NotNull
        @Schema(description = "게시판 ID", example = "3")
        private Long boardId;

        @NotNull
        @Schema(description = "동작", example = "READ")
        private PermissionAction action;
    }
}
