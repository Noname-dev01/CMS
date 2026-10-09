package com.cms.common.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link FileStorage}에 쓴 파일을 트랜잭션 결과에 맞춰 정리하는 공유 유틸. 소비자는 본문 이미지·게시글 첨부·공지 첨부(루트)와
 * 회원 서비스·프로필 이미지 마이그레이션 러너(프로필 네임스페이스)다 — 예외 처리 정책이 복제되지 않도록 한 곳에 둔다.
 */
@Slf4j
public final class FileStorageTransactionSupport {

    private FileStorageTransactionSupport() {
    }

    /**
     * store() 성공 직후 등록한다 — 트랜잭션이 <b>확정 롤백</b>되면 방금 쓴 파일을 정리한다. 등록 자체가 실패하면(활성 트랜잭션 동기화 없음 등)
     * 즉시 파일을 정리한 뒤 원 예외를 다시 던진다 — 이때 정리(delete) 자체가 또 실패하면 정리 실패 예외가 원 예외를 가리지 않도록
     * {@link Throwable#addSuppressed}로 붙인 뒤 원 예외를 던진다.
     *
     * <p><b>삭제하는 경우는 {@code STATUS_ROLLED_BACK}이면서 {@code beforeCommit}이 불리지 않았을 때(커밋을 시도하기 전에 롤백) 하나뿐이다.</b>
     * Spring은 커밋 단계 예외(DB는 커밋됐지만 응답이 유실된 경우 포함)에서도 {@code STATUS_ROLLED_BACK}을 넘기므로(PR A 실측) 상태 코드만으로는
     * 확정 롤백을 알 수 없고, 롤백 자체가 실패하면 {@code STATUS_UNKNOWN}이 온다. 이 둘에서 DB가 커밋돼 있을 수 있는데 파일을 지우면
     * "행은 있는데 파일이 없는" 복구 불가 상태가 된다. 그래서 그 밖에는 보존하고 WARN만 남긴다 — 최악이 "행 없는 파일"이고 수동 회수 절차가 정리한다
     * (PLAN-board.md 쟁점 7·R5-1, v8·v10).
     */
    public static void deleteOnRollback(FileStorage storage, String storageKey, String namespace, String logContext) {
        deleteOnRollback(() -> storage.delete(storageKey, namespace), "namespace=" + namespace + ", " + logContext, storageKey);
    }

    /** 네임스페이스 없는 루트 영역 파일용 {@link #deleteOnRollback(FileStorage, String, String, String)}(본문 이미지·첨부). */
    public static void deleteOnRollback(FileStorage storage, String storageKey) {
        deleteOnRollback(() -> storage.delete(storageKey), "namespace=<root>", storageKey);
    }

    private static void deleteOnRollback(Runnable delete, String where, String storageKey) {
        try {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                private boolean commitAttempted;

                @Override
                public void beforeCommit(boolean readOnly) {
                    commitAttempted = true;
                }

                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_COMMITTED) {
                        return;
                    }
                    if (status == STATUS_ROLLED_BACK && !commitAttempted) {
                        delete.run();
                        return;
                    }
                    // 커밋 시도 이후의 비커밋이거나 롤백 실패 — DB 결과를 모른다. 지우지 않고 남긴다(수동 회수가 정리).
                    log.warn("트랜잭션 결과 불명 — 파일을 삭제하지 않고 보존한다. {}, storageKey={}, status={}, commitAttempted={}",
                            where, storageKey, status, commitAttempted);
                }
            });
        } catch (RuntimeException registrationFailure) {
            try {
                delete.run();
            } catch (RuntimeException cleanupFailure) {
                registrationFailure.addSuppressed(cleanupFailure);
            }
            throw registrationFailure;
        }
    }

    /**
     * 커밋 후(afterCommit)에만 실제 파일을 삭제한다. 삭제 실패는 예외를 전파하지 않고
     * {@code logContext}를 포함해 로그로 남긴다 — DB는 이미 커밋되어 되돌릴 수 없으므로
     * 여기서 예외를 던져도 실질적 도움이 안 된다(수동 정리 필요).
     */
    public static void deleteAfterCommit(FileStorage storage, String storageKey, String namespace, String logContext) {
        deleteAfterCommit(() -> storage.delete(storageKey, namespace), logContext, storageKey);
    }

    /** 네임스페이스 없는 루트 영역 파일용 {@link #deleteAfterCommit(FileStorage, String, String, String)}(게시글 첨부). */
    public static void deleteAfterCommit(FileStorage storage, String storageKey, String logContext) {
        deleteAfterCommit(() -> storage.delete(storageKey), logContext, storageKey);
    }

    private static void deleteAfterCommit(Runnable delete, String logContext, String storageKey) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    delete.run();
                } catch (RuntimeException e) {
                    log.error("파일 삭제 실패 — 수동 정리 필요. {}, storageKey={}", logContext, storageKey, e);
                }
            }
        });
    }
}
