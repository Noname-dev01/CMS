package com.cms.admin.media.service;

import com.cms.admin.banner.domain.Banner;
import com.cms.admin.banner.repository.BannerRepository;
import com.cms.admin.board.domain.Post;
import com.cms.admin.board.repository.PostRepository;
import com.cms.admin.contentimage.config.ContentImageProperties;
import com.cms.admin.contentimage.domain.ContentImage;
import com.cms.admin.contentimage.domain.ContentImageRef;
import com.cms.admin.contentimage.domain.ContentImageUsage;
import com.cms.admin.contentimage.repository.ContentImageRefRepository;
import com.cms.admin.contentimage.repository.ContentImageRepository;
import com.cms.admin.contentimage.repository.ContentImageUsageRepository;
import com.cms.admin.contentimage.service.ContentImageService;
import com.cms.admin.log.annotation.AdminActionLogged;
import com.cms.admin.log.constant.AdminActionTypes;
import com.cms.admin.media.dto.MediaDetailResponse;
import com.cms.admin.media.dto.MediaPageResponse;
import com.cms.admin.media.dto.MediaResponse;
import com.cms.admin.permission.AdminFeature;
import com.cms.admin.permission.AdminPermissionEvaluator;
import com.cms.admin.permission.PermissionAction;
import com.cms.admin.popup.domain.Popup;
import com.cms.admin.popup.repository.PopupRepository;
import com.cms.common.exception.ConflictException;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.exception.ResourceNotFoundException;
import com.cms.common.image.ImageFileValidator;
import com.cms.common.storage.FileStorage;
import com.cms.common.storage.StorageFileNotFoundException;
import com.cms.config.auth.AdminSecurityService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 미디어 라이브러리 관리(PLAN-media-library.md). 권한은 컨트롤러의 {@code @RequirePermission(MEDIA, …)}가 판정하고, 이 서비스는 <b>출처별 가시성</b>을
 * 판정한다 — 게시판 본문 이미지({@code BOARD}·{@code NOTICE} 출처)는 ADMIN에게만 보이고 MANAGER는 라이브러리 계열 출처만 본다. 보이지 않는 이미지는
 * 존재하지 않는 것과 같은 404다(ID를 짐작해도 받아 갈 수 없다, R1-3).
 *
 * <p>감사 애너테이션은 이 최상위 진입점({@link #upload}·{@link #delete})에만 있다. 등록·제거의 공통 작업은 {@link MediaImageRegistrar}·{@link MediaImageRemover}가
 * 호출자 트랜잭션에 참여해 수행한다(R4-2).
 */
@Service
@RequiredArgsConstructor
public class MediaService {

    static final String NOT_FOUND = "이미지를 찾을 수 없습니다.";
    static final String HIDDEN_POST_LABEL = "권한이 없는 게시판의 게시글";
    private static final int MAX_CONFLICT_ITEMS = 5;
    private static final int MAX_KEYWORD_LENGTH = 200;

    private static final Set<String> LIBRARY_SCOPES = Set.of(ContentImageService.SCOPE_LIBRARY, ContentImageService.SCOPE_BANNER,
            ContentImageService.SCOPE_POPUP);
    private static final Set<String> ALL_SCOPES = Set.of(ContentImageService.SCOPE_LIBRARY, ContentImageService.SCOPE_BANNER,
            ContentImageService.SCOPE_POPUP, ContentImageService.SCOPE_BOARD, "NOTICE");

    private final ContentImageRepository imageRepository;
    private final ContentImageRefRepository refRepository;
    private final ContentImageUsageRepository usageRepository;
    private final ContentImageProperties properties;
    private final PostRepository postRepository;
    private final BannerRepository bannerRepository;
    private final PopupRepository popupRepository;
    private final MediaImageRegistrar registrar;
    private final MediaImageRemover remover;
    private final AdminSecurityService adminSecurityService;
    private final AdminPermissionEvaluator permissionEvaluator;
    private final FileStorage fileStorage;

    /** @param origin ALL·LIBRARY·BANNER·POPUP·BOARD(NOTICE 포함). 호출자가 볼 수 없는 출처와의 교집합만 조회한다 */
    @Transactional(readOnly = true)
    public MediaPageResponse list(String keyword, String origin, boolean unusedOnly, boolean selectableOnly, Pageable pageable) {
        // 전역 핸들러에 메서드 검증(ConstraintViolationException) 처리가 없어 @Size 대신 직접 400으로 거부한다(입력값은 문구에 싣지 않는다)
        if (keyword != null && keyword.length() > MAX_KEYWORD_LENGTH) {
            throw new InvalidRequestException("검색어는 " + MAX_KEYWORD_LENGTH + "자 이하로 입력해주세요.");
        }
        Set<String> visible = visibleScopes(currentAuthentication());
        if (selectableOnly) {
            // 선택 모달용 — 호출자가 볼 수 있는 출처 중 라이브러리 계열만(게시판 출처는 ADMIN이어도 다른 곳에서 고를 수 없다)
            visible = visible.stream().filter(LIBRARY_SCOPES::contains).collect(Collectors.toSet());
        }
        Set<String> scopes = requestedScopes(visible, origin);
        Page<ContentImage> page = scopes.isEmpty()
                ? Page.empty(pageable)
                : imageRepository.searchLibrary(scopes, keyword, unusedOnly, pageable);

        List<Long> ids = page.getContent().stream().map(ContentImage::getId).toList();
        Map<Long, Integer> usageCounts = new HashMap<>();
        if (!ids.isEmpty()) {
            for (ContentImageRef ref : refRepository.findByImageIdIn(ids)) {
                usageCounts.merge(ref.getImageId(), 1, Integer::sum);
            }
        }
        List<MediaResponse> content = page.getContent().stream()
                .map(image -> MediaResponse.from(image, usageCounts.getOrDefault(image.getId(), 0)))
                .toList();
        ContentImageUsage usage = usageRepository.findById(ContentImageUsage.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("content_image_usage 카운터 행이 없습니다(V25 시드 누락)."));
        return new MediaPageResponse(content, page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages(),
                page.isLast(), usage.getTotalBytes(), usage.getTotalCount(), properties.getMaxTotalBytes(), properties.getMaxCount());
    }

    @Transactional(readOnly = true)
    public MediaDetailResponse getDetail(Long id) {
        Authentication authentication = currentAuthentication();
        ContentImage image = visibleImage(imageRepository.findById(id), authentication);
        List<ContentImageRef> refs = refRepository.findByImageIdIn(List.of(id));
        return new MediaDetailResponse(MediaResponse.from(image, refs.size()), usages(refs, authentication));
    }

    /** 관리자 미리보기용 파일 — 노출 여부와 무관하게 라이브러리가 가진 파일을 그대로 읽는다(공개 경로를 관리 용도로 넓히지 않는다, D7). */
    @Transactional(readOnly = true)
    public ImageFile getFile(Long id) {
        ContentImage image = visibleImage(imageRepository.findById(id), currentAuthentication());
        try {
            byte[] content = image.isRootNamespace()
                    ? fileStorage.load(image.getStorageKey())
                    : fileStorage.load(image.getStorageKey(), image.getNamespace());
            return new ImageFile(image.getContentType(), content);
        } catch (StorageFileNotFoundException e) {
            throw new ResourceNotFoundException("이미지 파일을 찾을 수 없습니다.");
        }
    }

    /** 라이브러리 직접 업로드 — {@code LIBRARY} 출처, 루트 영역, 본문 이미지와 같은 한도(5MB·4096px). */
    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.MEDIA_UPLOAD, targetType = "MEDIA", targetIdExpression = "id")
    public MediaResponse upload(MultipartFile file) {
        String uploaderId = adminSecurityService.getCurrentAdminUserId();
        if (uploaderId == null) {
            throw new AccessDeniedException("인증 정보를 확인할 수 없습니다.");
        }
        if (file == null || file.isEmpty()) {
            throw new InvalidRequestException("업로드할 이미지를 선택해주세요.");
        }
        if (file.getSize() > ContentImageService.MAX_FILE_SIZE) {
            throw new InvalidRequestException("이미지는 5MB 이하만 업로드할 수 있습니다.");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new InvalidRequestException("파일을 읽을 수 없습니다.");
        }
        String contentType = file.getContentType();
        ImageFileValidator.validate(content, contentType, ContentImageService.LIMITS);

        ContentImage saved = registrar.register(content, contentType, file.getOriginalFilename(), uploaderId,
                ContentImageService.SCOPE_LIBRARY, null, "");
        return MediaResponse.from(saved, 0);
    }

    /**
     * 사용 중이 아닌 이미지를 지운다. 격리 수준은 READ_COMMITTED — 참조 인덱스의 잠금 읽기가 REPEATABLE READ에서 갭 잠금으로 다른 이미지의 참조
     * INSERT를 막는 순환을 피한다(D5·R3-1). 최상위 진입점이라 새 트랜잭션에서 적용된다. 잠금 순서: 이미지 행 → 참조(잠금 읽기) → 카운터.
     * 사용 중이면 409 — 메시지에 사용처 요약(종류·ID만, 제목은 상세 조회가 권한에 맞춰 보여 준다)을 싣는다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @AdminActionLogged(actionType = AdminActionTypes.MEDIA_DELETE, targetType = "MEDIA", targetIdExpression = "id")
    public MediaResponse delete(Long id) {
        Authentication authentication = currentAuthentication();
        ContentImage image = visibleImage(imageRepository.findByIdForUpdate(id), authentication);
        List<ContentImageRef> refs = remover.lockRefs(id);
        if (!refs.isEmpty()) {
            throw new ConflictException("사용 중인 이미지는 삭제할 수 없습니다. 사용처: " + describe(refs));
        }
        MediaResponse snapshot = MediaResponse.from(image, 0);
        remover.deleteLocked(image);
        return snapshot;
    }

    // ===================== 가시성 =====================

    /** ADMIN은 모든 출처, 그 밖(MANAGER)은 라이브러리 계열 출처만. */
    private Set<String> visibleScopes(Authentication authentication) {
        return isAdmin(authentication) ? ALL_SCOPES : LIBRARY_SCOPES;
    }

    private static Set<String> requestedScopes(Set<String> visible, String origin) {
        if (origin == null || origin.isBlank() || "ALL".equalsIgnoreCase(origin)) {
            return visible;
        }
        Set<String> requested = switch (origin.toUpperCase(java.util.Locale.ROOT)) {
            case "LIBRARY" -> Set.of(ContentImageService.SCOPE_LIBRARY);
            case "BANNER" -> Set.of(ContentImageService.SCOPE_BANNER);
            case "POPUP" -> Set.of(ContentImageService.SCOPE_POPUP);
            case "BOARD" -> Set.of(ContentImageService.SCOPE_BOARD, "NOTICE");
            default -> throw new InvalidRequestException("지원하지 않는 출처입니다.");
        };
        return requested.stream().filter(visible::contains).collect(Collectors.toSet());
    }

    private ContentImage visibleImage(java.util.Optional<ContentImage> found, Authentication authentication) {
        Set<String> visible = visibleScopes(authentication);
        return found.filter(image -> visible.contains(image.getScopeType()))
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
    }

    private static boolean isAdmin(Authentication authentication) {
        if (authentication == null) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if ("ROLE_ADMIN".equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    private static Authentication currentAuthentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    // ===================== 사용처 =====================

    private List<MediaDetailResponse.Usage> usages(List<ContentImageRef> refs, Authentication authentication) {
        Map<String, List<Long>> idsByType = refs.stream().collect(Collectors.groupingBy(ContentImageRef::getOwnerType,
                Collectors.mapping(ContentImageRef::getOwnerId, Collectors.toList())));

        Map<Long, Banner> banners = byId(bannerRepository.findAllById(idsByType.getOrDefault(ContentImageService.OWNER_BANNER, List.of())),
                Banner::getId);
        Map<Long, Popup> popups = byId(popupRepository.findAllById(idsByType.getOrDefault(ContentImageService.OWNER_POPUP, List.of())),
                Popup::getId);
        Map<Long, Post> posts = byId(postRepository.findAllById(idsByType.getOrDefault(ContentImageService.OWNER_POST, List.of())),
                Post::getId);
        boolean canSeeBanners = permissionEvaluator.allows(authentication, AdminFeature.BANNER, PermissionAction.READ);
        boolean canSeePopups = permissionEvaluator.allows(authentication, AdminFeature.POPUP, PermissionAction.READ);

        List<MediaDetailResponse.Usage> result = new ArrayList<>();
        for (ContentImageRef ref : refs) {
            String label = switch (ref.getOwnerType()) {
                case ContentImageService.OWNER_BANNER -> {
                    Banner banner = banners.get(ref.getOwnerId());
                    yield banner != null && canSeeBanners ? "배너 · " + banner.getTitle() : "배너 #" + ref.getOwnerId();
                }
                case ContentImageService.OWNER_POPUP -> {
                    Popup popup = popups.get(ref.getOwnerId());
                    yield popup != null && canSeePopups ? "팝업 · " + popup.getTitle() : "팝업 #" + ref.getOwnerId();
                }
                case ContentImageService.OWNER_POST -> {
                    Post post = posts.get(ref.getOwnerId());
                    yield post != null && permissionEvaluator.allowsBoard(authentication, post.getBoardId(), PermissionAction.READ)
                            ? "게시글 · " + post.getTitle()
                            : HIDDEN_POST_LABEL;
                }
                default -> ref.getOwnerType() + " #" + ref.getOwnerId();
            };
            result.add(new MediaDetailResponse.Usage(ref.getOwnerType(), ref.getOwnerId(), label));
        }
        return result;
    }

    private static <T> Map<Long, T> byId(Collection<T> items, Function<T, Long> idOf) {
        return items.stream().collect(Collectors.toMap(idOf, Function.identity(), (a, b) -> a));
    }

    /** 409 메시지용 요약 — 종류와 ID만 싣는다(제목은 권한에 따라 상세 조회가 채운다). 최대 {@value #MAX_CONFLICT_ITEMS}건 + "외 N건". */
    private static String describe(List<ContentImageRef> refs) {
        Set<String> parts = new LinkedHashSet<>();
        for (ContentImageRef ref : refs) {
            String kind = switch (ref.getOwnerType()) {
                case ContentImageService.OWNER_BANNER -> "배너";
                case ContentImageService.OWNER_POPUP -> "팝업";
                case ContentImageService.OWNER_POST -> "게시글";
                default -> ref.getOwnerType();
            };
            parts.add(kind + " #" + ref.getOwnerId());
        }
        List<String> shown = parts.stream().limit(MAX_CONFLICT_ITEMS).toList();
        String text = String.join(", ", shown);
        return parts.size() > shown.size() ? text + " 외 " + (parts.size() - shown.size()) + "건" : text;
    }

    /** 관리자 미리보기 응답용 파일 내용. */
    public record ImageFile(String contentType, byte[] content) {
    }
}
