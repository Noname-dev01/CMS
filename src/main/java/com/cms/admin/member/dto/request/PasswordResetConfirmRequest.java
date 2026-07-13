package com.cms.admin.member.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 토큰·비밀번호를 담으므로 자동 문자열화 금지 — @ToString/@Data를 붙이지 않는다.
 * 이 객체 자체를 로그에 출력해서도 안 된다.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "비밀번호 재설정 실행 요청")
public class PasswordResetConfirmRequest {

    @NotBlank
    @Size(min = 64, max = 64)
    @Pattern(regexp = "^[0-9a-f]{64}$")
    @Schema(description = "메일 링크의 재설정 토큰 (64자 hex)")
    private String token;

    @NotBlank
    @Size(min = 4, max = 100)
    @Schema(description = "새 비밀번호", example = "NewAdmin1234!")
    private String newPassword;

    @NotBlank
    @Schema(description = "새 비밀번호 확인", example = "NewAdmin1234!")
    private String confirmPassword;
}
