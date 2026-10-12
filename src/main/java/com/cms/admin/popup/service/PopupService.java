package com.cms.admin.popup.service;

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
import com.cms.admin.popup.domain.Popup;
import com.cms.admin.popup.domain.PopupLimits;
import com.cms.admin.popup.domain.PopupType;
import com.cms.admin.popup.dto.request.PopupCreateRequest;
import com.cms.admin.popup.dto.request.PopupUpdateRequest;
import com.cms.admin.popup.dto.response.PopupImageDownload;
import com.cms.admin.popup.dto.response.PopupResponse;
import com.cms.admin.popup.repository.PopupRepository;
import com.cms.common.display.DisplayPeriod;
import com.cms.common.exception.ConflictException;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.exception.ResourceNotFoundException;
import com.cms.common.html.ContentBodyPolicy;
import com.cms.common.html.SanitizedHtml;
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
import java.util.List;
import java.util.Map;

/**
 * 팝업 관리(PLAN-popup.md). 권한은 컨트롤러의 {@code @RequirePermission(POPUP, …)}가 판정하고 서비스는 대상의 존재·상태만 본다.
 *
 * <p><b>잠금</b>: 생성은 직렬화할 대상이 없다(가드 행·순서·정확한 개수 상한이 없다 — 쟁점 2). 수정·삭제는 대상 행만
 * {@code findByIdForUpdate}로 잡는다 — 수정이 이 락 아래에서 {@code revision}을 올리므로 락이 빠지면 증가분이 유실된다(R4-2).
 *
 * <p><b>파일</b>: {@code popup} 네임스페이스(이미지형만). 생성은 롤백 시 정리, 삭제는 커밋 후 정리({@code FileStorageTransactionSupport}).
 *
 * <p><b>감사</b>: 실패 행의 {@code errorMessage}는 고정 문구다 — HTML 본문이 사용자 입력이라 저장·정리 예외 메시지에 섞여 감사 테이블에 남는 것을
 * 막는다(정적 페이지와 같은 이유).
 */
@Service
@RequiredArgsConstructor
public class PopupService {

    /** 관리 화면 비용을 제한하는 소프트 상한. 가드 행이 없어 동시 등록 시 요청 수만큼 넘을 수 있다(PLAN-popup.md R1-3, 수용). */
    public static final int MAX_POPUPS = 30;

    /** 이미지 네임스페이스. {@code LocalDiskFileStorage}의 예약 네임스페이스라 루트 API로는 닿지 않는다. */
    public static final String STORAGE_NAMESPACE = "popup";

    static final long MAX_FILE_SIZE = 2L * 1024 * 1024;

    /** 한 변 2560px·총 4,000,000픽셀, 헤더 검사만. 배너의 값과 같지만 배너의 비공개 상수를 참조하지 않는다(쟁점 4). */
    static final ImageFileValidator.Limits LIMITS = new ImageFileValidator.Limits(2560, 4_000_000L, false);

    static final String POPUP_NOT_FOUND = "팝업을 찾을 수 없습니다.";

