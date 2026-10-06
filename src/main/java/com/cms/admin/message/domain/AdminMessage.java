package com.cms.admin.message.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 관리자 1:1 쪽지. 읽음·삭제는 엔티티 수정이 아니라 리포지토리의 조건부 UPDATE로만 한다 — 경합해도 최초 읽음 시각이 보존되고,
 * 양쪽 삭제 판정이 낡은 엔티티 값에 의존하지 않게 하기 위해서다(PLAN-admin-message.md §5-D).
 * 제목·본문은 사용자 입력이라 원문 그대로 저장하며 표시 계층이 {@code textContent}로만 그린다.
 */
@Entity
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 보낸 회원 ID — 연관관계 매핑 없이 ID만 둔다(다른 엔티티와 같은 방식). */
    @Column(nullable = false)
    private Long senderId;

    @Column(nullable = false)
    private Long recipientId;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, length = 2000)
    private String body;

    /** NULL이면 수신자가 아직 읽지 않음. */
    private LocalDateTime readAt;

    /** 보낸 사람 보관함에서 삭제한 시각. NULL이면 보이는 상태. */
    private LocalDateTime senderDeletedAt;

    /** 받은 사람 보관함에서 삭제한 시각. NULL이면 보이는 상태. */
    private LocalDateTime recipientDeletedAt;

    @Column(nullable = false)
    private LocalDateTime createDate;

    public boolean isRead() {
        return readAt != null;
    }
}
