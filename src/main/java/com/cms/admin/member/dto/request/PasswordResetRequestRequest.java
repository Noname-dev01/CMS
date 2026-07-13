package com.cms.admin.member.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "비밀번호 재설정 메일 발송 요청")
public class PasswordResetRequestRequest {

    @NotBlank
    @Email
    @Schema(description = "가입 이메일", example = "admin@example.com")
    private String email;
}
