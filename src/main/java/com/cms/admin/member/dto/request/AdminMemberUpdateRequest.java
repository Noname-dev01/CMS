package com.cms.admin.member.dto.request;

import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.dto.request.validation.AllowedRoles;
import com.cms.admin.member.dto.request.validation.AllowedStatuses;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 타 관리자 계정 수정 요청 (부분 수정 시맨틱). 필드가 null이면 기존값을 유지하고,
 * userId(로그인 식별자)는 포함하지 않는다(변경 불가 정책 — menu의 upMenuNo 제외 방식).
 * 모든 필드가 null인 무의미 요청은 400으로 거부한다.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "타 관리자 계정 수정 요청 (null 필드는 기존값 유지, userId는 변경 불가)")
public class AdminMemberUpdateRequest {

    @Size(max = 100)
    @Pattern(regexp = ".*\\S.*", message = "이름은 공백일 수 없습니다.")
    @Schema(description = "이름. null이면 기존값 유지", example = "홍길동")
    private String userName;

    @Email
    @Size(max = 100)
    // @Email은 빈 문자열을 통과시키므로, "보내면 공백 불가"를 별도로 강제한다 (null은 기존값 유지)
    @Pattern(regexp = ".*\\S.*", message = "이메일은 공백일 수 없습니다.")
    @Schema(description = "이메일. null이면 기존값 유지", example = "admin01@test.com")
    private String email;

    @AllowedRoles(
            allowed = {Role.ROLE_ADMIN, Role.ROLE_MANAGER},
            message = "지정 가능한 userType은 ROLE_ADMIN, ROLE_MANAGER만 허용됩니다."
    )
    @Schema(description = "권한 유형. null이면 기존값 유지", example = "ROLE_MANAGER",
            allowableValues = {"ROLE_ADMIN", "ROLE_MANAGER"})
    private Role userType;

    @AllowedStatuses(
            allowed = {MemberStatus.ACTIVE, MemberStatus.LOCKED, MemberStatus.DISABLED},
            message = "지정 가능한 status는 ACTIVE, LOCKED, DISABLED만 허용됩니다."
    )
    @Schema(description = "계정 상태. null이면 기존값 유지. DELETED·PASSWORD_EXPIRED는 지정 불가",
            example = "LOCKED", allowableValues = {"ACTIVE", "LOCKED", "DISABLED"})
    private MemberStatus status;

    /** 전부 null인 무의미 요청 거부 — 위반 시 400 VALIDATION_ERROR */
    @AssertTrue(message = "수정할 필드를 최소 1개 이상 지정해야 합니다.")
    @JsonIgnore
    @Schema(hidden = true)
    public boolean isAnyFieldPresent() {
        return userName != null || email != null || userType != null || status != null;
    }
}
