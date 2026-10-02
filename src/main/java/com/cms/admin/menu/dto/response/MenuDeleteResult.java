package com.cms.admin.menu.dto.response;

import lombok.Getter;

/**
 * 메뉴 영구삭제의 서비스 내부 결과. 컨트롤러는 이 객체를 응답으로 노출하지 않고(204) 버린다.
 * 감사 Aspect가 반환 객체의 getter에서만 targetId·targetLabel을 추출하므로(void면 targetId가 null이 된다)
 * 삭제 전에 만든 스냅샷을 돌려준다 — 행이 사라진 뒤에는 이름·URL을 알 방법이 없다.
 */
@Getter
public class MenuDeleteResult {

    private final Long menuNo;
    private final String menuName;
    private final String menuUrl;

    public MenuDeleteResult(Long menuNo, String menuName, String menuUrl) {
        this.menuNo = menuNo;
        this.menuName = menuName;
        this.menuUrl = menuUrl;
    }

    /** 감사 로그 targetLabel — "이름 (URL)", URL이 없으면 이름만. */
    public String getAuditLabel() {
        if (menuUrl == null || menuUrl.isBlank()) {
            return menuName;
        }
        return menuName + " (" + menuUrl + ")";
    }
}
