package com.cms.admin.session.dto.response;

import java.time.LocalDateTime;

/** 세션 한 건. {@code handle}은 세션 ID의 해시이며 원문 세션 ID는 어디에도 없다. */
public record SessionItemResponse(String handle, LocalDateTime lastRequestAt, boolean current) {
}
