package com.cms.admin.member.service;

import com.cms.common.exception.InvalidRequestException;
import com.cms.common.image.ImageFileValidator;

import java.util.Set;

/**
 * 프로필 이미지 업로드(AdminMemberService)·마이그레이션(ProfileImageMigrationRunner) 양쪽이
 * 공유하는 검증 로직. 실제 검사는 공용 {@link ImageFileValidator}가 하고, 여기서는 프로필 전용 상한
 * (아바타 용도 — 한 변 2000px·총 200만 픽셀, 전체 디코드)만 정한다(PLAN-html-editor.md 쟁점 8).
 *
 * <p>위반 시 항상 {@link InvalidRequestException}을 던진다 — 업로드 경로는 이를 그대로
 * 400으로 매핑하고, 마이그레이션 러너는 행 단위 catch(Exception)으로 흡수해 스킵한다
 * (adversarial-review/plan/PLAN-profile-image-storage.md 쟁점 3).
 */
public final class ProfileImageValidator {

    /** WebP는 JDK 표준 ImageIO가 지원하지 않아 제외한다(사용자 결정 — 신규 의존성 추가 대신). */
    public static final Set<String> ALLOWED_CONTENT_TYPES = ImageFileValidator.ALLOWED_CONTENT_TYPES;

    /** 아바타 용도로 충분히 선명하면서 힙 부담이 작은 상한. 전체 디코드까지 해서 손상 이미지도 거부한다. */
    private static final ImageFileValidator.Limits LIMITS = new ImageFileValidator.Limits(2000, 2_000_000L, true);

    private ProfileImageValidator() {
    }

    public static void validate(byte[] content, String declaredContentType) {
        ImageFileValidator.validate(content, declaredContentType, LIMITS);
    }
}
