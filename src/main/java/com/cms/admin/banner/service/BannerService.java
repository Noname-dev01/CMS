package com.cms.admin.banner.service;

import com.cms.admin.banner.domain.Banner;
import com.cms.admin.banner.domain.BannerLock;
import com.cms.admin.banner.dto.request.BannerCreateRequest;
import com.cms.admin.banner.dto.request.BannerOrderRequest;
import com.cms.admin.banner.dto.request.BannerUpdateRequest;
import com.cms.admin.banner.dto.response.BannerImageDownload;
import com.cms.admin.banner.dto.response.BannerResponse;
import com.cms.admin.banner.repository.BannerLockRepository;
import com.cms.admin.banner.repository.BannerRepository;
import com.cms.admin.contentimage.domain.ContentImage;
import com.cms.admin.contentimage.domain.ContentImageRef;
import com.cms.admin.contentimage.repository.ContentImageRefRepository;
import com.cms.admin.contentimage.service.ContentImageService;
import com.cms.admin.log.annotation.AdminActionLogged;
import com.cms.admin.log.constant.AdminActionTypes;
import com.cms.admin.media.service.MediaImageInfo;
import com.cms.admin.media.service.MediaImageLookup;
import com.cms.admin.media.service.MediaImageRegistrar;
import com.cms.admin.media.service.MediaImageRemover;
import com.cms.admin.media.service.MediaImageSelector;
import com.cms.admin.site.ContentSiteScope;
import com.cms.admin.site.SiteScope;
import com.cms.common.display.DisplayPeriod;
import com.cms.common.exception.ConflictException;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.exception.ResourceNotFoundException;
import com.cms.common.image.ImageFileValidator;
import com.cms.common.storage.FileStorage;
import com.cms.common.storage.StorageFileNotFoundException;
import com.cms.config.auth.AdminSecurityService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 배너 관리(PLAN-public-home-banner.md, 이미지 연결은 PLAN-media-library.md). 권한은 컨트롤러의 {@code @RequirePermission(BANNER, …)}가
 * 판정하고 서비스는 대상의 존재·상태만 본다.
 *
 * <p><b>잠금</b>: 생성·삭제·순서 저장은 가드 행 {@code banner_lock}을 {@code FOR UPDATE}로 먼저 잡는다(첫 DB 조회) — 배너 행이 없어도
 * 직렬화되고(R1-1), 단건 수정은 가드 없이 대상 행만 잡는다. 잠금 순서는 가드 → 소유자 행 → 이미지 행 → 참조 → 카운터다(D5). 가드를 잡은
 * <b>뒤에</b> 비잠금 읽기를 해 스냅샷이 잠금 이후에 확정되게 한다.
 *
 * <p><b>이미지</b>: 배너와 이미지의 연결은 {@code content_image_ref(BANNER)} 한 곳이다. 직접 올리면 {@code banner} 네임스페이스·{@code BANNER}
 * 출처로 라이브러리에 함께 등록되고, 라이브러리에서 고르면 {@link MediaImageSelector}가 권한·출처·용도 한도를 검증한다. 삭제는 커밋 후 파일 정리
 * ({@link MediaImageRemover}).
 */
@Service
@RequiredArgsConstructor
public class BannerService {

    /** 공개 메인이 활성 배너 전부의 이미지를 매번 내려받으므로 전체 개수를 제한한다(쟁점 5). */
    public static final int MAX_BANNERS = 10;

    /** 직접 올린 배너 이미지의 네임스페이스. {@code LocalDiskFileStorage}의 예약 네임스페이스라 루트 API로는 닿지 않는다. */
    public static final String STORAGE_NAMESPACE = "banner";

    static final long MAX_FILE_SIZE = 2L * 1024 * 1024;

    /** 한 변 2560px·총 4,000,000픽셀, 헤더 검사만(쟁점 3 — 본문 이미지(5MB·4096²)보다 작은 배너 전용 예산). */
    static final ImageFileValidator.Limits LIMITS = new ImageFileValidator.Limits(2560, 4_000_000L, false);

