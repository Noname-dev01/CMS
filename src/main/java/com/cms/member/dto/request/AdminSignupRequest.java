package com.cms.member.dto.request;

import com.cms.member.domain.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AdminSignupRequest(
        @NotBlank
        @Size(min = 4, max = 30)
        String userId,

        @NotBlank
        @Size(min = 8, max = 72)
        String pwd,

        @NotBlank
        String userName,

        @Email
        String email,

        @NotNull
        Role userType
) {
}
