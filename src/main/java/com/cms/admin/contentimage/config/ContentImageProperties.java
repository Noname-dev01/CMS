package com.cms.admin.contentimage.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code cms.content-image.*} — 본문 이미지 전체 저장 상한(PLAN-html-editor.md 쟁점 9). DB에 등록된 이미지의
 * 바이트·개수 합계에 대한 상한이며 업로드가 카운터 행을 잠가 정확히 집행한다. 0 이하 값은 기동 시 실패(fail-fast).
 * 기본값이 있어 설정을 생략해도 기동한다.
 */
@ConfigurationProperties(prefix = "cms.content-image")
@Validated
public class ContentImageProperties {

    /** 전체 바이트 상한(기본 1GB). */
    @Positive
    private long maxTotalBytes = 1024L * 1024 * 1024;

    /** 전체 개수 상한(기본 10,000개). */
    @Positive
    private long maxCount = 10_000;

    public long getMaxTotalBytes() {
        return maxTotalBytes;
    }

    public void setMaxTotalBytes(long maxTotalBytes) {
        this.maxTotalBytes = maxTotalBytes;
    }

    public long getMaxCount() {
        return maxCount;
    }

    public void setMaxCount(long maxCount) {
        this.maxCount = maxCount;
    }
}
