package com.cms.admin.board.repository;

import com.cms.admin.board.domain.PostAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PostAttachmentRepository extends JpaRepository<PostAttachment, Long> {

    /** 첨부 목록 조회용 — attachmentId를 받지 않는 단순 목록이라 IDOR 검증 대상이 아니다. */
    List<PostAttachment> findByPostIdOrderByIdAsc(Long postId);

    /** 게시글당 첨부 개수 상한(5개) 검사, 게시글 삭제 시 첨부 존재 여부 검사에 사용. */
    long countByPostId(Long postId);

    /**
     * 다운로드·삭제 전용 — attachmentId와 postId 복합조건으로 조회해, 다른 게시글의 attachmentId를
     * 잘못된 부모 URI로 접근하는 것을 차단한다(IDOR 방지).
     */
    Optional<PostAttachment> findByIdAndPostId(Long id, Long postId);
}
