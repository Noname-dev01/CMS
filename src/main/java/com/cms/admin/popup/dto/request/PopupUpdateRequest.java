package com.cms.admin.popup.dto.request;

import com.cms.admin.member.dto.request.validation.MaxUtf8Bytes;
import com.cms.common.html.ContentBodyPolicy;
import com.cms.common.web.validation.SafeLinkUrl;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import com.cms.admin.site.SiteScope;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 팝업 <b>전체 교체</b> 요청(PUT). 종류와 이미지는 바뀌지 않는다. 해제 계약은 필드별로 다르다(PLAN-popup.md R2-3): 링크는 {@code null}·빈
 * 문자열 모두 해제, 노출 시작·종료와 높이는 {@code null}만 해제이고 빈 문자열은 400이다 — 기간을 잘못 읽으면 팝업이 의도보다 오래 노출된다.
 * Jackson은 {@code Integer}를 읽을 때 {@code ""}·공백을 {@code null}로 바꾸므로 높이는 세터가 원시 값을 받아 직접 검사한다(R3-2, 전역
 * {@code ObjectMapper} 설정은 다른 API에 영향을 주므로 바꾸지 않는다). 모든 인자 생성자를 두지 않는 것도 같은 이유다 — Jackson 3은 매개변수
 * 이름으로 생성자를 creator로 인식해 세터를 우회한다.
 */
@Getter
@Setter
@NoArgsConstructor
@Schema(description = "팝업 수정 요청 (전체 교체)")
public class PopupUpdateRequest {

    @NotBlank
    @Size(max = 200)
    @Schema(description = "제목", example = "추석 연휴 안내")
    private String title;

    @Size(max = 500)
    @SafeLinkUrl
    @Schema(description = "이미지형만 — 링크. null/빈 값이면 링크 해제. HTML형은 비워야 한다")
    private String linkUrl;

    @MaxUtf8Bytes(value = ContentBodyPolicy.POPUP_MAX_RAW_BYTES, message = "본문이 너무 깁니다.")
    @Schema(description = "HTML형만 — 본문 HTML. 이미지형은 비워야 한다")
    private String content;

    @Schema(description = "본문 형식 표식. HTML형이면 \"HTML\" 필수", example = "HTML")
    private String contentFormat;

    @NotNull
    @Schema(description = "왼쪽 위치(px, 0~2000)", example = "100")
    private Integer posX;

    @NotNull
    @Schema(description = "위쪽 위치(px, 0~2000)", example = "100")
    private Integer posY;

    @NotNull
    @Schema(description = "폭(px, 200~1000)", example = "400")
    private Integer width;

    @Setter(AccessLevel.NONE)
    @Schema(description = "HTML형만 — 높이(px, 144~1000). null이면 내용 높이에 맞춘다. 빈 문자열은 400", nullable = true)
    private Integer height;

    @Schema(description = "노출 시작 시각(포함, 한국 시각). yyyy-MM-ddTHH:mm, 오프셋 불가. null이면 시작 제한 해제, 빈 문자열은 400", nullable = true,
            example = "2026-10-10T09:00")
    private String displayStart;

    @Schema(description = "노출 종료 시각(미포함). null이면 종료 제한 해제, 빈 문자열은 400", nullable = true, example = "2026-10-20T00:00")
    private String displayEnd;

    @NotNull
    @Schema(description = "노출 여부", example = "true")
    private Boolean useYn;

    @NotNull
    @Pattern(regexp = SiteScope.PATTERN, message = "표시 대상은 COMMON 또는 사이트 ID여야 합니다.")
    @Schema(description = "표시 대상 — COMMON(모든 공개 사이트 공통) 또는 공개 사이트 ID. 전체 교체라 필수(누락이 공통 공개가 되지 않게 400)", example = "COMMON")
    private String siteScope;

    @NotNull
    @PositiveOrZero
    @Schema(description = "편집을 시작할 때 응답으로 받은 revision(시작값 0). 다르면 409(낡은 화면)", example = "0")
    private Integer revision;

    /** {@code null}과 정수만 받는다. 문자열(빈 문자열·공백 포함)·소수는 읽기 오류(400)다. */
    @JsonProperty("height")
    public void setHeight(Object raw) {
        if (raw == null) {
            this.height = null;
        } else if (raw instanceof Integer value) {
            this.height = value;
        } else {
            throw new IllegalArgumentException("height는 정수 또는 null이어야 합니다.");
        }
    }
}
