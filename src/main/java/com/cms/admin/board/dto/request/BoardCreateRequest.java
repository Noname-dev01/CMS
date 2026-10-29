package com.cms.admin.board.dto.request;

import com.cms.admin.site.SiteScope;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "게시판 생성 요청")
public class BoardCreateRequest {

    @NotBlank
    @Size(max = 100)
    @Schema(description = "게시판 이름(중복 허용 — 화면은 #id를 함께 보인다)", example = "자료실")
    private String name;

    @NotNull
    @Schema(description = "공개 게시판 여부 — true면 공개 사이트 /boards/{id}에 노출(PR B)", example = "true")
    private Boolean publicYn;

    @NotNull
    @Schema(description = "첨부 허용 여부 — false면 새 첨부 업로드만 막는다(PR B)", example = "true")
    private Boolean attachmentYn;

    @Pattern(regexp = SiteScope.PATTERN)
    @Schema(description = "표시 대상 — \"COMMON\"(모든 공개 사이트 공통) 또는 사이트 ID 문자열. 누락 = 공통", example = "COMMON")
    private String siteScope;

    @Schema(description = "true면 siteScope 사이트의 공지 게시판으로 만든다(공개 필수·사이트당 하나, 공통은 불가). 누락 = false", example = "false")
    private Boolean noticeBoard;

    @Schema(description = "관리 화면 표시 순서(0..9999, 작을수록 먼저, 같으면 id 순). 누락 = 0", example = "0")
    private Integer sortOrder;

    @Schema(description = "공개 주소 슬러그(소문자·숫자·하이픈 2~50자, 숫자만·board-{숫자} 불가). 누락·공백 = 기본 슬러그 board-{id}(나중에 한 번 바꿀 수 있다)",
            example = "press")
    private String slug;

    // 표시 설정은 모두 선택이다(누락 = 현행 동작). 길이 상한은 앞뒤 공백을 정리한 뒤 서비스가 검사한다 — 정리 전 길이로 막지 않게 @Size를 두지 않는다.
    @Schema(description = "공개 목록 머리의 설명(평문, 정리 후 500자 이하). 누락·공백 = 없음", example = "보도자료를 올립니다.")
    private String description;

    @Schema(description = "공개 목록 한 페이지 글 수 — 10·20·30·50. 누락 = 10", example = "10")
    private Integer listPageSize;

    @Schema(description = "공개 화면의 작성자 표시명(정리 후 50자 이하). 누락·공백 = 표시 안 함", example = "홍보팀")
    private String authorLabel;

    @Schema(description = "공개 화면에 조회수 표시 여부. 누락 = false", example = "false")
    private Boolean showViewCount;

    @Schema(description = "새 글 배지 기간(일, 0..30, 0 = 끔). 누락 = 0", example = "3")
    private Integer newBadgeDays;

    @Schema(description = "쓰기 허용 — false면 쓰기 중지(보관: 새 글·수정·첨부·본문 이미지 업로드 차단). 누락 = true", example = "true")
    private Boolean writableYn;

    @Schema(description = "공개 목록 1페이지 상단에 고정할 수 있는 글 수(0..5, 0 = 고정 끔). 누락 = 0", example = "0")
    private Integer pinMaxCount;

    // 문자열로 받아 서비스가 정확히 일치할 때만 받는다(Jackson이 ""를 null로 읽는 함정을 피한다 — 팝업 종류·대표 이미지와 같은 방식)
    @Schema(description = "공개 목록의 모양 — LIST(목록형)·GALLERY(갤러리형)·WEBZINE(웹진형), 대소문자 정확히. 누락 = LIST", example = "LIST")
    private String boardType;

    // 작성 정책도 모두 선택이다(누락 = 현행 동작). 범위·정규화는 서비스 한 곳이 한다. 원소 개수는 정리 전후가 같아 여기서 막는다.
    @Schema(description = "게시글당 첨부 개수 상한(1..5). 누락 = 5", example = "5")
    private Integer attachMaxCount;

    @Schema(description = "첨부 파일당 크기 상한(MB, 1..10). 누락 = 10", example = "10")
    private Integer attachMaxSizeMb;

    @Size(max = 50)
    @Schema(description = "허용 확장자(전역 허용 목록의 일부). 누락·전체 선택 = 전체 허용", example = "[\"pdf\", \"docx\"]")
    private List<String> attachExtensions;

    @Schema(description = "본문 이미지 허용 여부. 누락 = true", example = "true")
    private Boolean bodyImageYn;

    @Schema(description = "예약 게시 허용 여부. 누락 = true", example = "true")
    private Boolean publishPeriodYn;
}
