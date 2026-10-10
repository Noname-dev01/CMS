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
import com.cms.admin.log.annotation.AdminActionLogged;
import com.cms.admin.log.constant.AdminActionTypes;
import com.cms.common.display.DisplayPeriod;
import com.cms.common.exception.ConflictException;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.exception.ResourceNotFoundException;
import com.cms.common.image.ImageFileValidator;
import com.cms.common.storage.FileStorage;
import com.cms.common.storage.FileStorageTransactionSupport;
import com.cms.common.storage.StorageFileNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
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
 * 배너 관리(PLAN-public-home-banner.md). 권한은 컨트롤러의 {@code @RequirePermission(BANNER, …)}가 판정하고 서비스는 대상의 존재·상태만 본다.
 *
 * <p><b>잠금</b>: 생성·삭제·순서 저장은 가드 행 {@code banner_lock}을 {@code FOR UPDATE}로 먼저 잡는다(첫 DB 조회) — 배너 행이 없어도
 * 직렬화되고(R1-1), 단건 수정은 가드 없이 대상 행만 잡는다. 잠금 순서는 항상 가드 → 행이다. 가드를 잡은 <b>뒤에</b> 비잠금 읽기를 해
 * 스냅샷이 잠금 이후에 확정되게 한다.
 *
 * <p><b>파일</b>: {@code banner} 네임스페이스. 생성은 롤백 시 정리, 삭제는 커밋 후 정리({@code FileStorageTransactionSupport}).
 */
@Service
@RequiredArgsConstructor
public class BannerService {

    /** 공개 메인이 활성 배너 전부의 이미지를 매번 내려받으므로 전체 개수를 제한한다(쟁점 5). */
    public static final int MAX_BANNERS = 10;

    /** 이미지 네임스페이스. {@code LocalDiskFileStorage}의 예약 네임스페이스라 루트 API로는 닿지 않는다. */
    public static final String STORAGE_NAMESPACE = "banner";

    static final long MAX_FILE_SIZE = 2L * 1024 * 1024;

    /** 한 변 2560px·총 4,000,000픽셀, 헤더 검사만(쟁점 3 — 본문 이미지(5MB·4096²)보다 작은 배너 전용 예산). */
    static final ImageFileValidator.Limits LIMITS = new ImageFileValidator.Limits(2560, 4_000_000L, false);

    static final String BANNER_NOT_FOUND = "배너를 찾을 수 없습니다.";

    private final BannerRepository bannerRepository;
    private final BannerLockRepository bannerLockRepository;
    private final FileStorage fileStorage;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<BannerResponse> getBanners() {
        LocalDateTime now = LocalDateTime.now(clock);
        return bannerRepository.findAllByOrderByOrdAscIdAsc().stream()
                .map(banner -> BannerResponse.from(banner, now))
                .toList();
    }

