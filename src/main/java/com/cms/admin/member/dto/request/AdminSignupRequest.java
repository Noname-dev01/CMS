package com.cms.admin.member.dto.request;

import com.cms.admin.member.domain.Role;
import com.cms.admin.member.dto.request.validation.AllowedRoles;
import com.cms.admin.member.dto.request.validation.MaxUtf8Bytes;
import com.cms.admin.member.dto.request.validation.MinCodePoints;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "관리자 계정 생성 요청")
public class AdminSignupRequest {
        @NotBlank
        @Size(max = 50, message = "아이디는 50자를 초과할 수 없습니다.")
        @Schema(description = "로그인 아이디", example = "admin01")
        private String userId;

        @NotBlank
        @MinCodePoints(value = 15, message = "비밀번호는 15자 이상이어야 합니다.")
        @MaxUtf8Bytes(value = 72, message = "비밀번호는 72바이트(UTF-8 기준)를 초과할 수 없습니다.")
        @Schema(description = "비밀번호", example = "Admin1234567890!")
        private String pwd;

        @NotBlank
        @Size(max = 100, message = "이름은 100자를 초과할 수 없습니다.")
        @Schema(description = "이름", example = "홍길동")
        private String userName;

        @Email @NotBlank
        @Size(max = 100, message = "이메일은 100자를 초과할 수 없습니다.")
        @Schema(description = "이메일", example = "admin01@test.com")
        private String email;

        @NotNull
        @AllowedRoles(
                allowed = {Role.ROLE_ADMIN, Role.ROLE_MANAGER},
                message = "생성 가능한 userType은 ROLE_ADMIN, ROLE_MANAGER만 허용됩니다."
        )
        @Schema(description = "권한 유형", example = "ROLE_ADMIN", allowableValues = {"ROLE_ADMIN", "ROLE_MANAGER"})
        private Role userType;
}
