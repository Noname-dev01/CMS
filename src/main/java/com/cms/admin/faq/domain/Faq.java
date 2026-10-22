package com.cms.admin.faq.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * FAQ 한 건(PLAN-faq.md 쟁점 1). 연관관계 매핑 없이 {@link #categoryId}만 가진다. {@link #ord}는 카테고리 안에서 항상 0..n-1이다.
 * 답변은 저장 시 정리된 HTML이고(이미지 불허) 출력 시에도 한 번 더 정리한다. 하드 삭제.
 */
@Entity
@Table(name = "faq")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Faq {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(nullable = false, length = 300)
    private String question;

    @Column(name = "answer_html", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String answerHtml;

    @Column(nullable = false)
    private Integer ord;

    @Column(name = "use_yn", nullable = false)
    private Boolean useYn;

    private LocalDateTime createDate;

    private LocalDateTime updateDate;

    /** 질문·답변·노출 여부 전체 교체(PUT). 카테고리를 바꾸는 이동은 {@link #moveTo}가 따로 한다(서비스가 원래 목록 재번호·대상 끝 삽입을 함께 처리). */
    public void replace(String question, String answerHtml, boolean useYn, LocalDateTime now) {
        this.question = question;
        this.answerHtml = answerHtml;
        this.useYn = useYn;
        this.updateDate = now;
    }

    /** 다른 카테고리의 지정한 위치({@code ord}, 보통 맨 끝)로 옮긴다. */
    public void moveTo(Long categoryId, int ord, LocalDateTime now) {
        this.categoryId = categoryId;
        this.ord = ord;
        this.updateDate = now;
    }

    /** 표시 순서만 바꾼다(값이 같으면 호출하지 않는다 — update_date 보존). */
    public void changeOrder(int ord, LocalDateTime now) {
        this.ord = ord;
        this.updateDate = now;
    }
}
