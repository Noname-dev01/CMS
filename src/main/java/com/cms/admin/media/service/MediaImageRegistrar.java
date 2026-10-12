package com.cms.admin.media.service;

import com.cms.admin.contentimage.config.ContentImageProperties;
import com.cms.admin.contentimage.domain.ContentImage;
import com.cms.admin.contentimage.domain.ContentImageUsage;
import com.cms.admin.contentimage.repository.ContentImageRepository;
import com.cms.admin.contentimage.repository.ContentImageUsageRepository;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.storage.FileStorage;
import com.cms.common.storage.FileStorageTransactionSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 새 이미지 행을 만드는 <b>모든 경로</b>(라이브러리 업로드·게시판 에디터·배너/팝업 직접 업로드)의 공통 등록 작업자(PLAN-media-library.md D5·R4-2).
 * 카운터 행을 {@code FOR UPDATE}로 잡고 상한을 검사·증가시킨 뒤 파일을 저장하고 행을 기록한다. 롤백 시 파일 정리({@code deleteOnRollback})와 카운터
 * 증가의 롤백이 같은 트랜잭션이다.
 *
 * <p><b>감사 애너테이션도, 자체 트랜잭션 경계도 없다</b> — 호출자(최상위 업무 진입점)의 트랜잭션에 {@code REQUIRED}로 참여한다. 내부에서
 * {@code REQUIRES_NEW}로 바꾸면 업무 원자성이 깨진다. 호출자가 가드 행({@code banner_lock})을 쓰는 경로는 그것을 먼저 잡은 뒤 이 메서드를 부른다
 * (잠금 순서: 가드 → 소유자 → 이미지 → 참조 → 카운터).
 */
@Component
@RequiredArgsConstructor
public class MediaImageRegistrar {

    private static final int MAX_ORIGINAL_NAME_LENGTH = 200;

    private final ContentImageRepository imageRepository;
    private final ContentImageUsageRepository usageRepository;
    private final ContentImageProperties properties;
    private final FileStorage fileStorage;
    private final Clock clock;

    /**
     * @param namespace 저장 네임스페이스 — 빈 문자열이면 루트(본문 이미지·라이브러리), 아니면 {@code banner}·{@code popup}
     */
    public ContentImage register(byte[] content, String contentType, String originalName, String uploaderId,
                                 String scopeType, Long scopeId, String namespace) {
        ContentImageUsage usage = usageRepository.findByIdForUpdate(ContentImageUsage.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("content_image_usage 카운터 행이 없습니다(V25 시드 누락)."));
        usage.reserve(content.length, properties.getMaxTotalBytes(), properties.getMaxCount());

        boolean root = namespace == null || namespace.isEmpty();
        String filename = (root ? "image." : namespace + ".") + extensionFor(contentType);
        String storageKey;
        if (root) {
            storageKey = fileStorage.store(content, filename);
            FileStorageTransactionSupport.deleteOnRollback(fileStorage, storageKey);
        } else {
            storageKey = fileStorage.store(content, filename, namespace);
            FileStorageTransactionSupport.deleteOnRollback(fileStorage, storageKey, namespace, namespace);
        }

        return imageRepository.save(ContentImage.builder()
                .storageKey(storageKey)
                .contentType(contentType)
                .fileSize((long) content.length)
                .uploaderId(uploaderId)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .createDate(LocalDateTime.now(clock))
                .namespace(root ? "" : namespace)
                .originalName(sanitizeName(originalName))
                .build());
    }

    public static String extensionFor(String contentType) {
        if (contentType == null) {
            throw new InvalidRequestException("이미지 파일만 업로드할 수 있습니다.");
        }
        return switch (contentType) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/gif" -> "gif";
            default -> throw new InvalidRequestException("이미지 파일만 업로드할 수 있습니다.");
        };
    }

    /**
     * 표시·검색용 파일명 정리 — 경로 요소를 떼고(마지막 세그먼트) NFC로 정규화하고 제어문자를 지우고 200자(코드포인트)로 자른다. 비면 null.
     * 이 값은 저장 경로에 쓰이지 않는다(저장 키는 서버가 생성). 화면은 항상 {@code textContent}로만 그린다.
     */
    static String sanitizeName(String raw) {
        if (raw == null) {
            return null;
        }
        String name = raw.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        name = Normalizer.normalize(name, Normalizer.Form.NFC);
        StringBuilder cleaned = new StringBuilder();
        name.codePoints().filter(cp -> !Character.isISOControl(cp)).forEach(cleaned::appendCodePoint);
        String result = cleaned.toString().strip();
        if (result.isEmpty()) {
            return null;
        }
        if (result.codePointCount(0, result.length()) > MAX_ORIGINAL_NAME_LENGTH) {
            result = result.substring(0, result.offsetByCodePoints(0, MAX_ORIGINAL_NAME_LENGTH));
        }
        return result;
    }
}
