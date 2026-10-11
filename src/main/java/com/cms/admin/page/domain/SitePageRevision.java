package com.cms.admin.page.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 정적 페이지의 개정 한 건(V37). 본문이 바뀐 저장마다 하나씩 쌓이며 수정·삭제하지 않는다(페이지 삭제 때만 함께 지운다).
 * 연관관계 매핑 없이 ID 값만 가진다(프로젝트 엔티티 규약). {@code content}는 sanitize를 마친 HTML이다.
 */
@Entity
@Table(name = "site_page_revision")
@IdClass(SitePageRevisionId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SitePageRevision {

    @Id
    @Column(name = "page_id")
    private Long pageId;

    @Id
    @Column(name = "revision_no")
    private Integer revisionNo;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
    private String content;

    @Column(nullable = false)
    private LocalDateTime createDate;

    public SitePageRevision(Long pageId, Integer revisionNo, String title, String content, LocalDateTime createDate) {
        this.pageId = pageId;
        this.revisionNo = revisionNo;
        this.title = title;
        this.content = content;
        this.createDate = createDate;
    }
}
