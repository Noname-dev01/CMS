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
 * FAQ 카테고리(V41, PLAN-faq.md 쟁점 1). {@link #ord}는 전체 안에서 항상 0..n-1이다. 노출을 끄면 그 안의 FAQ가 공개 화면에서 모두 숨는다.
 * 하드 삭제이고 FAQ가 하나도 없을 때만 지울 수 있다(서비스가 판정, FK는 RESTRICT).
 */
@Entity
@Table(name = "faq_category")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FaqCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false)
    private Integer ord;

    @Column(name = "use_yn", nullable = false)
    private Boolean useYn;

    private LocalDateTime createDate;

    private LocalDateTime updateDate;

    /** 이름·노출 여부 전체 교체(PUT). */
    public void replace(String name, boolean useYn, LocalDateTime now) {
        this.name = name;
        this.useYn = useYn;
        this.updateDate = now;
    }

    /** 표시 순서만 바꾼다(값이 같으면 호출하지 않는다 — update_date 보존). */
    public void changeOrder(int ord, LocalDateTime now) {
        this.ord = ord;
        this.updateDate = now;
    }
}
