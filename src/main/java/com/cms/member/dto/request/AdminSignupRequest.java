package com.cms.member.dto.request;

import com.cms.member.domain.Role;
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
        @Schema(description = "로그인 아이디", example = "admin01")
        private String userId;

        @NotBlank
        @Schema(description = "비밀번호", example = "Admin1234!")
        private String pwd;

        @NotBlank
        @Schema(description = "이름", example = "홍길동")
        private String userName;

        @Email @NotBlank
        @Schema(description = "이메일", example = "admin01@test.com")
        private String email;

        @NotNull
        @Schema(description = "권한 유형", example = "ROLE_ADMIN", allowableValues = {"ROLE_ADMIN"})
        private Role userType;
}
