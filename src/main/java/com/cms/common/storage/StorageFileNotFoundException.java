package com.cms.common.storage;

/**
 * 스토리지에 저장된 파일 자체가 없을 때 던진다({@link FileStorage#load}가 던지는
 * {@link IllegalStateException} 중 "존재하지 않음"만 구분한다) — 호출부가 다른 I/O 실패(500)와
 * 구분해 404 등으로 매핑할 수 있게 한다. {@link IllegalStateException}을 상속해 기존
 * {@code assertThrows(IllegalStateException.class, ...)} 검증과의 호환을 유지한다.
 */
public class StorageFileNotFoundException extends IllegalStateException {

    public StorageFileNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 원인 예외 없이 던지는 경우(예: 예약된 네임스페이스 접근 거부처럼 I/O 실패가 아닌
     * 논리적 거부) 전용 — {@link com.cms.admin.member.ProfileImageMigrationRunner} 등.
     */
    public StorageFileNotFoundException(String message) {
        super(message);
    }
}
