package com.cms.admin.page.domain;

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
 * 정적 페이지 메타(V37, PLAN-static-page.md 쟁점 2). 본문·제목은 {@link SitePageRevision}에만 있고 {@link #currentRevisionNo}가 현재
 * 개정을 가리킨다. 슬러그는 생성 후 바뀌지 않는다. {@link #version}은 낡은 화면 검출용 변경 버전으로 본문·노출·푸터 중 무엇이든
 * 바뀌면 오른다 — 개정 번호와는 다른 값이다(메타만 바뀌면 개정 번호는 그대로이고 버전만 오른다).
 */
@Entity
@Table(name = "site_page")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SitePage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = SiteSlug.MAX_LENGTH, unique = true)
    private String slug;

    @Column(name = "current_revision_no", nullable = false)
    private Integer currentRevisionNo;

    @Column(nullable = false)
    private Integer version;

    @Column(name = "use_yn", nullable = false)
    private Boolean useYn;

    @Column(name = "footer_yn", nullable = false)
    private Boolean footerYn;

    private LocalDateTime createDate;

    private LocalDateTime updateDate;

    /** 노출·푸터를 바꾼다. 실제로 값이 바뀌면 true. 버전·수정 시각은 {@link #markChanged}가 한 번만 올린다. */
    public boolean changeFlags(boolean useYn, boolean footerYn) {
        boolean changed = !Boolean.valueOf(useYn).equals(this.useYn) || !Boolean.valueOf(footerYn).equals(this.footerYn);
        this.useYn = useYn;
        this.footerYn = footerYn;
        return changed;
    }

    /** 새 개정 번호를 매긴다. {@code int} 한계에서는 쓰기 전에 {@link ArithmeticException}으로 실패한다(R5-1 — 음수 개정이 저장되지 않게). */
    public int advanceRevision() {
        this.currentRevisionNo = Math.incrementExact(this.currentRevisionNo);
        return this.currentRevisionNo;
    }

    /** 무언가 바뀐 저장 한 번에 버전을 한 번만 올린다. */
    public void markChanged(LocalDateTime now) {
        this.version = Math.incrementExact(this.version);
        this.updateDate = now;
    }
}
