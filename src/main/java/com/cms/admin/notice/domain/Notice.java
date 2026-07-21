package com.cms.admin.notice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Lob
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "use_yn", nullable = false)
    private Boolean useYn;

    @Column(nullable = false)
    private Boolean deleted;

    @Column(name = "author_id", nullable = false, length = 100)
    private String authorId;

    private LocalDateTime createDate;

    private LocalDateTime updateDate;

    /**
     * 부분 수정. 각 파라미터가 null이 아닐 때만 반영한다(null=기존값 유지 시맨틱).
     * 공백 거부·전체 null 거부는 Service 계층(요청 DTO 검증 이후)의 책임이다.
     */
    public void update(String title, String content, Boolean useYn) {
        if (title != null) {
            this.title = title;
        }
        if (content != null) {
            this.content = content;
        }
        if (useYn != null) {
            this.useYn = useYn;
        }
        this.updateDate = LocalDateTime.now();
    }

    /**
     * 소프트 삭제. deleted=true 처리 후 수정 시각을 갱신한다.
     */
    public void softDelete() {
        this.deleted = true;
        this.updateDate = LocalDateTime.now();
    }
}
