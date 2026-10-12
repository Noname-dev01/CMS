package com.cms.admin.popup.dto.request;

import com.cms.admin.member.dto.request.validation.MaxUtf8Bytes;
import com.cms.admin.popup.domain.PopupType;
import com.cms.common.html.ContentBodyPolicy;
import com.cms.common.web.validation.SafeLinkUrl;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.web.multipart.MultipartFile;

/**
 * 팝업 등록 요청 — {@code multipart/form-data}({@code @ModelAttribute} 바인딩, 이미지 파일은 서비스가 검증한다). 폼의 빈 입력란은 "없음"으로
 * 본다(수정 JSON과 계약이 다르다 — PLAN-popup.md R2-3). 노출 기간은 {@code String}으로 받아 {@code DisplayPeriod.parseKstLocal}이 엄격하게
 * 읽는다(R1-5). 종류별 필수·금지 필드는 서비스가 400으로 강제한다.
 */
@Getter
@Setter
@NoArgsConstructor
@Schema(description = "팝업 등록 요청 (multipart/form-data)")
public class PopupCreateRequest {

    @NotBlank
    @Size(max = 200)
    @Schema(description = "제목(관리용 이름이자 팝업의 접근성 이름)", example = "추석 연휴 안내")
    private String title;

    @NotNull
    @Schema(description = "종류. IMAGE(이미지 한 장) 또는 HTML(편집기 본문). 생성 후 바꿀 수 없다", example = "IMAGE")
    private PopupType popupType;

    @Size(max = 500)
    @SafeLinkUrl
    @Schema(description = "이미지형만 — 클릭 시 이동할 링크(/로 시작하는 경로 또는 http(s):// 주소). HTML형은 본문 안의 링크를 쓴다")
    private String linkUrl;

    @Schema(description = "이미지형만 — 팝업 이미지(png·jpeg·gif, 2MB 이하, 한 변 2560px 이하, 애니메이션 불가)")
    private MultipartFile image;

    @Schema(description = "이미지형만 — 미디어 라이브러리에서 고른 이미지 ID(라이브러리 읽기 권한 필요, 2MB 이하만). image와 둘 중 하나만 보낸다", example = "12")
    private Long imageId;

    // 원문 상한은 sanitizer가 원문을 파싱하기 전에 거른다(PLAN-popup.md 쟁점 5)
    @MaxUtf8Bytes(value = ContentBodyPolicy.POPUP_MAX_RAW_BYTES, message = "본문이 너무 깁니다.")
    @Schema(description = "HTML형만 — 본문 HTML(편집기 출력). 이미지는 지원하지 않는다. 보이는 글자 2,000자 이하")
    private String content;

    @Schema(description = "본문 형식 표식. HTML형이면 \"HTML\" 필수", example = "HTML")
    private String contentFormat;

    @Schema(description = "왼쪽 위치(px, 0~2000). 비우면 100", example = "100")
    private Integer posX;

    @Schema(description = "위쪽 위치(px, 0~2000). 비우면 100", example = "100")
    private Integer posY;

    @Schema(description = "폭(px, 200~1000). 비우면 400", example = "400")
    private Integer width;

    @Schema(description = "HTML형만 — 높이(px, 144~1000). 비우면 내용 높이에 맞춘다(넘치면 내부 스크롤)")
    private Integer height;

    @Schema(description = "노출 시작 시각(포함, 한국 시각, 분 단위로 절단). yyyy-MM-ddTHH:mm, 오프셋 불가. 비우면 시작 제한 없음", example = "2026-10-10T09:00")
    private String displayStart;

    @Schema(description = "노출 종료 시각(미포함). 비우면 종료 제한 없음", example = "2026-10-20T00:00")
    private String displayEnd;

    @Schema(description = "노출 여부. 누락 시 true", example = "true")
    private Boolean useYn;
}
