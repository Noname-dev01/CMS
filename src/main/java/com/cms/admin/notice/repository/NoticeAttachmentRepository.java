package com.cms.admin.notice.repository;

import com.cms.admin.notice.domain.NoticeAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NoticeAttachmentRepository extends JpaRepository<NoticeAttachment, Long> {

    /** 첨부 목록 조회용 — attachmentId를 받지 않는 단순 목록이라 IDOR 검증 대상이 아니다. */
    List<NoticeAttachment> findByNoticeIdOrderByIdAsc(Long noticeId);

    /** notice당 첨부 개수 상한(5개) 검사, notice 삭제 시 첨부 존재 여부 검사(쟁점 14)에 사용. */
    long countByNoticeId(Long noticeId);

    /**
     * 다운로드·삭제 전용 — attachmentId와 noticeId 복합조건으로 조회해, 다른 notice의
     * attachmentId를 잘못된 부모 URI로 접근하는 것을 차단한다(IDOR 방지).
     */
    Optional<NoticeAttachment> findByIdAndNoticeId(Long id, Long noticeId);
}
