package com.cms.admin.board.dto.request;

import com.cms.admin.member.dto.request.validation.MaxUtf8Bytes;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 부분 수정 요청. null 필드는 기존값 유지 시맨틱이다. 값이 채워진 title/content의 공백 거부, 전 필드가 null인 경우의 거부는
 * 서비스 계층에서 처리한다({@code Post.update()}는 도메인 규칙만 담당). {@code publishPeriod}는 객체가 있을 때만 시작·종료를 통째로
 * 교체한다(없으면 유지) — {@link PostPublishPeriodRequest}.
 */
@Getter
@Setter
@NoArgsConstructor
@Schema(description = "게시글 부분 수정 요청")
public class PostUpdateRequest {

    @Size(max = 200)
    @Schema(description = "제목 (null이면 기존값 유지)", example = "2026년 상반기 자료집(수정)")
    private String title;

    @MaxUtf8Bytes(value = 200_000, message = "본문이 너무 깁니다.")
    @Schema(description = "본문 HTML (null이면 기존값 유지)")
    private String content;

    @Schema(description = "본문 형식 표식. content가 있으면 \"HTML\" 필수", example = "HTML")
    private String contentFormat;

    @Schema(description = "노출 여부 (null이면 기존값 유지)", example = "false")
    private Boolean useYn;

    @Schema(description = "공개 목록 1페이지 상단 고정 여부 (null이면 기존값 유지). 새로 고정하면 게시판 상한이 0이면 400, 상한을 넘으면 409. 해제(false)는 언제나 가능", example = "true")
    private Boolean pinned;

    @Valid
    @Schema(description = "노출 기간(예약 게시). 없으면(키 없음·null) 기존 기간 유지, 있으면 시작·종료를 통째로 교체({}면 제한 없음으로 비움)")
    private PostPublishPeriodRequest publishPeriod;

    @Setter(lombok.AccessLevel.NONE)
    @Schema(description = "대표 이미지 ID — 이 글 본문에 들어 있는 이미지 중 하나. 키 없음 = 유지, null = 해제, 정수 = 지정. "
            + "본문을 바꾸면서 키를 주지 않았는데 현재 대표가 새 본문에 없으면 서버가 해제한다", example = "12", nullable = true)
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

    /**
     * {@code coverImageId} 키가 요청에 있었는지(= 명시 {@code null} 해제와 "키 없음 = 유지"를 가른다)와 중복 키 거부를 겸하는 플래그.
     * {@link #publishPeriodSet}와 같은 이유로 JSON 속성이면 안 된다.
     */
    @JsonIgnore
    @Getter(lombok.AccessLevel.NONE)
    @Setter(lombok.AccessLevel.NONE)
    @Schema(hidden = true)
    private boolean coverImageIdSet;

    /** {@code DISABLED} 필수 — creator로 인식되면 같은 키가 중복될 때 마지막 값이 이겨 {@code publishPeriod} 세터의 중복 거부가 동작하지 않는다. */
    @JsonCreator(mode = JsonCreator.Mode.DISABLED)
    @Builder
    public PostUpdateRequest(String title, String content, String contentFormat, Boolean useYn, Boolean pinned,
                             PostPublishPeriodRequest publishPeriod, Long coverImageId) {
        this.title = title;
        this.content = content;
        this.contentFormat = contentFormat;
        this.useYn = useYn;
        this.pinned = pinned;
        this.publishPeriod = publishPeriod;
        this.coverImageId = coverImageId;
        // 빌더로 만든 요청은 값이 있을 때만 "키 있음"이다 — 해제(명시 null)가 필요한 시험은 clearCoverImage()를 쓴다
        this.coverImageIdSet = coverImageId != null;
    }

    /** {@code coverImageId} 키가 요청에 있었는지. false면 대표 이미지를 유지한다(본문 변경에 따른 자동 해제만 서버가 판단). */
    public boolean hasCoverImageId() {
        return coverImageIdSet;
    }

    /** 대표 이미지 해제(명시 {@code null})를 담은 요청으로 만든다 — 빌더로는 표현할 수 없는 "키 있음 + null"용. */
    public PostUpdateRequest clearCoverImage() {
        this.coverImageId = null;
        this.coverImageIdSet = true;
        return this;
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
