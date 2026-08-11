package com.cms.admin.member.service;

import com.cms.common.exception.InvalidRequestException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/**
 * 프로필 이미지 업로드(AdminMemberService)·마이그레이션(ProfileImageMigrationRunner) 양쪽이
 * 공유하는 검증 로직. JDK 표준 {@code javax.imageio}만 사용한다(신규 의존성 없음 —
 * PLAN-notice-attachment.md의 "매직바이트 문자열 검사 도입 안 함" 방침과 상충하지 않음,
 * 이건 매직바이트 시그니처 스캔이 아니라 JDK 표준 디코더의 정상 API 사용이다).
 *
 * <p>위반 시 항상 {@link InvalidRequestException}을 던진다 — 업로드 경로는 이를 그대로
 * 400으로 매핑하고, 마이그레이션 러너는 행 단위 catch(Exception)으로 흡수해 스킵한다
 * (adversarial-review/plan/PLAN-profile-image-storage.md 쟁점 3).
 */
public final class ProfileImageValidator {

    /** WebP는 JDK 표준 ImageIO가 지원하지 않아 제외한다(사용자 결정 — 신규 의존성 추가 대신). */
    public static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/png", "image/jpeg", "image/gif");

    /** 아바타 용도로 충분히 선명하면서 힙 부담이 작은 상한. */
    private static final int MAX_DIMENSION = 2000;
    private static final long MAX_TOTAL_PIXELS = 2_000_000L;

    private ProfileImageValidator() {
    }

    /**
     * 화이트리스트·헤더 우선 크기·애니메이션·포맷-MIME 일치를 검증한다. 모든 검사를 통과한
     * 뒤에만(즉 마지막 단계에서만) 실제 픽셀 디코딩을 수행해 decompression bomb을 방어한다
     * (Oracle {@code ImageReader} API 계약 — {@code getWidth(0)/getHeight(0)}은 헤더만 읽는다).
     */
    public static void validate(byte[] content, String declaredContentType) {
        if (declaredContentType == null || !ALLOWED_CONTENT_TYPES.contains(declaredContentType)) {
            throw new InvalidRequestException("이미지 파일만 업로드할 수 있습니다.");
        }

        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            if (iis == null) {
                throw new InvalidRequestException("이미지를 해석할 수 없습니다.");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                throw new InvalidRequestException("이미지를 해석할 수 없습니다.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                long pixels = (long) width * height;
                if (width > MAX_DIMENSION || height > MAX_DIMENSION || pixels > MAX_TOTAL_PIXELS) {
                    throw new InvalidRequestException("이미지 크기가 너무 큽니다.");
                }
                // allowSearch=false는 GIF 등 스트림 검색이 필요한 포맷에서 -1을 반환할 수 있어
                // 애니메이션 검사가 우회된다(JDK ImageReader 계약) — true로 검색해 정확히 1일
                // 때만 통과시킨다. 2MB 크기 상한이 이미 있어 검색 비용은 감내할 만하다.
                if (reader.getNumImages(true) != 1) {
                    throw new InvalidRequestException("애니메이션 이미지는 지원하지 않습니다.");
                }
                String actualMime = canonicalMimeFor(reader.getFormatName());
                if (!declaredContentType.equals(actualMime)) {
                    throw new InvalidRequestException("파일 형식과 실제 이미지가 일치하지 않습니다.");
                }
                reader.read(0); // 위 검사를 모두 통과한 뒤에만 실제 픽셀 디코딩
            } catch (IOException e) {
                throw new InvalidRequestException("이미지를 해석할 수 없습니다.");
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new InvalidRequestException("이미지를 해석할 수 없습니다.");
        }
    }

    private static String canonicalMimeFor(String formatName) {
        if (formatName == null) {
            return null;
        }
        return switch (formatName.toUpperCase(Locale.ROOT)) {
            case "PNG" -> "image/png";
            case "JPEG", "JPG" -> "image/jpeg";
            case "GIF" -> "image/gif";
            default -> null;
        };
    }
}
