package com.cms.admin.contentimage.domain;

import com.cms.common.exception.ConflictException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 본문 이미지 전체 바이트·개수 카운터(행 1개, id=1, V25 시드). 업로드가 이 행을 비관적 락으로 잡고
 * {@link #reserve}로 상한을 검사·증가시켜 동시 업로드에서도 상한을 정확히 지킨다(PLAN-html-editor.md 쟁점 9).
 * 보장 범위는 <b>DB에 등록된 이미지</b>뿐이다 — 커밋 전 강제 종료 등으로 남은 "행 없는 파일"은 집계되지 않는다(R6-1).
 */
@Entity
@Table(name = "content_image_usage")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContentImageUsage {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Column(name = "total_bytes", nullable = false)
    private Long totalBytes;

    @Column(name = "total_count", nullable = false)
    private Long totalCount;

    /**
     * 상한 안이면 카운터를 늘리고, 넘으면 409. 호출자는 이 행을 비관적 락으로 잡은 상태여야 한다.
     */
    public void reserve(long bytes, long maxTotalBytes, long maxCount) {
        if (totalCount + 1 > maxCount) {
            throw new ConflictException("본문 이미지 개수 상한에 도달했습니다. 관리자에게 문의해주세요.");
        }
        if (totalBytes + bytes > maxTotalBytes) {
            throw new ConflictException("본문 이미지 저장 용량 상한에 도달했습니다. 관리자에게 문의해주세요.");
        }
        totalBytes += bytes;
        totalCount += 1;
    }
}
