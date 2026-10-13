package com.cms.admin.menu.dto.request;

import com.cms.admin.menu.dto.request.validation.SafeMenuUrl;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "메뉴 생성 요청")
public class MenuCreateRequest {

    @NotBlank
    @Size(max = 100)
    @Schema(description = "메뉴명", example = "회원 관리")
    private String menuName;

    @NotNull
    @Schema(description = "소속 사이트 ID(필수). 부모 메뉴도 같은 사이트여야 한다", example = "1")
    private Long siteId;

    @Size(max = 255)
    @SafeMenuUrl
    @Schema(description = "메뉴 URL. 형식은 /로 시작하는 내부 경로 또는 http(s) 주소이며, 외부 주소 허용 여부와 비공개 경로 금지는 소속 사이트 정책이 판정한다"
            + "(관리자 사이트는 내부 경로만). 빈 문자열은 null로 저장", example = "/admin/member/manage")
    private String menuUrl;

    @Size(max = 100)
    @Schema(description = "아이콘 클래스명", example = "fas fa-users")
    private String menuIcon;

    @Size(max = 500)
    @Schema(description = "메뉴 설명", example = "회원 목록 조회 및 관리")
    private String menuDesc;

    @Schema(description = "사용 여부. 누락 시 true로 기본화", example = "true")
    private Boolean useYn;


    @Schema(description = "부모 메뉴 번호. 최상위 메뉴는 null", example = "1")
    private Long upMenuNo;
}