    private final PopupRepository popupRepository;
    private final ContentImageRefRepository refRepository;
    private final MediaImageLookup imageLookup;
    private final MediaImageRegistrar imageRegistrar;
    private final MediaImageSelector imageSelector;
    private final MediaImageRemover imageRemover;
    private final AdminSecurityService adminSecurityService;
    private final FileStorage fileStorage;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<PopupResponse> getPopups() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Popup> popups = popupRepository.findAllByOrderByIdDesc();
        Map<Long, MediaImageInfo> images = imageLookup.forOwners(ContentImageService.OWNER_POPUP, popups.stream().map(Popup::getId).toList());
        return popups.stream()
                .map(popup -> PopupResponse.summaryOf(popup, now, images.get(popup.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public PopupResponse getPopup(Long id) {
        Popup popup = popupRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(POPUP_NOT_FOUND));
        return PopupResponse.detailOf(popup, LocalDateTime.now(clock), imageOf(popup));
    }

    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.POPUP_CREATE, targetType = "POPUP", targetIdExpression = "id",
            safeErrorMessage = "팝업 등록에 실패했습니다.")
    public PopupResponse createPopup(PopupCreateRequest request) {
        // 쓰기 전에 끝낼 수 있는 검증을 모두 마친다 — 입력 오류가 파일을 만들지 않게
        String title = requireTitle(request.getTitle());
        PopupType type = request.getPopupType();
        int posX = layoutValue(request.getPosX(), PopupLimits.DEFAULT_POS, PopupLimits.POS_MIN, PopupLimits.POS_MAX, "왼쪽 위치");
        int posY = layoutValue(request.getPosY(), PopupLimits.DEFAULT_POS, PopupLimits.POS_MIN, PopupLimits.POS_MAX, "위쪽 위치");
        int width = layoutValue(request.getWidth(), PopupLimits.DEFAULT_WIDTH, PopupLimits.WIDTH_MIN, PopupLimits.WIDTH_MAX, "폭");
        Integer height = requireHeight(request.getHeight());
        Period period = period(blankToNull(request.getDisplayStart()), blankToNull(request.getDisplayEnd()));
        boolean useYn = request.getUseYn() == null || request.getUseYn();
        String linkUrl = normalizeLink(request.getLinkUrl());
        String content = blankToNull(request.getContent());
        MultipartFile image = request.getImage();
        boolean hasImage = image != null && !image.isEmpty();
        Long selectedImageId = request.getImageId();

        String contentHtml = null;
        byte[] imageBytes = null;
        String imageType = null;
        if (type == PopupType.IMAGE) {
            requireImageTypeFields(content, height);
            if (hasImage && selectedImageId != null) {
                throw new InvalidRequestException("이미지 파일과 라이브러리 이미지 중 하나만 지정해주세요.");
            }
            if (!hasImage && selectedImageId == null) {
                throw new InvalidRequestException("팝업 이미지를 선택해주세요.");
            }
            if (hasImage) {
                imageBytes = readImage(image);
                imageType = image.getContentType();
                ImageFileValidator.validate(imageBytes, imageType, LIMITS);
            }
        } else {
            requireHtmlTypeFields(linkUrl, hasImage || selectedImageId != null);
            contentHtml = sanitizeBody(content, request.getContentFormat());
        }

        if (popupRepository.count() >= MAX_POPUPS) {
            throw new ConflictException("팝업은 최대 " + MAX_POPUPS + "개까지 등록할 수 있습니다. 사용하지 않는 팝업을 삭제해주세요.");
        }

        ContentImage stored = null;
        if (type == PopupType.IMAGE) {
            if (hasImage) {
                String uploaderId = adminSecurityService.getCurrentAdminUserId();
                if (uploaderId == null) {
                    throw new AccessDeniedException("인증 정보를 확인할 수 없습니다.");
                }
                stored = imageRegistrar.register(imageBytes, imageType, image.getOriginalFilename(), uploaderId,
                        ContentImageService.SCOPE_POPUP, null, STORAGE_NAMESPACE);
            } else {
                stored = imageSelector.select(selectedImageId, MAX_FILE_SIZE, LIMITS, "팝업");
            }
        }

        LocalDateTime now = LocalDateTime.now(clock);
        Popup saved = popupRepository.save(Popup.builder()
                .title(title)
                .revision(0)
                .popupType(type)
                .linkUrl(linkUrl)
                .contentHtml(contentHtml)
                .posX(posX)
                .posY(posY)
                .width(width)
                .height(height)
                .displayStart(period.start())
                .displayEnd(period.end())
                .useYn(useYn)
                .createDate(now)
                .updateDate(now)
                .build());
        if (stored != null) {
            refRepository.save(new ContentImageRef(ContentImageService.OWNER_POPUP, saved.getId(), stored.getId()));
        }
        return PopupResponse.detailOf(saved, now, stored == null ? null : MediaImageInfo.from(stored));
    }

    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.POPUP_UPDATE, targetType = "POPUP", targetIdExpression = "id",
            safeErrorMessage = "팝업 수정에 실패했습니다.")
    public PopupResponse updatePopup(Long id, PopupUpdateRequest request) {
        // 종류는 행을 읽어야 알 수 있으므로 종류와 무관한 검증만 잠금 전에 한다. 본문이 있으면(HTML형에서만 의미가 있다) CPU를 쓰는 정리를 잠금 전에 해 둔다.
        String title = requireTitle(request.getTitle());
        int posX = layoutValue(request.getPosX(), 0, PopupLimits.POS_MIN, PopupLimits.POS_MAX, "왼쪽 위치");
        int posY = layoutValue(request.getPosY(), 0, PopupLimits.POS_MIN, PopupLimits.POS_MAX, "위쪽 위치");
        int width = layoutValue(request.getWidth(), 0, PopupLimits.WIDTH_MIN, PopupLimits.WIDTH_MAX, "폭");
        Integer height = requireHeight(request.getHeight());
        Period period = period(request.getDisplayStart(), request.getDisplayEnd());
        String linkUrl = normalizeLink(request.getLinkUrl());
        String content = blankToNull(request.getContent());
        String sanitizedContent = content == null ? null : sanitizeBody(content, request.getContentFormat());

        Popup target = popupRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException(POPUP_NOT_FOUND));
        if (target.getPopupType() == PopupType.IMAGE) {
            requireImageTypeFields(content, height);
        } else {
            requireHtmlTypeFields(linkUrl, false);
            if (sanitizedContent == null) {
                throw new InvalidRequestException("본문은 공백일 수 없습니다.");
            }
        }

