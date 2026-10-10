package com.cms.admin.session.dto.response;

import java.util.List;

public record SessionListResponse(int totalSessions, List<SessionMemberResponse> members) {
}
