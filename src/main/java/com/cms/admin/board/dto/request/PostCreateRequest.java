package com.cms.admin.board.dto.request;

import com.cms.admin.member.dto.request.validation.MaxUtf8Bytes;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Schema(description = "게시글 생성 요청")
public class PostCreateRequest {

    @NotBlank
    @Size(max = 200)
    @Schema(description = "제목", example = "2026년 상반기 자료집")
    private String title;

    @NotBlank
    @MaxUtf8Bytes(value = 200_000, message = "본문이 너무 깁니다.")
    @Schema(description = "본문 HTML(편집기 출력). 서버가 허용 목록으로 정리한 뒤 저장한다 — 보이는 글자 10,000자 이하",
            example = "<p>자료집을 공유합니다.</p>")
    private String content;

    @Schema(description = "본문 형식 표식. \"HTML\" 필수 — 배포 전에 열어 둔 평문 편집 화면의 저장을 막는다", example = "HTML")
    private String contentFormat;

    @Schema(description = "노출 여부. 누락 시 true로 기본화", example = "true")
    private Boolean useYn;

    @Schema(description = "공개 목록 1페이지 상단 고정 여부. 누락 = false. 게시판의 상단 고정 상한이 0이면 true는 400, 상한을 넘으면 409", example = "false")
    private Boolean pinned;

    @Valid
    @Schema(description = "노출 기간(예약 게시). 없으면 기간 제한 없음. useYn과 기간을 둘 다 만족해야 공개된다")
    private PostPublishPeriodRequest publishPeriod;

    @Setter(lombok.AccessLevel.NONE)
    @Schema(description = "대표 이미지 ID — 이 글 본문에 들어 있는 이미지 중 하나. 누락·null = 대표 없음. 본문에 없는 이미지는 400, "
            + "만들 수 없는 이미지(손상·CMYK 등)는 400", example = "12", nullable = true)
    private Long coverImageId;

    /**
     * 세터 호출 여부 — 같은 키가 두 번 오면 뒤 객체가 앞 객체를 덮어써 잘못된 값이 기간 비움으로 바뀌므로 두 번째 호출을 거부한다.
     * JSON 속성이면 안 된다: getter가 보이면 Jackson이 비공개 필드를 mutator로 추론해 {"publishPeriodSet":false}로 플래그를 되돌려
     * 우회할 수 있다(코드 리뷰 R1) — getter·setter를 없애고 {@code @JsonIgnore}로 제외한다.
     */
    @JsonIgnore
    @Getter(lombok.AccessLevel.NONE)
    @Setter(lombok.AccessLevel.NONE)
    @Schema(hidden = true)
    private boolean publishPeriodSet;

    /** {@link #publishPeriodSet}와 같은 이유(JSON 속성이면 안 됨)로 {@code coverImageId}의 중복 키를 거부하는 플래그. */
    @JsonIgnore
    @Getter(lombok.AccessLevel.NONE)
    @Setter(lombok.AccessLevel.NONE)
    @Schema(hidden = true)
    private boolean coverImageIdSet;

    /** {@code DISABLED} 필수 — creator로 인식되면 같은 키가 중복될 때 마지막 값이 이겨 {@code publishPeriod} 세터의 중복 거부가 동작하지 않는다. */
    @JsonCreator(mode = JsonCreator.Mode.DISABLED)
    @Builder
    public PostCreateRequest(String title, String content, String contentFormat, Boolean useYn, Boolean pinned,
                             PostPublishPeriodRequest publishPeriod, Long coverImageId) {
        this.title = title;
        this.content = content;
        this.contentFormat = contentFormat;
        this.useYn = useYn;
        this.pinned = pinned;
        this.publishPeriod = publishPeriod;
        this.coverImageId = coverImageId;
    }

    /** {@code Object}로 받아 엄격 파싱한다 — {@link CoverImageIdInput}. 같은 키가 두 번 오면 거부. */
    @JsonProperty("coverImageId")
    public void setCoverImageId(Object coverImageId) {
        if (coverImageIdSet) {
            throw new IllegalArgumentException("coverImageId가 중복되었습니다.");
        }
        coverImageIdSet = true;
        this.coverImageId = CoverImageIdInput.parse(coverImageId);
    }

    @JsonProperty("publishPeriod")
    public void setPublishPeriod(PostPublishPeriodRequest publishPeriod) {
        if (publishPeriodSet) {
            throw new IllegalArgumentException("publishPeriod가 중복되었습니다.");
        }
        publishPeriodSet = true;
        this.publishPeriod = publishPeriod;
    }
}
