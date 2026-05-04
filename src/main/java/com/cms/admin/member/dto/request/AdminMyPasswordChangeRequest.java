package com.cms.admin.member.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
    @Size(min = 4, max = 100)
    @Schema(description = "새 비밀번호", example = "NewAdmin1234!")
    private String newPassword;

    @NotBlank
    @Schema(description = "새 비밀번호 확인", example = "NewAdmin1234!")
    private String confirmPassword;
}
