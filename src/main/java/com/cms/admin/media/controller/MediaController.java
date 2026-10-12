package com.cms.admin.media.controller;

import com.cms.admin.media.dto.MediaDetailResponse;
import com.cms.admin.media.dto.MediaPageResponse;
import com.cms.admin.media.dto.MediaResponse;
import com.cms.admin.media.service.MediaService;
import com.cms.admin.permission.AdminFeature;
import com.cms.admin.permission.PermissionAction;
import com.cms.admin.permission.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * 미디어 라이브러리 API. 경로가 {@code MEDIA} URL 게이트({@code /admin/api/media/**}) 안이라 게이트는 READ까지만 보고, 동작별 판정은 핸들러의
 * {@link RequirePermission}이 한다(PLAN-media-library.md D6). 동작 분류: 목록·상세·파일 보기 = READ, 업로드 = CREATE, 삭제 = DELETE.
 * 출처별 가시성(게시판 본문 이미지는 ADMIN만)은 서비스가 판정한다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/api/media")
@Tag(name = "Admin Media", description = "미디어 라이브러리 API (기능 단위 위임)")
public class MediaController {

    private final MediaService mediaService;

    @Operation(summary = "라이브러리 목록 조회",
            description = "keyword(원본 파일명 부분 일치·숫자면 ID 일치)·origin(ALL·LIBRARY·BANNER·POPUP·BOARD)·unused(참조 없는 이미지만) 필터. "
                    + "게시판 본문 이미지(BOARD)는 ADMIN에게만 보인다. selectable=true면 배너·팝업·본문에서 고를 수 있는 출처(LIBRARY·BANNER·POPUP)만. "
                    + "응답에 전체 용량 현황이 함께 담긴다")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "400", description = "지원하지 않는 origin 또는 200자를 넘는 keyword")
    @ApiResponse(responseCode = "403", description = "라이브러리 조회 권한 없음")
    @GetMapping
    @RequirePermission(feature = AdminFeature.MEDIA, action = PermissionAction.READ)
    public ResponseEntity<MediaPageResponse> list(
            @ParameterObject @PageableDefault(size = 24) Pageable pageable,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false, defaultValue = "ALL") String origin,
            @RequestParam(required = false, defaultValue = "false") boolean unused,
            @RequestParam(required = false, defaultValue = "false") boolean selectable) {
        return ResponseEntity.ok(mediaService.list(keyword, origin, unused, selectable, pageable));
    }

    @Operation(summary = "라이브러리 항목 상세", description = "사용처 목록 포함. 게시글 라벨은 그 게시판 조회 권한이 있을 때만 제목을 보여 준다")
    @ApiResponse(responseCode = "404", description = "이미지 없음(호출자가 볼 수 없는 출처 포함)")
    @GetMapping("/{id}")
    @RequirePermission(feature = AdminFeature.MEDIA, action = PermissionAction.READ)
    public ResponseEntity<MediaDetailResponse> getDetail(@PathVariable Long id) {
        return ResponseEntity.ok(mediaService.getDetail(id));
    }

    @Operation(summary = "이미지 파일 미리보기", description = "관리자 전용 — 공개 경로를 관리 용도로 넓히지 않기 위한 별도 엔드포인트")
    @ApiResponse(responseCode = "404", description = "이미지 또는 파일 없음(호출자가 볼 수 없는 출처 포함)")
    @GetMapping("/{id}/file")
    @RequirePermission(feature = AdminFeature.MEDIA, action = PermissionAction.READ)
    public ResponseEntity<byte[]> getFile(@PathVariable Long id) {
        MediaService.ImageFile file = mediaService.getFile(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .body(file.content());
    }

    @Operation(summary = "이미지 업로드", description = "multipart/form-data의 file. png·jpeg·gif, 5MB 이하, 한 변 4096px 이하, 애니메이션 불가. 전체 용량·개수 상한이 있다")
    @ApiResponse(responseCode = "201", description = "업로드 성공")
    @ApiResponse(responseCode = "400", description = "이미지 형식·크기·애니메이션 검증 실패")
    @ApiResponse(responseCode = "403", description = "라이브러리 업로드 권한 없음")
    @ApiResponse(responseCode = "409", description = "전체 용량·개수 상한 도달")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequirePermission(feature = AdminFeature.MEDIA, action = PermissionAction.CREATE)
    public ResponseEntity<MediaResponse> upload(@RequestPart("file") MultipartFile file) {
        MediaResponse response = mediaService.upload(file);
        return ResponseEntity.created(
                ServletUriComponentsBuilder.fromCurrentRequest()
                        .path("/{id}")
                        .buildAndExpand(response.id())
                        .toUri()
        ).body(response);
    }

    @Operation(summary = "이미지 삭제", description = "사용 중이 아닌 이미지만. 행·카운터를 정리하고 커밋 후 파일도 지운다(복구 불가)")
    @ApiResponse(responseCode = "204", description = "삭제 성공")
    @ApiResponse(responseCode = "404", description = "이미지 없음(호출자가 볼 수 없는 출처 포함)")
    @ApiResponse(responseCode = "409", description = "사용 중인 이미지 — 메시지에 사용처 요약")
    @DeleteMapping("/{id}")
    @RequirePermission(feature = AdminFeature.MEDIA, action = PermissionAction.DELETE)
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        mediaService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
