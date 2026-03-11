package com.cms.common.api;

import java.time.OffsetDateTime;

public record ApiErrorResponse(
        String timestamp,
        String path,
        String code,
        String message
) {

    public static ApiErrorResponse of(String path, String code, String message) {
        return new ApiErrorResponse(
                OffsetDateTime.now().toString(),
                path,
                code,
                message
        );
    }
}