package com.cms.admin.message.service;

import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.message.repository.AdminMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 모든 쪽지 API가 요청마다 호출하는 <b>현재 자격 확인</b>(PLAN-admin-message.md D16, §5-J, R1-4). 세션의 역할·상태는 로그인 당시 스냅샷이고
 * 상태·역할 변경 후 세션 만료는 최선 노력이며 인스턴스 로컬이라, 낡은 세션이 쪽지를 계속 쓰지 못하게 DB의 현재 값으로 판정한다.
 *
 * <p>{@code REQUIRES_NEW + readOnly + READ_COMMITTED}의 <b>스칼라 조회</b>다(R3-2) — 호출자에 외부 RR 트랜잭션이 있어도 그 낡은
 * 스냅샷("ACTIVE")을 읽지 않고, 엔티티 로딩·캐시된 엔티티 기반 판정을 하지 않는다. 가드는 서비스 호출 <b>전에</b> 끝난다(서비스 진입점의
 * 첫 DB 접근이 잠금 쓰기여야 하므로). 가드 통과와 서비스 실행 사이의 짧은 틈에 상태가 바뀌는 경합은 수용한다
 * (보장은 "가드 실행 시점의 DB 현재값"). 이 가드는 쪽지 API에 한정한다 — 다른 기능은 기존 세션 계약 그대로다.
 */
@Component
@RequiredArgsConstructor
public class MessageActorGuard {

    /** 고정 문구 — 상태·역할 어느 쪽이 사유인지 구분하지 않는다. */
    static final String DENIED_MESSAGE = "쪽지를 사용할 수 없는 계정입니다.";

    private final AdminMessageRepository adminMessageRepository;

    /** {@code ACTIVE}이고 역할이 ADMIN·MANAGER일 때만 통과한다. 그 밖은 403({@link AccessDeniedException}, 고정 문구). */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true, isolation = Isolation.READ_COMMITTED)
    public void requireActive(Long memberId) {
        boolean allowed = memberId != null && adminMessageRepository.findActorView(memberId)
                .filter(view -> view.getStatus() == MemberStatus.ACTIVE)
                .filter(view -> view.getUserType() == Role.ROLE_ADMIN || view.getUserType() == Role.ROLE_MANAGER)
                .isPresent();
        if (!allowed) {
            throw new AccessDeniedException(DENIED_MESSAGE);
        }
    }
}
