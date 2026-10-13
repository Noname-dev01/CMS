package com.cms.admin.site.service;

import com.cms.admin.menu.MenuRepository;
import com.cms.admin.site.Site;
import com.cms.admin.site.SiteRepository;
import com.cms.admin.site.SiteType;
import com.cms.admin.site.dto.request.SiteUpdateRequest;
import com.cms.admin.site.dto.response.SiteResponse;
import com.cms.common.exception.ConflictException;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 기본 공개 사이트 변경(PLAN-site-menu.md PR ② P2-5) — 단위 시험. 동시성(실제 락)은 {@code SiteDefaultConcurrencyIntegrationTest}가 맡는다. */
@ExtendWith(MockitoExtension.class)
class SiteServiceDefaultTest {

    @Mock SiteRepository siteRepository;
    @Mock MenuRepository menuRepository;

    @Spy
    Clock clock = Clock.fixed(Instant.parse("2026-09-29T15:00:00Z"), ZoneId.of("Asia/Seoul"));

    @InjectMocks SiteService siteService;

    private static Site site(long id, String key, SiteType type, boolean useYn, boolean defaultYn) {
        return Site.builder().id(id).siteKey(key).siteName("이름-" + key).siteType(type)
                .allowExternalLink(type == SiteType.PUBLIC).useYn(useYn).defaultYn(defaultYn).build();
    }

    private static SiteUpdateRequest makeDefault() {
        return SiteUpdateRequest.builder().defaultSite(true).build();
    }

    @Test
    @DisplayName("기본 지정: 옛 기본이 해제되고 대상이 기본이 된다 — 처음부터 모든 site 행을 잠근다(대상 단독 잠금 없음)")
    void makeDefault_movesTheFlag_lockingAllRows() {
        Site admin = site(1, "admin", SiteType.ADMIN, true, false);
        Site main = site(2, "main", SiteType.PUBLIC, true, true);
        Site news = site(3, "news", SiteType.PUBLIC, true, false);
        given(siteRepository.findAllForUpdate()).willReturn(List.of(admin, main, news));

        SiteResponse response = siteService.updateSite(3L, makeDefault());

        assertThat(news.isDefaultYn()).isTrue();
        assertThat(main.isDefaultYn()).isFalse();
        assertThat(admin.isDefaultYn()).isFalse();
        assertThat(response.getDefaultSite()).isTrue();
        assertThat(news.getUpdateDate()).isEqualTo(LocalDateTime.of(2026, 9, 30, 0, 0));
        verify(siteRepository, never()).findByIdForUpdate(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("이미 기본인 사이트에 다시 지정해도 멱등 성공 — 다른 행은 건드리지 않는다")
    void makeDefault_idempotent() {
        Site main = site(2, "main", SiteType.PUBLIC, true, true);
        Site news = site(3, "news", SiteType.PUBLIC, true, false);
        given(siteRepository.findAllForUpdate()).willReturn(List.of(main, news));

        siteService.updateSite(2L, makeDefault());

        assertThat(main.isDefaultYn()).isTrue();
        assertThat(news.isDefaultYn()).isFalse();
    }

    @Test
    @DisplayName("defaultSite=false는 400 — 기본 사이트는 다른 사이트를 지정해야만 바뀐다 (잠금도 잡지 않는다)")
    void makeDefault_falseRejected() {
        assertThatThrownBy(() -> siteService.updateSite(2L, SiteUpdateRequest.builder().defaultSite(false).build()))
                .isInstanceOf(InvalidRequestException.class);
        verify(siteRepository, never()).findAllForUpdate();
        verify(siteRepository, never()).findByIdForUpdate(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("관리자 타입·비활성 사이트는 기본으로 지정할 수 없다 (400)")
    void makeDefault_requiresActivePublic() {
        Site admin = site(1, "admin", SiteType.ADMIN, true, false);
        Site main = site(2, "main", SiteType.PUBLIC, true, true);
        Site off = site(3, "off", SiteType.PUBLIC, false, false);
        given(siteRepository.findAllForUpdate()).willReturn(List.of(admin, main, off));

        assertThatThrownBy(() -> siteService.updateSite(1L, makeDefault())).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> siteService.updateSite(3L, makeDefault())).isInstanceOf(InvalidRequestException.class);
        assertThat(main.isDefaultYn()).as("거부되면 기존 기본이 그대로").isTrue();
        assertThat(off.isDefaultYn()).isFalse();
    }

    @Test
    @DisplayName("복합 요청 {defaultSite:true, useYn:false}는 최종 상태 기준으로 거부된다 (변경 전 플래그만 보지 않는다)")
    void makeDefault_compositeRequestJudgedByFinalState() {
        Site main = site(2, "main", SiteType.PUBLIC, true, true);
        Site news = site(3, "news", SiteType.PUBLIC, true, false);
        given(siteRepository.findAllForUpdate()).willReturn(List.of(main, news));

        SiteUpdateRequest request = SiteUpdateRequest.builder().defaultSite(true).useYn(false).build();

        assertThatThrownBy(() -> siteService.updateSite(3L, request)).isInstanceOf(InvalidRequestException.class);
        assertThat(news.isDefaultYn()).isFalse();
        assertThat(news.getUseYn()).as("거부된 요청은 다른 필드도 바꾸지 않는다").isTrue();
        assertThat(main.isDefaultYn()).isTrue();
    }

    @Test
    @DisplayName("없는 사이트는 404 — 기본 지정 경로에서도 존재 판정은 잠근 행 목록에서 한다")
    void makeDefault_notFound() {
        given(siteRepository.findAllForUpdate()).willReturn(List.of(site(2, "main", SiteType.PUBLIC, true, true)));

        assertThatThrownBy(() -> siteService.updateSite(99L, makeDefault())).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("현재 기본 사이트는 비활성화·삭제할 수 없다 (시드가 아니어도) — 시드 main도 기본이 아니게 된 뒤에는 시드 보호가 계속 막는다")
    void defaultSite_cannotBeDisabledOrDeleted() {
        Site news = site(3, "news", SiteType.PUBLIC, true, true);
        given(siteRepository.findByIdForUpdate(3L)).willReturn(java.util.Optional.of(news));

        assertThatThrownBy(() -> siteService.updateSite(3L, SiteUpdateRequest.builder().useYn(false).build()))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> siteService.deleteSite(3L)).isInstanceOf(ConflictException.class);
        verify(siteRepository, never()).delete(org.mockito.ArgumentMatchers.any(Site.class));

        Site formerMain = site(2, "main", SiteType.PUBLIC, true, false);
        given(siteRepository.findByIdForUpdate(2L)).willReturn(java.util.Optional.of(formerMain));
        assertThatThrownBy(() -> siteService.updateSite(2L, SiteUpdateRequest.builder().useYn(false).build()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("기본 지정 없는 일반 수정은 단일 행 잠금만 쓴다 — 기본 표시를 건드리지 않는다")
    void plainUpdate_usesSingleRowLock_andKeepsFlag() {
        Site news = site(3, "news", SiteType.PUBLIC, true, false);
        given(siteRepository.findByIdForUpdate(3L)).willReturn(java.util.Optional.of(news));

        siteService.updateSite(3L, SiteUpdateRequest.builder().siteName("새 이름").build());

        assertThat(news.isDefaultYn()).isFalse();
        verify(siteRepository, never()).findAllForUpdate();
    }
}
