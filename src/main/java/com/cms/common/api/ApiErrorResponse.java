package com.cms.common.api;

import java.time.LocalDateTime;

public record ApiErrorResponse(
        LocalDateTime timestamp,
        String path,
        String code,
        String message
) {

    public static ApiErrorResponse of(String path, String code, String message) {
        return new ApiErrorResponse(
                LocalDateTime.now(),
                path,
                code,
                message
        );
    }
}