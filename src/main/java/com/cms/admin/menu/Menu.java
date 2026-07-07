package com.cms.admin.menu;

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

@Entity
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Menu {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long menuNo;

    @Column(nullable = false, length = 100)
    private String menuName;

    @Column(length = 255)
    private String menuUrl;

    @Column(length = 100)
    private String menuIcon;

    @Column(length = 500)
    private String menuDesc;

    @Column(nullable = false)
    private Boolean useYn;

    private Integer ord;

    private Long upMenuNo;

    private LocalDateTime createDate;

    private LocalDateTime updateDate;

    /**
     * 수정 가능 필드 일괄 반영. upMenuNo(부모)는 변경 대상이 아니다.
     */
    public void update(String menuName, String menuUrl, String menuIcon, String menuDesc,
                        Boolean useYn, Integer ord) {
        this.menuName = menuName;
        this.menuUrl = menuUrl;
        this.menuIcon = menuIcon;
        this.menuDesc = menuDesc;
        this.useYn = useYn;
        this.ord = ord;
        this.updateDate = LocalDateTime.now();
    }

    /**
     * 비활성화. useYn=false 처리 후 수정 시각을 갱신한다.
     */
    public void deactivate() {
        this.useYn = false;
        this.updateDate = LocalDateTime.now();
    }
}
