package com.cms.common.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link FileStorage}에 쓴 파일을 트랜잭션 결과에 맞춰 정리하는 공유 유틸.
 * {@code NoticeAttachmentService}가 이미 이 패턴(등록 시점 registerSynchronization,
 * 롤백 시 정리, 커밋 후 삭제)을 구현해뒀지만, 소비자가 3곳(공지 첨부·회원 서비스·프로필
 * 이미지 마이그레이션 러너)으로 늘어나는 시점부터는 미묘한 예외 처리 정책의 3중 복제가
 * 되므로 신규 2곳(회원 서비스·마이그레이션 러너)에 한해 이 유틸로 통일한다.
 * {@code NoticeAttachmentService}는 이미 테스트로 검증된 코드라 이번 PR에서 건드리지
 * 않는다(adversarial-review/plan/PLAN-profile-image-storage.md 쟁점 7).
 */
@Slf4j
public final class FileStorageTransactionSupport {

    private FileStorageTransactionSupport() {
    }

    /**
     * store() 성공 직후 등록한다 — 트랜잭션이 커밋되지 못하면(롤백·미확정 등) 방금 쓴 파일을
     * 정리한다. 등록 자체가 실패하면(활성 트랜잭션 동기화 없음 등) 즉시 파일을 정리한 뒤
     * 원 예외를 다시 던진다 — 이때 정리(delete) 자체가 또 실패하면 정리 실패 예외가 원
     * 예외를 가리지 않도록 {@link Throwable#addSuppressed}로 붙인 뒤 원 예외를 던진다.
     */
    public static void deleteOnRollback(FileStorage storage, String storageKey, String namespace, String logContext) {
        try {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != TransactionSynchronization.STATUS_COMMITTED) {
                        storage.delete(storageKey, namespace);
                    }
                }
            });
        } catch (RuntimeException registrationFailure) {
            try {
                storage.delete(storageKey, namespace);
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
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    storage.delete(storageKey, namespace);
                } catch (RuntimeException e) {
                    log.error("파일 삭제 실패 — 수동 정리 필요. {}, storageKey={}", logContext, storageKey, e);
                }
            }
        });
    }
}
