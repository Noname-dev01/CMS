package com.cms.common.storage;

/**
 * 실파일 저장 추상화. 첫 구현체는 로컬 디스크({@link LocalDiskFileStorage})이며,
 * 향후 원격 스토리지(S3 등)로 교체하더라도 이 인터페이스의 소비자(NoticeAttachmentService 등)는
 * 변경할 필요가 없다.
 *
 * <p>파일당 상한이 10MB로 작고 프로젝트에 스트리밍 API 관용구가 없어 byte[] 기반으로 설계했다
 * (adversarial-review/plan/PLAN-notice-attachment.md 쟁점 1 참조).
 */
public interface FileStorage {

    /**
     * 파일 내용을 저장하고 이후 조회에 사용할 storageKey를 반환한다.
     * storageKey는 이 메서드가 서버에서 직접 생성하며, 원본 파일명은 키에 반영되지 않는다
     * (경로 탈출·파일명 인젝션 원천 차단, 쟁점 2 참조).
     *
     * @param content          저장할 바이트 배열
     * @param originalFilename 확장자 추출 등에 사용되는 원본 파일명(경로 구성에 직접 쓰이지 않음)
     * @return 이후 load/delete에 사용할 storageKey
     */
    String store(byte[] content, String originalFilename);

    /**
     * storageKey에 해당하는 파일 내용을 읽어 반환한다.
     *
     * @throws StorageFileNotFoundException 파일이 존재하지 않는 경우(IllegalStateException의 서브타입)
     * @throws IllegalStateException        그 외 읽기에 실패한 경우
     */
    byte[] load(String storageKey);

    /**
     * storageKey에 해당하는 파일을 삭제한다. 파일이 없으면 no-op(예외를 던지지 않음) —
     * 동시 삭제 경합이나 이미 정리된 파일에 대한 재시도가 안전하도록 하기 위함이다.
     */
    void delete(String storageKey);
}
