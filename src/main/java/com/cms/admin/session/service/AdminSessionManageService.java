package com.cms.admin.session.service;

import com.cms.admin.log.annotation.AdminActionLogged;
import com.cms.admin.log.constant.AdminActionTypes;
import com.cms.admin.session.dto.response.SessionExpireResult;
import com.cms.admin.session.dto.response.SessionItemResponse;
import com.cms.admin.session.dto.response.SessionListResponse;
import com.cms.admin.session.dto.response.SessionMemberResponse;
import com.cms.common.exception.ConflictException;
import com.cms.common.exception.ResourceNotFoundException;
import com.cms.config.auth.AdminSessionService;
import com.cms.config.auth.AdminSessionService.ActiveSession;
import com.cms.config.auth.AdminSessionService.MemberExpiration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 세션 관리(조회·수동 강제 만료). 레지스트리 조작뿐이라 DB 트랜잭션이 없다. 현재 세션 보호는 이 서비스가 한다 —
 * 기준은 <b>요청 진입 시점의 세션 ID</b>다(PLAN-session-management.md 쟁점 3).
 * 감사({@code @AdminActionLogged})는 서비스 진입 이후의 결과만 기록한다: 성공은 소유 회원·건수 라벨, 실패(404·409)는 대상 없는 FAIL 한 건.
 */
@Service
@RequiredArgsConstructor
public class AdminSessionManageService {

    private final AdminSessionService adminSessionService;
    private final Clock clock;

    public SessionListResponse getSessions(String currentSessionId) {
        String currentHandle = currentSessionId == null ? null : AdminSessionService.handleOf(currentSessionId);

        // listActiveSessions()가 이미 (마지막 요청 내림차순, 회원 ID, 핸들) 순으로 정렬해 돌려준다 — 회원 묶음은 첫 등장 순서를 유지한다.
        Map<Long, List<ActiveSession>> byMember = new LinkedHashMap<>();
        for (ActiveSession session : adminSessionService.listActiveSessions()) {
            byMember.computeIfAbsent(session.memberId(), id -> new ArrayList<>()).add(session);
        }

        List<SessionMemberResponse> members = new ArrayList<>();
        int total = 0;
        for (List<ActiveSession> sessions : byMember.values()) {
            ActiveSession first = sessions.get(0);
            List<SessionItemResponse> items = sessions.stream()
                    .map(s -> new SessionItemResponse(s.handle(), toLocal(s), s.handle().equals(currentHandle)))
                    .toList();
            members.add(new SessionMemberResponse(first.memberId(), first.userId(), first.userName(), first.role(),
                    items.size(), items.get(0).lastRequestAt(), items));
            total += items.size();
        }
        return new SessionListResponse(total, members);
    }

    /** 세션 하나 만료. 현재 세션이면 409, 조회 시점에 활성인 세션이 없으면(이미 만료·소멸) 404. */
    @AdminActionLogged(actionType = AdminActionTypes.SESSION_EXPIRE, targetType = "MEMBER",
            targetIdExpression = "memberId", targetLabelExpression = "auditLabel")
    public SessionExpireResult expireSession(String handle, String currentSessionId) {
        if (currentSessionId != null && AdminSessionService.handleOf(currentSessionId).equals(handle)) {
            throw new ConflictException("현재 사용 중인 세션은 만료할 수 없습니다. 로그아웃을 사용하세요.");
        }
        ActiveSession expired = adminSessionService.expireByHandle(handle)
                .orElseThrow(() -> new ResourceNotFoundException("세션을 찾을 수 없습니다."));
        return new SessionExpireResult(expired.memberId(), 1, false);
    }

    /** 회원의 활성 세션 전부 만료. 현재 세션은 호출자 회원과 무관하게 항상 제외한다. 대상이 없어도 정상(0건)이다. */
    @AdminActionLogged(actionType = AdminActionTypes.SESSION_EXPIRE, targetType = "MEMBER",
            targetIdExpression = "memberId", targetLabelExpression = "auditLabel")
    public SessionExpireResult expireMemberSessions(Long memberId, String currentSessionId) {
        MemberExpiration result = adminSessionService.expireSessionsFor(memberId, currentSessionId);
        return new SessionExpireResult(memberId, result.expiredCount(), result.currentExcluded());
    }

    private LocalDateTime toLocal(ActiveSession session) {
        return LocalDateTime.ofInstant(session.lastRequest(), clock.getZone());
    }
}
