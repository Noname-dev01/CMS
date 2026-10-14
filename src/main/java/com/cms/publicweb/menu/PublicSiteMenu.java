package com.cms.publicweb.menu;

import com.cms.publicweb.menu.dto.PublicMenuItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

/**
 * 템플릿이 모델 {@code siteMenu}로 보는 요청 범위 지연 홀더(PLAN-site-menu.md PR ⑤ P5-2). 템플릿이 {@link #getItems()}를 처음 만질 때만
 * 조회하고 결과를 요청 안에서 기억하므로, 메뉴를 그리지 않는 요청(스트리밍·리다이렉트)에는 쿼리가 나가지 않는다. 메뉴는 선택 섹션이라
 * 조회 실패는 여기서 격리한다 — 렌더링 중 호출이라 {@code PublicWebExceptionAdvice}가 잡지 못하는 구간이고, 서비스는 자기 읽기
 * 트랜잭션이라 예외가 호출자 트랜잭션을 오염시키지 않는다. 요청마다 새로 만들며 스레드 간에 공유하지 않는다.
 */
@Slf4j
public class PublicSiteMenu {

    private final ObjectProvider<PublicMenuService> serviceProvider;
    private final Long siteId;
    private List<PublicMenuItem> items;

    public PublicSiteMenu(ObjectProvider<PublicMenuService> serviceProvider, Long siteId) {
        this.serviceProvider = serviceProvider;
        this.siteId = siteId;
    }

    public List<PublicMenuItem> getItems() {
        if (items == null) {
            items = load();
        }
        return items;
    }

    private List<PublicMenuItem> load() {
        try {
            PublicMenuService service = serviceProvider.getIfAvailable();
            return service == null ? List.of() : service.findMenu(siteId);
        } catch (RuntimeException e) {
            log.warn("공개 사이트 메뉴 조회 실패 — 메뉴 없이 렌더링합니다: {}", e.getClass().getName());
            return List.of();
        }
    }
}
