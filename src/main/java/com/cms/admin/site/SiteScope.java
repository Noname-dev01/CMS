package com.cms.admin.site;

import com.cms.common.exception.InvalidRequestException;

/**
 * 콘텐츠(배너·팝업·FAQ 카테고리·정적 페이지)의 표시 대상 요청 값(PLAN-site-menu.md PR ③ S6). 문자열 {@code "COMMON"}(모든 공개 사이트 공통)
 * 또는 양의 정수 문자열(사이트 ID)이다. 형식은 DTO의 {@code @Pattern(regexp = SiteScope.PATTERN)}이 거르고 이 클래스가 한 번 더 파싱한다 —
 * 정규식은 {@code Long} 범위를 넘는 19자리 값을 통과시키므로 범위 초과는 여기서 400({@link InvalidRequestException})으로 바꾼다(500 방지).
 * 공개 사이트(PUBLIC)인지·존재하는지는 {@code ContentSiteScope}가 사이트 행을 잠그고 판정한다.
 */
public final class SiteScope {

    public static final String COMMON = "COMMON";

    /** DTO 형식 검사용 정규식. */
    public static final String PATTERN = "COMMON|[1-9][0-9]{0,18}";

    private SiteScope() {
    }

    /** 수정(PUT) 요청용 — 누락은 400이다(전체 교체에서 누락이 조용히 공통 공개가 되지 않게, S6). */
    public static Long parseRequired(String raw) {
        if (raw == null) {
            throw new InvalidRequestException("표시 대상(siteScope)이 필요합니다.");
        }
        return parse(raw);
    }

    /** 수정(PUT) 요청의 낡은 화면 검사 값 — 누락은 400이다(S6b). */
    public static int requireVersion(Integer raw, int min) {
        if (raw == null || raw < min) {
            throw new InvalidRequestException("version이 올바르지 않습니다.");
        }
        return raw;
    }

    /**
     * 요청 문자열을 사이트 ID로 바꾼다. {@code COMMON}은 {@code null}(공통)이다. 호출자는 "누락"과 "COMMON"을 먼저 구분해야 한다 —
     * 등록 요청의 누락은 공통이지만 수정 요청의 누락은 DTO 검증이 400으로 거른다.
     */
    public static Long parse(String raw) {
        if (raw == null || COMMON.equals(raw)) {
            return null;
        }
        try {
            long id = Long.parseLong(raw);
            if (id < 1) {
                throw new InvalidRequestException("올바르지 않은 표시 대상입니다.");
            }
            return id;
        } catch (NumberFormatException e) {
            throw new InvalidRequestException("올바르지 않은 표시 대상입니다.");
        }
    }
}
