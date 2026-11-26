package com.cms.admin.contentimage.service;

import com.cms.common.storage.FileStorage;
import com.cms.common.storage.StoredFileStream;

/**
 * {@code content_image.namespace}의 파일 위치 규칙을 한 곳에 둔다: 빈 문자열(또는 null)이면 루트 영역(본문 이미지·라이브러리 업로드),
 * 그 밖의 값({@code banner}·{@code popup})이면 그 네임스페이스다. 빈 문자열을 쓰는 이유는 UNIQUE가 NULL 중복을 허용하기 때문이다
 * (PLAN-media-library.md R4-1).
 *
 * <p>이 규칙을 {@link FileStorage} 안에 넣지 않는다 — 저장소는 빈 네임스페이스를 {@code IllegalArgumentException}으로 거부해
 * 실수로 빠진 네임스페이스가 루트에 섞이지 않게 막는다. 빈 값을 루트로 읽는 것은 이 테이블의 저장 형식이므로 이 도메인이 맡는다.
 * 예외는 감싸지 않는다(호출자가 {@code StorageFileNotFoundException}을 404로 바꾼다). PLAN-solid-small-cleanup.md 결정 1.
 */
public final class ContentImageFiles {

    private ContentImageFiles() {
    }

    /** 루트 영역 파일인지(네임스페이스 API가 아니라 루트 API로 읽고·저장하고·지워야 하는지). */
    public static boolean isRoot(String namespace) {
        return namespace == null || namespace.isEmpty();
    }

    /** 파일 전체를 읽는다(관리자 미리보기·검증용 — 크기 상한이 작은 이미지). */
    public static byte[] load(FileStorage storage, String storageKey, String namespace) {
        return isRoot(namespace) ? storage.load(storageKey) : storage.load(storageKey, namespace);
    }

    /** 스트림으로 연다(공개 전송용). 반환된 스트림은 호출자가 닫는다. */
    public static StoredFileStream open(FileStorage storage, String storageKey, String namespace) {
        return isRoot(namespace) ? storage.open(storageKey) : storage.open(storageKey, namespace);
    }
}
