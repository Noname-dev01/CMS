package com.cms.admin.banner.service;

import com.cms.admin.banner.domain.Banner;
import com.cms.admin.banner.domain.BannerLock;
import com.cms.admin.banner.dto.request.BannerCreateRequest;
import com.cms.admin.banner.dto.request.BannerOrderRequest;
import com.cms.admin.banner.dto.request.BannerUpdateRequest;
import com.cms.admin.banner.dto.response.BannerResponse;
import com.cms.admin.banner.repository.BannerLockRepository;
import com.cms.admin.banner.repository.BannerRepository;
import com.cms.admin.contentimage.domain.ContentImage;
import com.cms.admin.contentimage.domain.ContentImageRef;
import com.cms.admin.contentimage.repository.ContentImageRefRepository;
import com.cms.admin.media.service.MediaImageInfo;
import com.cms.admin.media.service.MediaImageLookup;
import com.cms.admin.media.service.MediaImageRegistrar;
import com.cms.admin.media.service.MediaImageRemover;
import com.cms.admin.media.service.MediaImageSelector;
import com.cms.config.auth.AdminSecurityService;
import com.cms.common.exception.ConflictException;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.exception.ResourceNotFoundException;
import com.cms.common.image.ImageFileValidatorTest;
import com.cms.common.storage.FileStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 순수 Mockito 단위 시험 — 검증 순서(잠금 전 검증)·가드 행 잠금 순서·상한·정규화·순서 저장의 쓰기 최소화를 고정한다.
 * 커밋/롤백 시점 파일 정리의 실제 동작은 {@code BannerApiIntegrationTest}가 실제 스토리지로 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class BannerServiceTest {

    @Mock BannerRepository bannerRepository;
    @Mock BannerLockRepository bannerLockRepository;
    @Mock ContentImageRefRepository refRepository;
    @Mock MediaImageLookup imageLookup;
    @Mock MediaImageRegistrar imageRegistrar;
    @Mock MediaImageSelector imageSelector;
    @Mock MediaImageRemover imageRemover;
    @Mock com.cms.admin.site.ContentSiteScope contentSiteScope;
    @Mock AdminSecurityService adminSecurityService;
    @Mock FileStorage fileStorage;

    /** UTC 2026-10-09 03:00 = KST 12:00 — 시스템 시각·기본 시간대와 무관하게 저장 시각을 단언한다. */
    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 9, 12, 0);

    @Spy Clock clock = Clock.fixed(Instant.parse("2026-10-09T03:00:00Z"), ZoneId.of("Asia/Seoul"));

    @InjectMocks BannerService bannerService;

    @BeforeEach
    void setUp() throws Exception {
        // create/delete는 TransactionSynchronizationManager.registerSynchronization()을 호출한다 — 실제 트랜잭션 없이 호출하려면 수동 활성화
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.initSynchronization();
        }
        lenient().when(bannerLockRepository.findByIdForUpdate(BannerLock.SINGLETON_ID)).thenReturn(Optional.of(newLock()));
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static BannerLock newLock() throws Exception {
        Constructor<BannerLock> constructor = BannerLock.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static MockMultipartFile pngImage() throws IOException {
        return new MockMultipartFile("image", "b.png", "image/png", ImageFileValidatorTest.png(20, 10));
    }

    private static BannerCreateRequest createRequest(MockMultipartFile image) {
        BannerCreateRequest request = new BannerCreateRequest();
        request.setTitle("  가을 행사  ");
        request.setImage(image);
        return request;
    }

    private static Banner banner(long id, int ord) {
        return Banner.builder().id(id).title("b" + id)
                .useYn(true).ord(ord).createDate(NOW).updateDate(NOW).build();
    }

    // ===== 등록 =====

    @Test
    @DisplayName("등록: 가드 행을 잠근 뒤 개수·최대 ord를 읽고 ord = max+1, 제목은 strip, 기간은 분 단위로 절단되어 저장되며 파일은 banner 네임스페이스·BANNER 출처 이미지 행으로 등록되고 참조 행이 소유자와 이미지를 잇는다")
    void create_success() throws Exception {
        given(bannerRepository.count()).willReturn(3L);
        given(bannerRepository.findMaxOrd()).willReturn(6);
        given(adminSecurityService.getCurrentAdminUserId()).willReturn("admin");
        given(imageRegistrar.register(any(), eq("image/png"), eq("b.png"), eq("admin"), eq("BANNER"), isNull(), eq("banner")))
                .willReturn(ContentImage.builder().id(70L).storageKey("2026/10/09/abc.png").contentType("image/png").fileSize(5L).namespace("banner").build());
        given(bannerRepository.save(any(Banner.class))).willAnswer(i -> {
            Banner b = i.getArgument(0);
            return Banner.builder().id(11L).title(b.getTitle()).linkUrl(b.getLinkUrl()).displayStart(b.getDisplayStart())
                    .displayEnd(b.getDisplayEnd()).useYn(b.getUseYn()).ord(b.getOrd()).createDate(b.getCreateDate()).updateDate(b.getUpdateDate()).build();
        });
        BannerCreateRequest request = createRequest(pngImage());
        request.setLinkUrl("   ");
        request.setDisplayStart(LocalDateTime.of(2026, 10, 10, 9, 0, 59, 500_000_000));
        request.setDisplayEnd(LocalDateTime.of(2026, 10, 11, 9, 0, 1));

        BannerResponse response = bannerService.createBanner(request);

        ArgumentCaptor<Banner> saved = ArgumentCaptor.forClass(Banner.class);
        verify(bannerRepository).save(saved.capture());
        Banner banner = saved.getValue();
        assertThat(banner.getTitle()).isEqualTo("가을 행사");
        assertThat(banner.getLinkUrl()).as("공백 링크는 링크 없음").isNull();
        assertThat(banner.getOrd()).isEqualTo(7);
        assertThat(banner.getDisplayStart()).isEqualTo(LocalDateTime.of(2026, 10, 10, 9, 0));
        assertThat(banner.getDisplayEnd()).isEqualTo(LocalDateTime.of(2026, 10, 11, 9, 0));
        assertThat(banner.getUseYn()).as("누락 시 노출").isTrue();
        assertThat(banner.getCreateDate()).isEqualTo(NOW);
        assertThat(response.getStatus()).isEqualTo(BannerResponse.Status.SCHEDULED);
        assertThat(response.getImageId()).isEqualTo(70L);
        ArgumentCaptor<ContentImageRef> ref = ArgumentCaptor.forClass(ContentImageRef.class);
        verify(refRepository).save(ref.capture());
        assertThat(ref.getValue().getOwnerType()).isEqualTo("BANNER");
        assertThat(ref.getValue().getOwnerId()).isEqualTo(11L);
        assertThat(ref.getValue().getImageId()).isEqualTo(70L);

        InOrder order = inOrder(bannerLockRepository, bannerRepository, imageRegistrar);
        order.verify(bannerLockRepository).findByIdForUpdate(BannerLock.SINGLETON_ID);
        order.verify(bannerRepository).count();
        order.verify(bannerRepository).findMaxOrd();
        order.verify(imageRegistrar).register(any(), eq("image/png"), eq("b.png"), eq("admin"), eq("BANNER"), isNull(), eq("banner"));
    }

    @Test
    @DisplayName("등록: 검증 실패(이미지 없음·형식·크기, 기간 역전, 소수 초 절단 후 동일)는 가드 행을 잠그기 전에 400이고 파일도 저장하지 않는다")
    void create_validationFailsBeforeLock() throws Exception {
        BannerCreateRequest noImage = createRequest(null);
        assertThatThrownBy(() -> bannerService.createBanner(noImage)).isInstanceOf(InvalidRequestException.class);

        BannerCreateRequest notImage = createRequest(new MockMultipartFile("image", "a.txt", "text/plain", "x".getBytes()));
        assertThatThrownBy(() -> bannerService.createBanner(notImage)).isInstanceOf(InvalidRequestException.class);

        BannerCreateRequest tooBig = createRequest(new MockMultipartFile("image", "b.png", "image/png", new byte[2 * 1024 * 1024 + 1]));
        assertThatThrownBy(() -> bannerService.createBanner(tooBig)).isInstanceOf(InvalidRequestException.class);

        BannerCreateRequest overDimension = createRequest(new MockMultipartFile("image", "b.png", "image/png", ImageFileValidatorTest.png(2561, 10)));
        assertThatThrownBy(() -> bannerService.createBanner(overDimension)).isInstanceOf(InvalidRequestException.class);

        BannerCreateRequest reversed = createRequest(pngImage());
        reversed.setDisplayStart(LocalDateTime.of(2026, 10, 11, 0, 0));
        reversed.setDisplayEnd(LocalDateTime.of(2026, 10, 10, 0, 0));
        assertThatThrownBy(() -> bannerService.createBanner(reversed)).isInstanceOf(InvalidRequestException.class);

        BannerCreateRequest collapsed = createRequest(pngImage());
        collapsed.setDisplayStart(LocalDateTime.of(2026, 10, 10, 12, 0, 0, 100_000_000));
        collapsed.setDisplayEnd(LocalDateTime.of(2026, 10, 10, 12, 0, 0, 900_000_000));
        assertThatThrownBy(() -> bannerService.createBanner(collapsed)).isInstanceOf(InvalidRequestException.class);

        BannerCreateRequest blankTitle = createRequest(pngImage());
        blankTitle.setTitle("   ");
        assertThatThrownBy(() -> bannerService.createBanner(blankTitle)).isInstanceOf(InvalidRequestException.class);

        BannerCreateRequest both = createRequest(pngImage());
        both.setImageId(3L);
        assertThatThrownBy(() -> bannerService.createBanner(both)).isInstanceOf(InvalidRequestException.class);

        verifyNoInteractions(bannerLockRepository, imageRegistrar, imageSelector);
        verify(bannerRepository, never()).save(any());
    }

    @Test
    @DisplayName("등록: 상한(10개)에 도달하면 409이고 파일을 저장하지 않는다")
    void create_limitReached_conflict() throws Exception {
        given(bannerRepository.count()).willReturn((long) BannerService.MAX_BANNERS);

        assertThatThrownBy(() -> bannerService.createBanner(createRequest(pngImage()))).isInstanceOf(ConflictException.class);

        verifyNoInteractions(imageRegistrar, imageSelector);
        verify(bannerRepository, never()).save(any());
    }

    @Test
    @DisplayName("등록: 가드 행이 없으면(V34 시드 누락) IllegalStateException — 조용히 잠금 없이 진행하지 않는다")
    void create_missingGuardRow_failsClosed() throws Exception {
        given(bannerLockRepository.findByIdForUpdate(BannerLock.SINGLETON_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> bannerService.createBanner(createRequest(pngImage()))).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(imageRegistrar, imageSelector);
    }

    @Test
    @DisplayName("등록: 라이브러리 이미지를 고르면 선택 검증(권한·출처·용도 한도)을 거쳐 참조 행만 만든다 — 새 이미지 행·파일은 만들지 않는다")
    void create_selectingLibraryImage_usesSelector() {
        given(bannerRepository.count()).willReturn(0L);
        given(bannerRepository.findMaxOrd()).willReturn(-1);
        given(imageSelector.select(eq(33L), eq(2L * 1024 * 1024), any(), eq("배너")))
                .willReturn(ContentImage.builder().id(33L).storageKey("k").contentType("image/png").fileSize(7L).namespace("").build());
        given(bannerRepository.save(any(Banner.class))).willAnswer(i -> {
            Banner b = i.getArgument(0);
            return Banner.builder().id(12L).title(b.getTitle()).useYn(true).ord(b.getOrd()).createDate(b.getCreateDate()).updateDate(b.getUpdateDate()).build();
        });
        BannerCreateRequest request = createRequest(null);
        request.setImageId(33L);

        BannerResponse response = bannerService.createBanner(request);

        assertThat(response.getImageId()).isEqualTo(33L);
        verify(imageRegistrar, never()).register(any(), any(), any(), any(), any(), any(), any());
        InOrder order = inOrder(bannerLockRepository, imageSelector, bannerRepository, refRepository);
        order.verify(bannerLockRepository).findByIdForUpdate(BannerLock.SINGLETON_ID);
        order.verify(imageSelector).select(eq(33L), eq(2L * 1024 * 1024), any(), eq("배너"));
        order.verify(bannerRepository).save(any(Banner.class));
        order.verify(refRepository).save(any(ContentImageRef.class));
    }

    // ===== 수정 (PUT 전체 교체) =====

    @Test
    @DisplayName("수정: 전체 교체 — 링크·시작·종료의 null은 해제이고 제목은 strip, 노출 여부와 update_date가 반영된다")
    void update_fullReplace_nullClears() {
        Banner target = banner(5, 0);
        target.replaceMetadata("옛 제목", "/boards/1", LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 2, 1, 0, 0), true, null, NOW);
        given(bannerRepository.findByIdForUpdate(5L)).willReturn(Optional.of(target));

        BannerResponse response = bannerService.updateBanner(5L,
                BannerUpdateRequest.builder().title(" 새 제목 ").linkUrl("").displayStart(null).displayEnd(null).useYn(false)
                        .siteScope("COMMON").version(target.getVersion()).build());

        assertThat(target.getTitle()).isEqualTo("새 제목");
        assertThat(target.getLinkUrl()).isNull();
        assertThat(target.getDisplayStart()).isNull();
        assertThat(target.getDisplayEnd()).isNull();
        assertThat(target.getUseYn()).isFalse();
        assertThat(response.getStatus()).isEqualTo(BannerResponse.Status.HIDDEN);
        verifyNoInteractions(bannerLockRepository);   // 단건 수정은 가드 없이 대상 행만 잠근다
    }

    @Test
    @DisplayName("수정: 시작만 지우기·종료만 지우기는 허용, 한쪽만 바꿔 역전되는 최종 값은 400")
    void update_periodValidatedOnFinalValues() {
        Banner target = banner(5, 0);
        given(bannerRepository.findByIdForUpdate(5L)).willReturn(Optional.of(target));
        LocalDateTime start = LocalDateTime.of(2026, 10, 10, 9, 0);
        LocalDateTime end = LocalDateTime.of(2026, 10, 20, 0, 0);

        bannerService.updateBanner(5L, update(start, null, target.getVersion()));
        assertThat(target.getDisplayStart()).isEqualTo(start);
        assertThat(target.getDisplayEnd()).isNull();
        bannerService.updateBanner(5L, update(null, end, target.getVersion()));
        assertThat(target.getDisplayStart()).isNull();
        assertThat(target.getDisplayEnd()).isEqualTo(end);

        assertThatThrownBy(() -> bannerService.updateBanner(5L, update(end, start, target.getVersion()))).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> bannerService.updateBanner(5L, update(start, start, target.getVersion()))).isInstanceOf(InvalidRequestException.class);
    }

    private static BannerUpdateRequest update(LocalDateTime start, LocalDateTime end) {
        return update(start, end, 1);
    }

    private static BannerUpdateRequest update(LocalDateTime start, LocalDateTime end, int version) {
        return BannerUpdateRequest.builder().title("t").displayStart(start).displayEnd(end).useYn(true).siteScope("COMMON").version(version).build();
    }

    @Test
    @DisplayName("수정: 없는 배너는 404, 요청 검증 실패는 행을 잠그기 전에 400")
    void update_notFoundAndValidationOrder() {
        given(bannerRepository.findByIdForUpdate(9L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> bannerService.updateBanner(9L, update(null, null))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> bannerService.updateBanner(9L,
                BannerUpdateRequest.builder().title("  ").useYn(true).build())).isInstanceOf(InvalidRequestException.class);
        verify(bannerRepository).findByIdForUpdate(9L);   // 공백 제목 요청은 조회 전에 거부
    }

    // ===== 삭제 =====

    @Test
    @DisplayName("삭제: 가드 → 대상 행 순서로 잠그고 행을 지우며, 응답은 삭제 전 스냅샷이고 이미지 정리는 공통 제거 작업자에 위임한다")
    void delete_locksGuardThenRow_registersAfterCommitFileDelete() {
        Banner target = banner(5, 2);
        given(bannerRepository.findByIdForUpdate(5L)).willReturn(Optional.of(target));
        given(imageLookup.forOwner("BANNER", 5L)).willReturn(Optional.of(
                new MediaImageInfo(70L, "k5", "banner", "image/png", 1L, "b5.png")));

        BannerResponse response = bannerService.deleteBanner(5L);

        assertThat(response.getId()).isEqualTo(5L);
        InOrder order = inOrder(bannerLockRepository, bannerRepository, imageRemover);
        order.verify(bannerLockRepository).findByIdForUpdate(BannerLock.SINGLETON_ID);
        order.verify(bannerRepository).findByIdForUpdate(5L);
        order.verify(bannerRepository).delete(target);
        order.verify(imageRemover).detachOwner("BANNER", 5L, 70L);   // 이미지 정리(행 잠금·참조 삭제·커밋 후 파일 삭제)는 공통 제거 작업자가 한다
        verify(fileStorage, never()).delete(anyString(), anyString());
    }

    @Test
    @DisplayName("삭제: 없는 배너는 404이고 이미지 정리도 하지 않는다")
    void delete_notFound() {
        given(bannerRepository.findByIdForUpdate(9L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> bannerService.deleteBanner(9L)).isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(imageRemover);
        verify(bannerRepository, never()).delete(any());
    }

    // ===== 순서 저장 =====

    @Test
    @DisplayName("순서 저장: 중복 id는 가드 행을 잠그기 전에 400")
    void saveOrder_duplicateIds_badRequestBeforeLock() {
        assertThatThrownBy(() -> bannerService.saveOrder(new BannerOrderRequest(List.of(1L, 1L)))).isInstanceOf(InvalidRequestException.class);

        verifyNoInteractions(bannerLockRepository);
    }

    @Test
    @DisplayName("순서 저장: 요청 집합이 현재 집합과 다르면(추가·삭제됨, 일부만 보냄) 409이고 아무 행도 바꾸지 않는다")
    void saveOrder_setMismatch_conflict() {
        Banner a = banner(1, 0);
        Banner b = banner(2, 1);
        given(bannerRepository.findAllForUpdate()).willReturn(List.of(a, b));

        assertThatThrownBy(() -> bannerService.saveOrder(new BannerOrderRequest(List.of(1L)))).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> bannerService.saveOrder(new BannerOrderRequest(List.of(1L, 2L, 3L)))).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> bannerService.saveOrder(new BannerOrderRequest(List.of(1L, 3L)))).isInstanceOf(ConflictException.class);

        assertThat(a.getOrd()).isZero();
        assertThat(b.getOrd()).isEqualTo(1);
    }

    @Test
    @DisplayName("순서 저장: 배열 순서대로 ord 0..n-1을 매기되 값이 같은 행은 쓰지 않는다(update_date 보존)")
    void saveOrder_assignsOrdAndSkipsUnchangedRows() {
        LocalDateTime old = LocalDateTime.of(2026, 1, 1, 0, 0);
        Banner a = Banner.builder().id(1L).title("a").useYn(true).ord(0).updateDate(old).build();
        Banner b = Banner.builder().id(2L).title("b").useYn(true).ord(5).updateDate(old).build();
        Banner c = Banner.builder().id(3L).title("c").useYn(true).ord(9).updateDate(old).build();
        given(bannerRepository.findAllForUpdate()).willReturn(List.of(a, b, c));

        List<BannerResponse> result = bannerService.saveOrder(new BannerOrderRequest(List.of(1L, 3L, 2L)));

        assertThat(a.getOrd()).isZero();
        assertThat(a.getUpdateDate()).as("값이 같은 행은 쓰지 않는다").isEqualTo(old);
        assertThat(c.getOrd()).isEqualTo(1);
        assertThat(b.getOrd()).isEqualTo(2);
        assertThat(c.getUpdateDate()).isEqualTo(NOW);
        assertThat(result).extracting(BannerResponse::getId).containsExactly(1L, 3L, 2L);
        verify(bannerLockRepository).findByIdForUpdate(BannerLock.SINGLETON_ID);
    }

    @Test
    @DisplayName("순서 저장: 배너가 하나도 없을 때 빈 목록 저장은 성공(no-op)")
    void saveOrder_emptyIsNoop() {
        given(bannerRepository.findAllForUpdate()).willReturn(List.of());

        assertThat(bannerService.saveOrder(new BannerOrderRequest(List.of()))).isEmpty();
    }

    // ===== 상태 계산 =====

    @Test
    @DisplayName("상태: HIDDEN > SCHEDULED > EXPIRED > ACTIVE — 종료 시각 정각은 만료")
    void statusPrecedence() {
        Banner banner = banner(1, 0);
        LocalDateTime start = LocalDateTime.of(2026, 10, 10, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 10, 20, 0, 0);
        banner.replaceMetadata("t", null, start, end, true, null, NOW);

        assertThat(BannerResponse.from(banner, start.minusMinutes(1), null).getStatus()).isEqualTo(BannerResponse.Status.SCHEDULED);
        assertThat(BannerResponse.from(banner, start, null).getStatus()).isEqualTo(BannerResponse.Status.ACTIVE);
        assertThat(BannerResponse.from(banner, end.minusMinutes(1), null).getStatus()).isEqualTo(BannerResponse.Status.ACTIVE);
        assertThat(BannerResponse.from(banner, end, null).getStatus()).isEqualTo(BannerResponse.Status.EXPIRED);
        banner.replaceMetadata("t", null, start, end, false, null, NOW);
        assertThat(BannerResponse.from(banner, start.plusDays(1), null).getStatus()).as("비노출이 기간보다 우선").isEqualTo(BannerResponse.Status.HIDDEN);
    }
}
