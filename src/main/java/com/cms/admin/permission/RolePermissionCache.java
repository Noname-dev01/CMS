package com.cms.admin.permission;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 허용 행 스냅샷 캐시(PLAN-menu-permission-management.md §5). 단일 인스턴스 전제다(세션 레지스트리와 같은 한계).
 *
 * <ul>
 *   <li>generation 카운터: {@link #invalidate()}가 올린다. 로드는 시작 전 generation을 기억했다가 설치 직전에 같을 때만 설치한다 —
 *       로드 도중 무효화가 끼면 그 결과로 이번 요청만 판정하고 다음 요청이 다시 로드한다(낡은 값이 "최신"으로 설치되는 경합 방지).</li>
 *   <li>로드는 <b>호출자 트랜잭션과 격리된 독립 짧은 읽기 트랜잭션</b>(REQUIRES_NEW)에서 한다 — 사이드바 렌더링처럼 이미 열린 읽기
 *       트랜잭션 안에서 읽으면 REPEATABLE READ의 낡은 스냅샷으로 회수된 권한이 "최신"으로 설치될 수 있다.</li>
 *   <li>로드 실패는 fail-closed: ERROR 로그를 남기고 이번 판정은 빈 스냅샷(MANAGER의 위임 기능 전부 거부)으로 한다. 실패는 캐시하지 않아
 *       다음 요청이 재시도한다. ADMIN과 상시 허용 기능은 DB를 보지 않으므로 영향이 없다.</li>
 * </ul>
 */
@Slf4j
@Component
public class RolePermissionCache {

    private record Holder(long generation, PermissionSnapshot snapshot) { }

    private final RolePermissionRepository repository;
    private final TransactionTemplate loadTransaction;
    private final AtomicLong generation = new AtomicLong();
    private volatile Holder holder;

    public RolePermissionCache(RolePermissionRepository repository, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.loadTransaction = new TransactionTemplate(transactionManager);
        this.loadTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.loadTransaction.setReadOnly(true);
    }

    /** 현재 스냅샷. 요청 안에서는 한 번 받아 같은 값을 계속 쓴다(여러 번 부르면 중간에 무효화될 수 있다). */
    public PermissionSnapshot snapshot() {
        Holder current = holder;
        if (current != null && current.generation() == generation.get()) {
            return current.snapshot();
        }
        return load();
    }

    /** 허용 행이 바뀐 커밋 뒤 호출한다. 실패할 수 없는 메모리 연산이다. */
    public void invalidate() {
        generation.incrementAndGet();
        holder = null;
    }

    private synchronized PermissionSnapshot load() {
        Holder current = holder;
        if (current != null && current.generation() == generation.get()) {
            return current.snapshot(); // 기다리는 동안 다른 스레드가 이미 로드했다(단일 비행)
        }
        long startedAt = generation.get();
        PermissionSnapshot loaded;
        try {
            List<Object[]> rows = loadTransaction.execute(status -> repository.findAllGrantRows());
            loaded = toSnapshot(rows == null ? List.of() : rows);
        } catch (RuntimeException e) {
            log.error("권한 허용 행 로드 실패 — 이번 판정은 MANAGER 위임 기능 전부 거부(fail-closed)", e);
            return PermissionSnapshot.EMPTY;
        }
        if (generation.get() == startedAt) {
            holder = new Holder(startedAt, loaded);
        }
        return loaded;
    }

    /** 모르는 기능·동작 행, 위임 불가 기능 행은 WARN 후 무시한다 — 코드에서 기능을 지웠는데 행이 남아도 로딩 전체가 실패하지 않는다. */
    static PermissionSnapshot toSnapshot(List<Object[]> rows) {
        Set<PermissionSnapshot.Grant> grants = new HashSet<>();
        for (Object[] row : rows) {
            String role = String.valueOf(row[0]);
            AdminFeature feature = parse(AdminFeature.class, row[1]);
            PermissionAction action = parse(PermissionAction.class, row[2]);
            if (feature == null || action == null) {
                log.warn("알 수 없는 권한 행을 무시한다: role={}, feature={}, action={}", row[0], row[1], row[2]);
                continue;
            }
            if (feature.getKind() != FeatureKind.DELEGABLE || !feature.supports(action)) {
                log.warn("위임할 수 없는 권한 행을 무시한다: role={}, feature={}, action={}", row[0], row[1], row[2]);
                continue;
            }
            grants.add(new PermissionSnapshot.Grant(role, feature, action));
        }
        return new PermissionSnapshot(grants);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, Object value) {
        try {
            return Enum.valueOf(type, String.valueOf(value));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
