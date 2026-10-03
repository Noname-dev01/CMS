package com.cms.admin.permission;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 권한 저장 트랜잭션이 <b>끝난 직후</b> 캐시를 무효화한다(AFTER_COMPLETION — 커밋·롤백·결과 불명 모두).
 *
 * <p>AFTER_COMMIT만 쓰면 DB는 커밋했는데 호출자가 커밋 예외를 받는 경우(응답 직전 연결 끊김 — 트랜잭션 상태 UNKNOWN)에 리스너가
 * 실행되지 않는다(AFTER_COMMIT은 상태가 COMMITTED일 때만 실행된다). 캐시에는 만료·재확인이 없어 회수한 권한이 계속 허용되고, 같은 집합을 다시
 * 저장해도 "변경 없음" 분기라 복구되지 않는다. 그래서 완료 상태와 무관하게 폐기한다 — {@link PermissionCache#invalidate()}는 실패할 수
 * 없는 메모리 연산이고, 롤백 때의 불필요한 폐기 비용은 다음 요청이 한 번 더 로드하는 것뿐이라 안전하다(값은 DB가 정한다).
 * 커밋과 무효화 사이 수 μs 창의 요청은 이전 값으로 판정한다("다음 요청부터 반영").
 */
@Component
@RequiredArgsConstructor
public class PermissionChangedListener {

    private final PermissionCache cache;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMPLETION)
    public void onChanged(PermissionChangedEvent event) {
        cache.invalidate();
    }
}