        LocalDateTime now = LocalDateTime.now(clock);
        target.replace(title, linkUrl, sanitizedContent, posX, posY, width, height, period.start(), period.end(),
                request.getUseYn(), now);
        return PopupResponse.detailOf(target, now, imageOf(target));
    }

    /**
     * 격리 수준을 READ_COMMITTED로 고정한다 — 이미지 삭제 경로의 참조 잠금 읽기가 REPEATABLE READ에서 비고유 인덱스 갭 잠금으로 다른 이미지의
     * 참조 INSERT(직접 업로드)를 막는 순환을 피한다(PLAN-media-library.md D5·R3-1·R4-2). 최상위 진입점이라 새 트랜잭션에서 적용된다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @AdminActionLogged(actionType = AdminActionTypes.POPUP_DELETE, targetType = "POPUP", targetIdExpression = "id",
            safeErrorMessage = "팝업 삭제에 실패했습니다.")
    public PopupResponse deletePopup(Long id) {
        Popup target = popupRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException(POPUP_NOT_FOUND));
        // 행이 사라진 뒤에는 응답을 만들 수 없으므로 삭제 전에 스냅샷을 만든다
        PopupResponse snapshot = PopupResponse.summaryOf(target, LocalDateTime.now(clock), imageOf(target));

        popupRepository.delete(target);
        // 이미지 행을 먼저 잠그고 자기 참조를 지운 뒤, 직접 업로드 출처이고 다른 참조가 없으면 이미지·파일(커밋 후)까지 정리한다
        if (snapshot.getImageId() != null) {
            imageRemover.detachOwner(ContentImageService.OWNER_POPUP, id, snapshot.getImageId());
        }
        return snapshot;
    }

    /** 관리자 미리보기 — 노출 여부·기간과 무관하게 행의 파일을 읽는다(공개 경로는 비노출을 404로 막으므로 따로 둔다). */
    @Transactional(readOnly = true)
    public PopupImageDownload getImage(Long id) {
        Popup popup = popupRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(POPUP_NOT_FOUND));
        MediaImageInfo image = imageOf(popup);
        if (image == null) {
            throw new ResourceNotFoundException("이미지가 없는 팝업입니다.");
        }
        try {
            byte[] content = image.isRootNamespace()
                    ? fileStorage.load(image.storageKey())
                    : fileStorage.load(image.storageKey(), image.namespace());
            return new PopupImageDownload(image.contentType(), content);
        } catch (StorageFileNotFoundException e) {
            throw new ResourceNotFoundException("팝업 이미지 파일을 찾을 수 없습니다.");
        }
    }

    private MediaImageInfo imageOf(Popup popup) {
        return popup.getPopupType() == PopupType.IMAGE
                ? imageLookup.forOwner(ContentImageService.OWNER_POPUP, popup.getId()).orElse(null)
                : null;
    }

    // ===================== 검증 =====================

    private static void requireImageTypeFields(String content, Integer height) {
        if (content != null) {
            throw new InvalidRequestException("이미지형 팝업에는 본문을 쓸 수 없습니다.");
        }
        if (height != null) {
            throw new InvalidRequestException("이미지형 팝업에는 높이를 지정할 수 없습니다(이미지 비율을 유지합니다).");
        }
    }

    private static void requireHtmlTypeFields(String linkUrl, boolean hasImage) {
        if (linkUrl != null) {
            throw new InvalidRequestException("HTML형 팝업에는 링크를 따로 지정할 수 없습니다. 본문 안에 링크를 넣어주세요.");
        }
        if (hasImage) {
            throw new InvalidRequestException("HTML형 팝업에는 이미지를 올릴 수 없습니다.");
        }
    }

    private static String sanitizeBody(String content, String contentFormat) {
        SanitizedHtml body = ContentBodyPolicy.sanitizePopup(content, contentFormat);
        return body.html();
    }

    private static String requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new InvalidRequestException("제목은 공백일 수 없습니다.");
        }
        return title.strip();
    }

    /** 폭·위치 — 등록 폼은 비운 값에 기본값을 쓰고({@code fallback > 0}), 수정은 필수라 호출 전에 {@code @NotNull}이 막는다. */
    private static int layoutValue(Integer value, int fallback, int min, int max, String label) {
        int resolved = value == null ? fallback : value;
        if (resolved < min || resolved > max) {
            throw new InvalidRequestException(label + "은(는) " + min + "~" + max + "px 사이여야 합니다.");
        }
        return resolved;
    }

    private static Integer requireHeight(Integer height) {
        if (height != null && (height < PopupLimits.HEIGHT_MIN || height > PopupLimits.HEIGHT_MAX)) {
            throw new InvalidRequestException("높이는 " + PopupLimits.HEIGHT_MIN + "~" + PopupLimits.HEIGHT_MAX + "px 사이여야 합니다.");
        }
        return height;
    }

    /**
     * 노출 기간 입력 검증(PLAN-popup.md R1-5): 엄격 파싱 → 범위(2000~2099) → 분 절단 → {@code start < end}. 문자열 {@code null}은 그쪽 열림이다.
     */
    private static Period period(String rawStart, String rawEnd) {
        LocalDateTime parsedStart = DisplayPeriod.parseKstLocal(rawStart);
        LocalDateTime parsedEnd = DisplayPeriod.parseKstLocal(rawEnd);
        DisplayPeriod.requireInRange(parsedStart);
        DisplayPeriod.requireInRange(parsedEnd);
        LocalDateTime start = DisplayPeriod.normalize(parsedStart);
        LocalDateTime end = DisplayPeriod.normalize(parsedEnd);
        DisplayPeriod.requireValid(start, end);
        return new Period(start, end);
    }

    private record Period(LocalDateTime start, LocalDateTime end) {
    }

    private static byte[] readImage(MultipartFile image) {
        if (image.getSize() > MAX_FILE_SIZE) {
            throw new InvalidRequestException("팝업 이미지는 2MB 이하만 업로드할 수 있습니다.");
        }
        try {
            return image.getBytes();
        } catch (IOException e) {
            throw new InvalidRequestException("파일을 읽을 수 없습니다.");
        }
    }

    /** 빈 문자열·공백은 없음(null)이다. 등록 폼의 빈 입력란과 수정의 링크·본문에 쓴다. */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** 형식 검증은 요청 DTO의 {@code @SafeLinkUrl}이 이미 했다. */
    private static String normalizeLink(String linkUrl) {
        return linkUrl == null || linkUrl.isBlank() ? null : linkUrl.strip();
    }
}
