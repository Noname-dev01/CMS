package com.cms.admin.member.dto.request;

import com.cms.admin.member.dto.request.validation.MaxUtf8Bytes;
import com.cms.admin.member.dto.request.validation.MinCodePoints;
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
    @MinCodePoints(value = 15, message = "비밀번호는 15자 이상이어야 합니다.")
    @MaxUtf8Bytes(value = 72, message = "비밀번호는 72바이트(UTF-8 기준)를 초과할 수 없습니다.")
    @Schema(description = "새 비밀번호", example = "NewAdmin1234567890!")
    private String newPassword;

    @NotBlank
    @Schema(description = "새 비밀번호 확인", example = "NewAdmin1234!")
    private String confirmPassword;
}