    @Transactional(readOnly = true)
    public BannerResponse getBanner(Long id) {
        Banner banner = bannerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(BANNER_NOT_FOUND));
        return BannerResponse.from(banner, LocalDateTime.now(clock));
    }

    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.BANNER_CREATE, targetType = "BANNER", targetIdExpression = "id")
    public BannerResponse createBanner(BannerCreateRequest request) {
        // 잠금 전에 끝낼 수 있는 검증을 모두 마친다 — 가드 행을 쥐는 시간을 줄인다.
        String title = requireNonBlank(request.getTitle());
        String linkUrl = normalizeLink(request.getLinkUrl());
        LocalDateTime start = DisplayPeriod.normalize(request.getDisplayStart());
        LocalDateTime end = DisplayPeriod.normalize(request.getDisplayEnd());
        DisplayPeriod.requireValid(start, end);
        boolean useYn = request.getUseYn() == null || request.getUseYn();

        MultipartFile image = request.getImage();
        if (image == null || image.isEmpty()) {
            throw new InvalidRequestException("배너 이미지를 선택해주세요.");
        }
        if (image.getSize() > MAX_FILE_SIZE) {
            throw new InvalidRequestException("배너 이미지는 2MB 이하만 업로드할 수 있습니다.");
        }
        byte[] content;
        try {
            content = image.getBytes();
        } catch (IOException e) {
            throw new InvalidRequestException("파일을 읽을 수 없습니다.");
        }
        String contentType = image.getContentType();
        ImageFileValidator.validate(content, contentType, LIMITS);

        lockGuard();
        if (bannerRepository.count() >= MAX_BANNERS) {
            throw new ConflictException("배너는 최대 " + MAX_BANNERS + "개까지 등록할 수 있습니다. 사용하지 않는 배너를 삭제해주세요.");
        }
        int ord = bannerRepository.findMaxOrd() + 1;

        String storageKey = fileStorage.store(content, "banner." + extensionFor(contentType), STORAGE_NAMESPACE);
        FileStorageTransactionSupport.deleteOnRollback(fileStorage, storageKey, STORAGE_NAMESPACE, "banner");

        LocalDateTime now = LocalDateTime.now(clock);
        Banner saved = bannerRepository.save(Banner.builder()
                .title(title)
                .linkUrl(linkUrl)
                .storageKey(storageKey)
                .contentType(contentType)
                .fileSize((long) content.length)
                .displayStart(start)
                .displayEnd(end)
                .useYn(useYn)
                .ord(ord)
                .createDate(now)
                .updateDate(now)
                .build());
        return BannerResponse.from(saved, now);
    }

    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.BANNER_UPDATE, targetType = "BANNER", targetIdExpression = "id")
    public BannerResponse updateBanner(Long id, BannerUpdateRequest request) {
        String title = requireNonBlank(request.getTitle());
        String linkUrl = normalizeLink(request.getLinkUrl());
        LocalDateTime start = DisplayPeriod.normalize(request.getDisplayStart());
        LocalDateTime end = DisplayPeriod.normalize(request.getDisplayEnd());
        DisplayPeriod.requireValid(start, end);

        Banner target = bannerRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException(BANNER_NOT_FOUND));
        LocalDateTime now = LocalDateTime.now(clock);
        target.replaceMetadata(title, linkUrl, start, end, request.getUseYn(), now);
        return BannerResponse.from(target, now);
    }

    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.BANNER_DELETE, targetType = "BANNER", targetIdExpression = "id")
    public BannerResponse deleteBanner(Long id) {
        lockGuard();
        Banner target = bannerRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException(BANNER_NOT_FOUND));
        // 행이 사라진 뒤에는 응답을 만들 수 없으므로 삭제 전에 스냅샷을 만든다
        BannerResponse snapshot = BannerResponse.from(target, LocalDateTime.now(clock));
        String storageKey = target.getStorageKey();

        bannerRepository.delete(target);
        // 커밋된 뒤에만 파일을 지운다 — 롤백되면 행과 파일이 모두 남는다
        FileStorageTransactionSupport.deleteAfterCommit(fileStorage, storageKey, STORAGE_NAMESPACE, "banner id=" + id);
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
        return current.values().stream()
                .sorted(java.util.Comparator.comparing(Banner::getOrd).thenComparing(Banner::getId))
                .map(banner -> BannerResponse.from(banner, now))
                .toList();
    }

    /** 관리자 미리보기 — 노출 여부·기간과 무관하게 행의 파일을 읽는다(공개 경로는 비노출을 404로 막으므로 따로 둔다, 쟁점 10). */
    @Transactional(readOnly = true)
    public BannerImageDownload getImage(Long id) {
        Banner banner = bannerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(BANNER_NOT_FOUND));
        try {
            return new BannerImageDownload(banner.getContentType(), fileStorage.load(banner.getStorageKey(), STORAGE_NAMESPACE));
        } catch (StorageFileNotFoundException e) {
            throw new ResourceNotFoundException("배너 이미지 파일을 찾을 수 없습니다.");
        }
    }

    private void lockGuard() {
        bannerLockRepository.findByIdForUpdate(BannerLock.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("banner_lock 가드 행이 없습니다(V34 시드 누락)."));
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

    private static String extensionFor(String contentType) {
        return switch (contentType) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/gif" -> "gif";
            default -> throw new InvalidRequestException("이미지 파일만 업로드할 수 있습니다.");
        };
    }
}
