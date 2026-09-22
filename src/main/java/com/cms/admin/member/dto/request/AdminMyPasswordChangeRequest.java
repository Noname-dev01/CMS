package com.cms.admin.member.dto.request;

import com.cms.admin.member.dto.request.validation.MaxUtf8Bytes;
import com.cms.admin.member.dto.request.validation.MinCodePoints;
import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "내 비밀번호 변경 요청")
public class AdminMyPasswordChangeRequest {

    @NotBlank
    @Schema(description = "현재 비밀번호", example = "Admin1234!")
    private String currentPassword;

    @NotBlank
    @MinCodePoints(value = 15, message = "비밀번호는 15자 이상이어야 합니다.")
    @MaxUtf8Bytes(value = 72, message = "비밀번호는 72바이트(UTF-8 기준)를 초과할 수 없습니다.")
    @Schema(description = "새 비밀번호", example = "NewAdmin1234567890!")
    private String newPassword;

    @NotBlank
    @Schema(description = "새 비밀번호 확인", example = "NewAdmin1234!")
    private String confirmPassword;
}