    static final String BANNER_NOT_FOUND = "배너를 찾을 수 없습니다.";
    static final String STALE_VERSION = "다른 관리자가 이 배너를 먼저 수정했습니다. 화면을 새로 고친 뒤 다시 시도해주세요.";

    private final BannerRepository bannerRepository;
    private final BannerLockRepository bannerLockRepository;
    private final ContentImageRefRepository refRepository;
    private final MediaImageLookup imageLookup;
    private final MediaImageRegistrar imageRegistrar;
    private final MediaImageSelector imageSelector;
    private final MediaImageRemover imageRemover;
    private final AdminSecurityService adminSecurityService;
    private final ContentSiteScope contentSiteScope;
    private final FileStorage fileStorage;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<BannerResponse> getBanners() {
        LocalDateTime now = LocalDateTime.now(clock);
        return toResponses(bannerRepository.findAllByOrderByOrdAscIdAsc(), now);
    }

    @Transactional(readOnly = true)
    public BannerResponse getBanner(Long id) {
        Banner banner = bannerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(BANNER_NOT_FOUND));
        return toResponse(banner, LocalDateTime.now(clock));
    }

    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.BANNER_CREATE, targetType = "BANNER", targetIdExpression = "id")
    public BannerResponse createBanner(BannerCreateRequest request) {
        // 잠금 전에 끝낼 수 있는 검증을 모두 마친다 — 가드 행을 쥐는 시간을 줄인다.
        String title = requireNonBlank(request.getTitle());
        String linkUrl = normalizeLink(request.getLinkUrl());
        LocalDateTime start = normalizeBound(request.getDisplayStart());
        LocalDateTime end = normalizeBound(request.getDisplayEnd());
        DisplayPeriod.requireValid(start, end);
        boolean useYn = request.getUseYn() == null || request.getUseYn();
        Long siteId = SiteScope.parse(request.getSiteScope());

        MultipartFile upload = request.getImage();
        boolean hasUpload = upload != null && !upload.isEmpty();
        Long selectedImageId = request.getImageId();
        if (hasUpload && selectedImageId != null) {
            throw new InvalidRequestException("이미지 파일과 라이브러리 이미지 중 하나만 지정해주세요.");
        }
        if (!hasUpload && selectedImageId == null) {
            throw new InvalidRequestException("배너 이미지를 선택해주세요.");
        }
        byte[] content = null;
        String contentType = null;
        if (hasUpload) {
            if (upload.getSize() > MAX_FILE_SIZE) {
                throw new InvalidRequestException("배너 이미지는 2MB 이하만 업로드할 수 있습니다.");
            }
            try {
                content = upload.getBytes();
            } catch (IOException e) {
                throw new InvalidRequestException("파일을 읽을 수 없습니다.");
            }
            contentType = upload.getContentType();
            ImageFileValidator.validate(content, contentType, LIMITS);
        }

        lockGuard();
        if (bannerRepository.count() >= MAX_BANNERS) {
            throw new ConflictException("배너는 최대 " + MAX_BANNERS + "개까지 등록할 수 있습니다. 사용하지 않는 배너를 삭제해주세요.");
        }
        int ord = bannerRepository.findMaxOrd() + 1;
        contentSiteScope.requireAssignable(siteId);

        ContentImage image;
        if (hasUpload) {
            String uploaderId = adminSecurityService.getCurrentAdminUserId();
            if (uploaderId == null) {
                throw new AccessDeniedException("인증 정보를 확인할 수 없습니다.");
            }
            image = imageRegistrar.register(content, contentType, upload.getOriginalFilename(), uploaderId,
                    ContentImageService.SCOPE_BANNER, null, STORAGE_NAMESPACE);
        } else {
            image = imageSelector.select(selectedImageId, MAX_FILE_SIZE, LIMITS, "배너");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        Banner saved = bannerRepository.save(Banner.builder()
                .title(title)
                .linkUrl(linkUrl)
                .displayStart(start)
                .displayEnd(end)
                .useYn(useYn)
                .ord(ord)
                .siteId(siteId)
                .createDate(now)
                .updateDate(now)
                .build());
        refRepository.save(new ContentImageRef(ContentImageService.OWNER_BANNER, saved.getId(), image.getId()));
        return BannerResponse.from(saved, now, MediaImageInfo.from(image));
    }

    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.BANNER_UPDATE, targetType = "BANNER", targetIdExpression = "id")
    public BannerResponse updateBanner(Long id, BannerUpdateRequest request) {
        String title = requireNonBlank(request.getTitle());
        String linkUrl = normalizeLink(request.getLinkUrl());
        LocalDateTime start = normalizeBound(request.getDisplayStart());
        LocalDateTime end = normalizeBound(request.getDisplayEnd());
        DisplayPeriod.requireValid(start, end);
        Long siteId = SiteScope.parseRequired(request.getSiteScope());
        int version = SiteScope.requireVersion(request.getVersion(), 1);

        Banner target = bannerRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException(BANNER_NOT_FOUND));
        // 행 락을 잡은 뒤 낡은 화면을 거른다 — 다른 관리자가 방금 바꾼 표시 대상을 옛 값으로 되돌려 공개 범위를 넓히지 못하게(PLAN-site-menu.md S6b)
        if (target.getVersion() != version) {
            throw new ConflictException(STALE_VERSION);
        }
        contentSiteScope.requireAssignable(siteId);
        LocalDateTime now = LocalDateTime.now(clock);
        target.replaceMetadata(title, linkUrl, start, end, request.getUseYn(), siteId, now);
        return toResponse(target, now);
    }

    /**
     * 격리 수준을 READ_COMMITTED로 고정한다 — 이미지 삭제 경로의 참조 잠금 읽기가 REPEATABLE READ에서 비고유 인덱스 갭 잠금으로 다른 이미지의
     * 참조 INSERT(직접 업로드)를 막는 순환을 피한다(PLAN-media-library.md D5·R3-1·R4-2). 이 메서드는 최상위 진입점이라 새 트랜잭션에서 적용된다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @AdminActionLogged(actionType = AdminActionTypes.BANNER_DELETE, targetType = "BANNER", targetIdExpression = "id")
    public BannerResponse deleteBanner(Long id) {
        lockGuard();
        Banner target = bannerRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException(BANNER_NOT_FOUND));
        // 행이 사라진 뒤에는 응답을 만들 수 없으므로 삭제 전에 스냅샷을 만든다
        BannerResponse snapshot = toResponse(target, LocalDateTime.now(clock));

        bannerRepository.delete(target);
        // 이미지 행을 먼저 잠그고 자기 참조를 지운 뒤, 직접 업로드 출처이고 다른 참조가 없으면 이미지·파일(커밋 후)까지 정리한다
        if (snapshot.getImageId() != null) {
            imageRemover.detachOwner(ContentImageService.OWNER_BANNER, id, snapshot.getImageId());
        }
        return snapshot;
    }

    /**
     * 표시 순서 일괄 저장. 요청 id 집합이 현재 집합과 다르면(다른 관리자가 추가·삭제함) 409, 같으면 배열 순서대로 {@code ord = 0..n-1}을 매기되
     * 값이 같은 행은 쓰지 않는다(update_date 보존). 동시에 같은 집합을 저장하면 나중 요청이 이긴다(순서만의 충돌이라 수용).
     */
    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.BANNER_ORDER, targetType = "BANNER")
    public List<BannerResponse> saveOrder(BannerOrderRequest request) {
        List<Long> ids = request.getIds();
        if (new HashSet<>(ids).size() != ids.size()) {
            throw new InvalidRequestException("배너 순서에 중복된 항목이 있습니다.");
        }

        lockGuard();
        Map<Long, Banner> current = bannerRepository.findAllForUpdate().stream()
                .collect(Collectors.toMap(Banner::getId, Function.identity()));
        Set<Long> requested = new HashSet<>(ids);
        if (!requested.equals(current.keySet())) {
            throw new ConflictException("배너 목록이 변경되었습니다. 화면을 새로 고친 뒤 다시 시도해주세요.");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        for (int index = 0; index < ids.size(); index++) {
            Banner banner = current.get(ids.get(index));
            if (banner.getOrd() != index) {
                banner.changeOrder(index, now);
            }
        }
        List<Banner> ordered = current.values().stream()
                .sorted(java.util.Comparator.comparing(Banner::getOrd).thenComparing(Banner::getId))
                .toList();
        return toResponses(ordered, now);
    }

    /** 관리자 미리보기 — 노출 여부·기간과 무관하게 연결된 이미지 파일을 읽는다(공개 경로는 비노출을 404로 막으므로 따로 둔다, 쟁점 10). */
    @Transactional(readOnly = true)
    public BannerImageDownload getImage(Long id) {
        bannerRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException(BANNER_NOT_FOUND));
        MediaImageInfo image = imageLookup.forOwner(ContentImageService.OWNER_BANNER, id)
                .orElseThrow(() -> new ResourceNotFoundException("배너 이미지를 찾을 수 없습니다."));
        try {
            byte[] content = image.isRootNamespace()
                    ? fileStorage.load(image.storageKey())
                    : fileStorage.load(image.storageKey(), image.namespace());
            return new BannerImageDownload(image.contentType(), content);
        } catch (StorageFileNotFoundException e) {
            throw new ResourceNotFoundException("배너 이미지 파일을 찾을 수 없습니다.");
        }
    }

    private BannerResponse toResponse(Banner banner, LocalDateTime now) {
        MediaImageInfo image = imageLookup.forOwner(ContentImageService.OWNER_BANNER, banner.getId()).orElse(null);
        return BannerResponse.from(banner, now, image);
    }

    /** 목록용 — 소유자 ID 집합의 이미지를 한 번에 읽는다(N+1 없음). */
    private List<BannerResponse> toResponses(List<Banner> banners, LocalDateTime now) {
        Map<Long, MediaImageInfo> images = imageLookup.forOwners(ContentImageService.OWNER_BANNER,
                banners.stream().map(Banner::getId).toList());
        return banners.stream()
                .map(banner -> BannerResponse.from(banner, now, images.get(banner.getId())))
                .toList();
    }

    private void lockGuard() {
        bannerLockRepository.findByIdForUpdate(BannerLock.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("banner_lock 가드 행이 없습니다(V2 시드 누락)."));
    }

    /**
     * 노출 기간의 한쪽 값을 검증·절단한다. 제품 허용 범위(2000-01-01 이상 2100-01-01 미만)를 <b>분 절단 전에</b> 확인한다 — 범위 밖 값(예: 0001년)이
     * 조용히 저장돼 의미 없는 기간이 되는 것을 막는다. 게시글 예약 게시와 같은 {@link DisplayPeriod#requireInRange} 규칙이다.
     */
    private static LocalDateTime normalizeBound(LocalDateTime value) {
        DisplayPeriod.requireInRange(value);
        return DisplayPeriod.normalize(value);
    }

    private static String requireNonBlank(String title) {
        if (title == null || title.isBlank()) {
            throw new InvalidRequestException("제목은 공백일 수 없습니다.");
        }
        return title.strip();
    }

    /** 빈 문자열·공백은 링크 없음(null)으로 정규화한다. 형식 검증은 요청 DTO의 {@code @SafeLinkUrl}이 이미 했다. */
    private static String normalizeLink(String linkUrl) {
        return linkUrl == null || linkUrl.isBlank() ? null : linkUrl.strip();
    }
}
