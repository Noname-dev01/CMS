package com.cms.admin.session.dto.response;

import java.time.LocalDateTime;
import java.util.List;

/** 회원별로 묶은 활성 세션. 표시 정보(아이디·이름·역할)는 로그인 시점 기준이다. */
public record SessionMemberResponse(
        Long memberId,
        String userId,
        String userName,
        String role,
        int sessionCount,
        LocalDateTime lastRequestAt,
        List<SessionItemResponse> sessions
) {
}
