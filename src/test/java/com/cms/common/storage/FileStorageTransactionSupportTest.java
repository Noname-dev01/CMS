package com.cms.common.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 파일 정리 헬퍼의 트랜잭션 결과별 동작(PLAN-board.md 쟁점 7·R5-1, v8·v10). Spring은 커밋 단계 예외에서도 {@code STATUS_ROLLED_BACK}을,
 * 롤백 자체가 실패하면 {@code STATUS_UNKNOWN}을 넘기므로 상태 코드와 {@code beforeCommit} 호출 여부의 조합으로 판정한다.
 * 실제 트랜잭션 없이 동기화 콜백을 직접 구동한다 — 실제 트랜잭션 경로는 통합 시험이 맡는다.
 */
class FileStorageTransactionSupportTest {

    private final FileStorage storage = mock(FileStorage.class);

    @BeforeEach
    void initSynchronization() {
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void clearSynchronization() {
        TransactionSynchronizationManager.clear();
    }

    private TransactionSynchronization registeredRootCleanup() {
        FileStorageTransactionSupport.deleteOnRollback(storage, "root-key");
        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, syncs.size());
        return syncs.get(0);
    }

    @Test
    @DisplayName("COMMITTED — 파일을 지우지 않는다")
    void committed_keepsFile() {
        TransactionSynchronization sync = registeredRootCleanup();

        sync.beforeCommit(false);
        sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);

        verify(storage, never()).delete("root-key");
    }

    @Test
    @DisplayName("ROLLED_BACK × beforeCommit 미호출(커밋 시도 전 롤백) — 확정 롤백이라 지운다")
    void rolledBack_commitNotAttempted_deletesFile() {
        TransactionSynchronization sync = registeredRootCleanup();

        sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(storage).delete("root-key");
    }

    @Test
    @DisplayName("ROLLED_BACK × beforeCommit 호출(커밋 단계 예외 — 응답 유실 포함) — 결과 불명이라 보존한다")
    void rolledBack_commitAttempted_keepsFile() {
        TransactionSynchronization sync = registeredRootCleanup();

        sync.beforeCommit(false);
        sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(storage, never()).delete("root-key");
    }

    @Test
    @DisplayName("UNKNOWN × beforeCommit 미호출(롤백 자체 실패) — 결과 불명이라 보존한다")
    void unknown_commitNotAttempted_keepsFile() {
        TransactionSynchronization sync = registeredRootCleanup();

        sync.afterCompletion(TransactionSynchronization.STATUS_UNKNOWN);

        verify(storage, never()).delete("root-key");
    }

    @Test
    @DisplayName("UNKNOWN × beforeCommit 호출 — 보존한다")
    void unknown_commitAttempted_keepsFile() {
        TransactionSynchronization sync = registeredRootCleanup();

        sync.beforeCommit(false);
        sync.afterCompletion(TransactionSynchronization.STATUS_UNKNOWN);

        verify(storage, never()).delete("root-key");
    }

    @Test
    @DisplayName("네임스페이스 오버로드도 같은 판정으로 해당 네임스페이스의 파일을 지운다")
    void namespaceOverload_deletesInNamespaceOnConfirmedRollback() {
        FileStorageTransactionSupport.deleteOnRollback(storage, "profile-key", "profile", "memberId=1");
        TransactionSynchronization sync = TransactionSynchronizationManager.getSynchronizations().get(0);

        sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(storage).delete("profile-key", "profile");
        verify(storage, never()).delete("profile-key");
    }

    @Test
    @DisplayName("동기화 등록 자체가 실패하면 즉시 파일을 정리하고 원 예외를 던지며, 정리 실패는 suppressed로 붙는다")
    void registrationFailure_cleansUpImmediately_andSuppressesCleanupFailure() {
        TransactionSynchronizationManager.clear();     // 활성 동기화 없음 → registerSynchronization이 IllegalStateException
        IllegalStateException cleanupFailure = new IllegalStateException("디스크 오류");
        doThrow(cleanupFailure).when(storage).delete("root-key");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> FileStorageTransactionSupport.deleteOnRollback(storage, "root-key"));

        verify(storage).delete("root-key");
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(cleanupFailure, thrown.getSuppressed()[0]);
    }

    @Test
    @DisplayName("루트 afterCommit 삭제 — 커밋 후에만 지우고, 삭제 실패는 전파하지 않는다")
    void deleteAfterCommit_root_deletesOnlyAfterCommit_andSwallowsFailure() {
        doThrow(new IllegalStateException("디스크 오류")).when(storage).delete("root-key");
        FileStorageTransactionSupport.deleteAfterCommit(storage, "root-key", "attachmentId=1");
        TransactionSynchronization sync = TransactionSynchronizationManager.getSynchronizations().get(0);

        verify(storage, never()).delete("root-key");
        sync.afterCommit();      // 예외를 던지지 않아야 한다

        verify(storage).delete("root-key");
    }
}
