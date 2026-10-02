package com.cms.admin.menu;

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

    /**
     * 사이드바 노출 범위. ddl-auto가 기존 행에 null을 남길 수 있으므로
     * getter에서 null을 ALL(공용)로 정규화한다.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private MenuAccessRole accessRole;

    private Integer ord;

    private Long upMenuNo;

    private LocalDateTime createDate;

    private LocalDateTime updateDate;

    /** null(레거시 행)은 공용(ALL)으로 간주한다. Lombok @Getter보다 이 메서드가 우선한다. */
    public MenuAccessRole getAccessRole() {
        return accessRole != null ? accessRole : MenuAccessRole.ALL;
    }

    /**
     * 수정 가능 필드 일괄 반영. upMenuNo(부모)는 변경 대상이 아니다.
     *
     * @param now 앱 Clock 기준 현재 시각 — updateDate에 기록
     */
    public void update(String menuName, String menuUrl, String menuIcon, String menuDesc,
                        Boolean useYn, MenuAccessRole accessRole, Integer ord, LocalDateTime now) {
        this.menuName = menuName;
        this.menuUrl = menuUrl;
        this.menuIcon = menuIcon;
        this.menuDesc = menuDesc;
        this.useYn = useYn;
        this.accessRole = accessRole;
        this.ord = ord;
        this.updateDate = now;
    }

    /**
     * 정렬 순서만 변경한다(형제 순서 재조정용). 다른 필드는 건드리지 않는다.
     *
     * @param now 앱 Clock 기준 현재 시각 — updateDate에 기록
     */
    public void changeOrd(Integer ord, LocalDateTime now) {
        this.ord = ord;
        this.updateDate = now;
    }

    /**
     * 부모를 바꿔 이동한다(전용 이동 API용). 부모·정렬 순서·수정 시각만 바꾸고 다른 필드는 건드리지 않는다.
     *
     * @param upMenuNo 새 부모 메뉴 번호. 최상위로 승격하면 null
     * @param ord      새 부모 아래에서의 정렬 순서(호출자가 형제 맨 끝 값을 계산해 전달)
     * @param now      앱 Clock 기준 현재 시각 — updateDate에 기록
     */
    public void changeParent(Long upMenuNo, Integer ord, LocalDateTime now) {
        this.upMenuNo = upMenuNo;
        this.ord = ord;
        this.updateDate = now;
    }
}
