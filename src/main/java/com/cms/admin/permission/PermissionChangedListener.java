package com.cms.admin.permission;

import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
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

    /**
     * 커밋 성공 경로의 <b>앞선</b> 무효화 — Spring은 {@code AFTER_COMMIT} 콜백을 모두 실행한 <b>뒤에</b> {@code AFTER_COMPLETION}을 실행하므로,
     * 같은 트랜잭션의 알림 저장({@code AFTER_COMMIT}, 연결·락 대기가 길어질 수 있다)이 {@code AFTER_COMPLETION} 무효화를 늦추지 않도록
     * 같은 {@code AFTER_COMMIT} 단계에서 알림보다 먼저(@Order 10 &lt; 100) 한 번 더 폐기한다. 아래 {@code AFTER_COMPLETION} 무효화는
     * 롤백·결과 불명을 위한 백스톱으로 그대로 둔다(무효화는 멱등이다). 메서드 리스너는 메서드의 {@code @Order}만 읽는다 — 클래스에 붙이면 무시된다.
     */
    @Order(10)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommitted(PermissionChangedEvent event) {
        cache.invalidate();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMPLETION)
    public void onChanged(PermissionChangedEvent event) {
        cache.invalidate();
    }
}
