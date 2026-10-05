package com.cms.admin.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 관리자 본인에게 보이는 알림. 읽음 처리는 엔티티 수정이 아니라 리포지토리의 조건부 UPDATE로만 한다
 * (경합 시 최초 읽음 시각을 보존하기 위해 — 계획서 v3 R-7).
 */
@Entity
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 수신 회원 ID — 연관관계 매핑 없이 ID만 둔다(다른 엔티티와 같은 방식). */
    @Column(nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private NotificationType type;

    @Column(nullable = false, length = 255)
    private String message;

    /** 같은 출처 경로만(예: {@code /admin/member/settings}). */
    @Column(length = 255)
    private String linkUrl;

    /** 같은 사건의 중복 생성 방지 키(E3: 비밀번호 주기당 1건). NULL이면 유니크 대상이 아니다. */
    @Column(length = 100)
    private String dedupeKey;

    /** NULL이면 미읽음. */
    private LocalDateTime readAt;

    @Column(nullable = false)
    private LocalDateTime createDate;

    public boolean isRead() {
        return readAt != null;
    }
}
