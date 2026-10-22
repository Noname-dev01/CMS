package com.cms.admin.inquiry.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 공개 문의 폼으로 들어온 문의(PLAN-inquiry.md). 이름·이메일·제목·본문은 모두 <b>방문자가 쓴 평문</b>이다 — 서버는 정책 검증을 거친 원문 그대로 저장하고,
 * 표시 계층(관리자 화면)이 텍스트로만 출력한다. 방문자 IP·User-Agent는 저장하지 않는다. 하드 삭제라 소프트 삭제 컬럼이 없다.
 */
@Entity
@Table(name = "inquiry")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Inquiry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false, length = 100)
    private String email;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InquiryStatus status;

    /** 개인정보 수집·이용 동의 시각. */
    @Column(nullable = false)
    private LocalDateTime consentAt;

    @Column(nullable = false)
    private LocalDateTime createDate;

    @Column(nullable = false)
    private LocalDateTime updateDate;

    /** 상태를 바꾼다. 같은 값이면 아무것도 하지 않고 {@code false}를 돌려준다(멱등 — {@code updateDate}를 올리지 않는다). */
    public boolean changeStatus(InquiryStatus newStatus, LocalDateTime now) {
        if (this.status == newStatus) {
            return false;
        }
        this.status = newStatus;
        this.updateDate = now;
        return true;
    }
}
