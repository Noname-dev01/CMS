package com.cms.support;

import com.cms.common.storage.FileStorage;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

/**
 * 미디어 라이브러리(content_image) 행·파일을 만드는 통합 시험의 정리 도우미(PLAN-media-library.md). 배너·팝업·라이브러리는 이미지를
 * {@code content_image}에 등록하고 카운터({@code content_image_usage})를 쓰므로, 시험이 만든 이미지 행·참조·파일과 카운터를 되돌려야 시험 간 상태가 쌓이지 않는다.
 */
public final class MediaTestSupport {

    private MediaTestSupport() {
    }

    /**
     * 라이브러리 계열 출처(LIBRARY·BANNER·POPUP)의 이미지 행·파일과 배너·팝업 소유자의 참조를 모두 지우고 카운터를 원본에서 재계산한다.
     * 호출 전에 소유자(배너·팝업) 행은 호출자가 지웠거나 이 메서드 뒤에 지워도 된다(참조 행만 정리한다).
     */
    public static void cleanLibrary(JdbcTemplate jdbc, FileStorage fileStorage) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT storage_key, namespace FROM content_image WHERE scope_type IN ('LIBRARY', 'BANNER', 'POPUP')");
        jdbc.update("DELETE FROM content_image_ref WHERE owner_type IN ('BANNER', 'POPUP')"
                + " OR image_id IN (SELECT id FROM content_image WHERE scope_type IN ('LIBRARY', 'BANNER', 'POPUP'))");
        jdbc.update("DELETE FROM content_image WHERE scope_type IN ('LIBRARY', 'BANNER', 'POPUP')");
        for (Map<String, Object> row : rows) {
            String key = (String) row.get("storage_key");
            String namespace = (String) row.get("namespace");
            if (namespace == null || namespace.isEmpty()) {
                fileStorage.delete(key);
            } else {
                fileStorage.delete(key, namespace);
            }
        }
        recomputeCounter(jdbc);
    }

    /**
     * 소유자(배너·팝업) 행에 이미지 파일을 연결하는 시험용 픽스처 — {@code content_image}(소유 출처·네임스페이스)와 {@code content_image_ref}를 만든다.
     * 호출 전에 파일은 호출자가 해당 네임스페이스({@code ownerType}의 소문자)에 저장해 두어야 한다. 카운터는 건드리지 않는다(필요하면 {@link #recomputeCounter}).
     *
     * @return 만든 이미지 ID
     */
    public static long attachImage(JdbcTemplate jdbc, String ownerType, long ownerId, String storageKey, long fileSize) {
        String namespace = ownerType.toLowerCase(java.util.Locale.ROOT);
        jdbc.update("INSERT INTO content_image (storage_key, content_type, file_size, uploader_id, scope_type, namespace) VALUES (?, 'image/png', ?, 'fixture', ?, ?)",
                storageKey, fileSize, ownerType, namespace);
        long imageId = jdbc.queryForObject("SELECT id FROM content_image WHERE storage_key = ? AND namespace = ?", Long.class, storageKey, namespace);
        jdbc.update("INSERT INTO content_image_ref (owner_type, owner_id, image_id) VALUES (?, ?, ?)", ownerType, ownerId, imageId);
        return imageId;
    }

    public static void recomputeCounter(JdbcTemplate jdbc) {
        jdbc.update("UPDATE content_image_usage SET total_bytes = (SELECT COALESCE(SUM(file_size), 0) FROM content_image),"
                + " total_count = (SELECT COUNT(*) FROM content_image) WHERE id = 1");
    }
}
