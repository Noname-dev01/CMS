package com.cms.admin.site.service;

import com.cms.admin.menu.MenuRepository;
import com.cms.admin.site.Site;
import com.cms.admin.site.SiteRepository;
import com.cms.admin.site.SiteType;
import com.cms.admin.site.dto.request.SiteCreateRequest;
import com.cms.admin.site.dto.request.SiteUpdateRequest;
import com.cms.admin.site.dto.response.SiteDeleteResult;
import com.cms.admin.site.dto.response.SiteResponse;
import com.cms.common.exception.ConflictException;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SiteServiceTest {

    static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 9, 30, 0, 0);

    @Mock
    SiteRepository siteRepository;

    @Mock
    MenuRepository menuRepository;

    @Mock
    com.cms.admin.banner.repository.BannerRepository bannerRepository;

    @Mock
    com.cms.admin.popup.repository.PopupRepository popupRepository;

    @Mock
    com.cms.admin.faq.repository.FaqCategoryRepository faqCategoryRepository;

    @Mock
    com.cms.admin.page.repository.SitePageRepository sitePageRepository;

    @Mock
    com.cms.admin.board.repository.BoardRepository boardRepository;

    @Spy
    Clock clock = Clock.fixed(Instant.parse("2026-09-29T15:00:00Z"), ZoneId.of("Asia/Seoul"));

    @InjectMocks
    SiteService siteService;

    private static Site site(long id, String key, SiteType type, boolean allowExternal, boolean useYn) {
        return Site.builder().id(id).siteKey(key).siteName("이름-" + key).siteType(type)
                .allowExternalLink(allowExternal).useYn(useYn).build();
    }

    // ── 생성 ──

    @Test
    @DisplayName("생성: 공개 사이트로만 만들어지고(종류 불변) 활성·메뉴 0·KST Clock 시각이 기록된다")
    void create_success() {
        given(siteRepository.existsBySiteKey("news")).willReturn(false);
        given(siteRepository.save(any(Site.class))).willAnswer(invocation -> {
            Site s = invocation.getArgument(0);
            return site(3L, s.getSiteKey(), s.getSiteType(), s.getAllowExternalLink(), s.getUseYn());
        });

        SiteResponse response = siteService.createSite(
                SiteCreateRequest.builder().siteKey("news").siteName("  소식  ").allowExternalLink(true).build());

        ArgumentCaptor<Site> captor = ArgumentCaptor.forClass(Site.class);
        verify(siteRepository).save(captor.capture());
        Site saved = captor.getValue();
        assertThat(saved.getSiteType()).isEqualTo(SiteType.PUBLIC);
        assertThat(saved.getSiteName()).isEqualTo("소식");
        assertThat(saved.getUseYn()).isTrue();
        assertThat(saved.getAllowExternalLink()).isTrue();
        assertThat(saved.getCreateDate()).isEqualTo(FIXED_NOW);
        assertThat(saved.getUpdateDate()).isEqualTo(FIXED_NOW);
        assertThat(response.getMenuCount()).isZero();
    }

    @Test
    @DisplayName("생성: allowExternalLink 누락은 false")
    void create_externalDefaultsFalse() {
        given(siteRepository.save(any(Site.class))).willAnswer(invocation -> invocation.getArgument(0));

        siteService.createSite(SiteCreateRequest.builder().siteKey("docs").siteName("문서").build());

        ArgumentCaptor<Site> captor = ArgumentCaptor.forClass(Site.class);
        verify(siteRepository).save(captor.capture());
        assertThat(captor.getValue().getAllowExternalLink()).isFalse();
    }

    @Test
    @DisplayName("생성: 예약어·형식 위반은 400, 중복은 409, 공백뿐인 이름은 400이고 저장하지 않는다")
    void create_rejections() {
        assertThatThrownBy(() -> siteService.createSite(SiteCreateRequest.builder().siteKey("faq").siteName("x").build()))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> siteService.createSite(SiteCreateRequest.builder().siteKey("Bad_Key").siteName("x").build()))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> siteService.createSite(SiteCreateRequest.builder().siteKey("news").siteName("   ").build()))
                .isInstanceOf(InvalidRequestException.class);
        given(siteRepository.existsBySiteKey("main")).willReturn(true);
        assertThatThrownBy(() -> siteService.createSite(SiteCreateRequest.builder().siteKey("main").siteName("중복").build()))
                .isInstanceOf(ConflictException.class);

        verify(siteRepository, never()).save(any());
    }

    // ── 수정 ──

    @Test
    @DisplayName("수정: null 필드는 유지, 이름·외부 링크·사용 여부가 바뀌고 키·종류는 그대로다")
    void update_partial() {
        Site existing = site(3L, "news", SiteType.PUBLIC, false, true);
        given(siteRepository.findByIdForUpdate(3L)).willReturn(Optional.of(existing));

        siteService.updateSite(3L, SiteUpdateRequest.builder().siteName("새 이름").build());
        assertThat(existing.getSiteName()).isEqualTo("새 이름");
        assertThat(existing.getAllowExternalLink()).isFalse();
        assertThat(existing.getUseYn()).isTrue();

        siteService.updateSite(3L, SiteUpdateRequest.builder().allowExternalLink(true).useYn(false).build());
        assertThat(existing.getAllowExternalLink()).isTrue();
        assertThat(existing.getUseYn()).isFalse();
        assertThat(existing.getSiteKey()).isEqualTo("news");
        assertThat(existing.getSiteType()).isEqualTo(SiteType.PUBLIC);
        assertThat(existing.getUpdateDate()).isEqualTo(FIXED_NOW);
    }

    @Test
    @DisplayName("수정: 시드 사이트(관리자·기본 공개)는 비활성화할 수 없다(409) — 이름 변경은 가능")
    void update_seedCannotBeDeactivated() {
        Site main = site(2L, "main", SiteType.PUBLIC, true, true);
        given(siteRepository.findByIdForUpdate(2L)).willReturn(Optional.of(main));

        assertThatThrownBy(() -> siteService.updateSite(2L, SiteUpdateRequest.builder().useYn(false).build()))
                .isInstanceOf(ConflictException.class);
        assertThat(main.getUseYn()).isTrue();

        siteService.updateSite(2L, SiteUpdateRequest.builder().siteName("메인").build());
        assertThat(main.getSiteName()).isEqualTo("메인");
    }

    @Test
    @DisplayName("수정: 관리자 사이트에 외부 링크 허용(true)은 400, false는 무변경으로 통과")
    void update_adminSiteCannotAllowExternal() {
        Site admin = site(1L, "admin", SiteType.ADMIN, false, true);
        given(siteRepository.findByIdForUpdate(1L)).willReturn(Optional.of(admin));

        assertThatThrownBy(() -> siteService.updateSite(1L, SiteUpdateRequest.builder().allowExternalLink(true).build()))
                .isInstanceOf(InvalidRequestException.class);
        siteService.updateSite(1L, SiteUpdateRequest.builder().allowExternalLink(false).build());
        assertThat(admin.permitsExternalLink()).isFalse();
    }

    @Test
    @DisplayName("수정: 외부 링크 허용 해제(강화)는 외부 URL 메뉴가 있으면 409, 없으면 통과 — 사이트 행 잠금 아래에서 센다")
    void update_tighteningChecksAbsoluteUrlMenus() {
        Site docs = site(3L, "docs", SiteType.PUBLIC, true, true);
        given(siteRepository.findByIdForUpdate(3L)).willReturn(Optional.of(docs));
        given(menuRepository.countAbsoluteUrlMenus(3L)).willReturn(2L).willReturn(0L);

        assertThatThrownBy(() -> siteService.updateSite(3L, SiteUpdateRequest.builder().allowExternalLink(false).build()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("2개");
        assertThat(docs.getAllowExternalLink()).as("거부되면 바뀌지 않는다").isTrue();

        siteService.updateSite(3L, SiteUpdateRequest.builder().allowExternalLink(false).build());
        assertThat(docs.getAllowExternalLink()).isFalse();
    }

    @Test
    @DisplayName("수정: 외부 링크를 새로 허용(완화)하거나 이미 불가인 사이트는 메뉴를 세지 않는다")
    void update_looseningDoesNotCount() {
        Site docs = site(3L, "docs", SiteType.PUBLIC, false, true);
        given(siteRepository.findByIdForUpdate(3L)).willReturn(Optional.of(docs));

        siteService.updateSite(3L, SiteUpdateRequest.builder().allowExternalLink(true).build());
        siteService.updateSite(3L, SiteUpdateRequest.builder().siteName("새 이름").build());

        verify(menuRepository, never()).countAbsoluteUrlMenus(any());
    }

    @Test
    @DisplayName("수정: 없는 사이트는 404")
    void update_notFound() {
        assertThatThrownBy(() -> siteService.updateSite(99L, SiteUpdateRequest.builder().siteName("x").build()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── 삭제 ──

    @Test
    @DisplayName("삭제: 시드 사이트는 409, 메뉴가 있는 사이트는 409 — 삭제하지 않는다")
    void delete_guards() {
        Site main = site(2L, "main", SiteType.PUBLIC, true, true);
        given(siteRepository.findByIdForUpdate(2L)).willReturn(Optional.of(main));
        Site news = site(3L, "news", SiteType.PUBLIC, true, true);
        given(siteRepository.findByIdForUpdate(3L)).willReturn(Optional.of(news));
        given(menuRepository.existsBySiteId(3L)).willReturn(true);

        assertThatThrownBy(() -> siteService.deleteSite(2L)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> siteService.deleteSite(3L)).isInstanceOf(ConflictException.class);

        verify(siteRepository, never()).delete(any());
    }

    @Test
    @DisplayName("삭제: 메뉴가 없어도 전용 배너·팝업·FAQ 카테고리·정적 페이지·게시판 중 하나라도 있으면 409 — 어느 쪽이든 삭제하지 않는다(PR ③·④)")
    void delete_blockedByAnyOwnedContent() {
        Site news = site(3L, "news", SiteType.PUBLIC, true, true);
        given(siteRepository.findByIdForUpdate(3L)).willReturn(Optional.of(news));
        java.util.List<Runnable> arrange = java.util.List.of(
                () -> given(bannerRepository.existsBySiteId(3L)).willReturn(true),
                () -> given(popupRepository.existsBySiteId(3L)).willReturn(true),
                () -> given(faqCategoryRepository.existsBySiteId(3L)).willReturn(true),
                () -> given(sitePageRepository.existsBySiteId(3L)).willReturn(true),
                () -> given(boardRepository.existsBySiteId(3L)).willReturn(true));
        for (Runnable owner : arrange) {
            org.mockito.Mockito.reset(bannerRepository, popupRepository, faqCategoryRepository, sitePageRepository, boardRepository);
            owner.run();
            assertThatThrownBy(() -> siteService.deleteSite(3L)).isInstanceOf(ConflictException.class);
        }
        verify(siteRepository, never()).delete(any());
    }

    @Test
    @DisplayName("삭제: 메뉴 없는 공개 사이트는 삭제되고, 감사용 스냅샷(ID·키)을 돌려준다")
    void delete_success() {
        Site news = site(3L, "news", SiteType.PUBLIC, true, true);
        given(siteRepository.findByIdForUpdate(3L)).willReturn(Optional.of(news));
        given(menuRepository.existsBySiteId(3L)).willReturn(false);

        SiteDeleteResult result = siteService.deleteSite(3L);

        verify(siteRepository).delete(news);
        assertThat(result.getId()).isEqualTo(3L);
        assertThat(result.getAuditLabel()).isEqualTo("news");
    }

    @Test
    @DisplayName("삭제: 없는 사이트는 404")
    void delete_notFound() {
        assertThatThrownBy(() -> siteService.deleteSite(99L)).isInstanceOf(ResourceNotFoundException.class);
    }

    // ── 목록 ──

    @Test
    @DisplayName("목록: 사이트별 메뉴 수(없으면 0)와 시드 여부가 채워진다")
    void list_withMenuCounts() {
        given(siteRepository.findAllByOrderByIdAsc()).willReturn(List.of(
                site(1L, "admin", SiteType.ADMIN, false, true),
                site(2L, "main", SiteType.PUBLIC, true, true),
                site(3L, "news", SiteType.PUBLIC, false, true)));
        given(menuRepository.countGroupedBySite()).willReturn(List.<Object[]>of(new Object[]{1L, 12L}, new Object[]{2L, 3L}));

        List<SiteResponse> sites = siteService.getSites();

        assertThat(sites).extracting(SiteResponse::getMenuCount).containsExactly(12L, 3L, 0L);
        assertThat(sites).extracting(SiteResponse::getSeed).containsExactly(true, true, false);
        assertThat(sites.get(0).getAllowExternalLink()).as("관리자 사이트는 항상 외부 링크 불가").isFalse();
    }
}
