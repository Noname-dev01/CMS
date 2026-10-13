package com.cms.admin.site;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 사이트(PLAN-site-menu.md). 메뉴가 소속되는 단위이며 관리자 사이트 1개와 공개 사이트 N개가 있다.
 * {@code siteKey}는 생성 후 바뀌지 않는다(공개 경로 접두어가 될 값) — 변경 메서드가 없다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Site {

    /** 관리자 사이트(시드 id=1). 사이드바는 이 사이트의 메뉴만 읽고, {@code menu.site_id}의 DB 기본값도 이 값이다. */
    public static final long ADMIN_SITE_ID = 1L;

    /** 기본 공개 사이트(시드 id=2). */
    public static final long DEFAULT_PUBLIC_SITE_ID = 2L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30, updatable = false)
    private String siteKey;

    @Column(nullable = false, length = 100)
    private String siteName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private SiteType siteType;

    @Column(nullable = false)
    private Boolean allowExternalLink;

    @Column(nullable = false)
    private Boolean useYn;

    private LocalDateTime createDate;

    private LocalDateTime updateDate;

    /** 시드 2행(관리자·기본 공개)은 삭제·비활성화할 수 없다. */
    public boolean isSeed() {
        return id != null && (id == ADMIN_SITE_ID || id == DEFAULT_PUBLIC_SITE_ID);
    }

    /** 이 사이트의 메뉴에 외부 http(s) 링크를 둘 수 있는가 — 관리자 사이트는 설정값과 무관하게 항상 불가. */
    public boolean permitsExternalLink() {
        return siteType == SiteType.PUBLIC && Boolean.TRUE.equals(allowExternalLink);
    }

    /** 수정 가능 필드 일괄 반영. 키·종류는 바뀌지 않는다. */
    public void update(String siteName, boolean allowExternalLink, boolean useYn, LocalDateTime now) {
        this.siteName = siteName;
        this.allowExternalLink = allowExternalLink;
        this.useYn = useYn;
        this.updateDate = now;
    }
}
