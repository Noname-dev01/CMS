package com.cms.publicweb.menu;

import com.cms.publicweb.menu.dto.PublicMenuItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 요청 범위 지연 홀더(PLAN-site-menu.md PR ⑤ P5-2): 만지기 전에는 조회하지 않고, 한 번만 조회하며, 실패는 빈 목록으로 격리한다. */
@SuppressWarnings("unchecked")
class PublicSiteMenuTest {

    private final ObjectProvider<PublicMenuService> provider = mock(ObjectProvider.class);
    private final PublicMenuService service = mock(PublicMenuService.class);

    @Test
    @DisplayName("템플릿이 items를 만지기 전에는 서비스를 부르지 않는다(스트리밍·리다이렉트 요청에 쿼리 없음)")
    void lazy_noCallUntilTouched() {
        when(provider.getIfAvailable()).thenReturn(service);

        new PublicSiteMenu(provider, 7L);

        verify(service, never()).findMenu(anyLong());
    }

    @Test
    @DisplayName("여러 번 읽어도 서비스는 요청당 한 번만 부른다")
    void loadsOnce() {
        List<PublicMenuItem> items = List.of(new PublicMenuItem("소개", "/main/pages/about", false, List.of()));
        when(provider.getIfAvailable()).thenReturn(service);
        when(service.findMenu(7L)).thenReturn(items);
        PublicSiteMenu menu = new PublicSiteMenu(provider, 7L);

        assertThat(menu.getItems()).isSameAs(items);
        assertThat(menu.getItems()).isSameAs(items);

        verify(service, times(1)).findMenu(7L);
    }

    @Test
    @DisplayName("조회가 예외를 던지면 빈 목록(화면은 메뉴만 빠진다)이고 재시도하지 않는다")
    void failureIsIsolated() {
        when(provider.getIfAvailable()).thenReturn(service);
        when(service.findMenu(7L)).thenThrow(new IllegalStateException("db down"));
        PublicSiteMenu menu = new PublicSiteMenu(provider, 7L);

        assertThat(menu.getItems()).isEmpty();
        assertThat(menu.getItems()).isEmpty();

        verify(service, times(1)).findMenu(7L);
    }

    @Test
    @DisplayName("서비스 빈이 없으면(슬라이스) 빈 목록")
    void missingServiceBean() {
        when(provider.getIfAvailable()).thenReturn(null);

        assertThat(new PublicSiteMenu(provider, 7L).getItems()).isEmpty();
    }
}
