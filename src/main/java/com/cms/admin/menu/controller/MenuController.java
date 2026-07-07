package com.cms.admin.menu.controller;

import com.cms.admin.menu.dto.request.MenuCreateRequest;
import com.cms.admin.menu.dto.request.MenuUpdateRequest;
import com.cms.admin.menu.dto.response.MenuResponse;
import com.cms.admin.menu.dto.response.MenuTreeResponse;
import com.cms.admin.menu.service.MenuService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/api/menus")
@Tag(name = "Admin Menu", description = "메뉴 관리 API")
public class MenuController {

    private final MenuService menuService;

    @Operation(summary = "메뉴 트리 조회", description = "useYn=true(기본, 활성만) 또는 useYn=all(비활성 포함 전체)")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "400", description = "허용되지 않은 useYn 값")
    @GetMapping("/tree")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<MenuTreeResponse>> getMenuTree(
            @RequestParam(defaultValue = "true") String useYn
    ) {
        return ResponseEntity.ok(menuService.getMenuTree(useYn));
    }

    @Operation(summary = "메뉴 단건 조회")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "404", description = "메뉴 없음")
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<MenuResponse> getMenu(@PathVariable Long id) {
        return ResponseEntity.ok(menuService.getMenu(id));
    }

    @Operation(summary = "메뉴 생성")
    @ApiResponse(responseCode = "201", description = "생성 성공")
    @ApiResponse(responseCode = "400", description = "요청값 검증 실패 또는 비활성 부모 아래 활성 메뉴 생성 시도")
    @ApiResponse(responseCode = "404", description = "부모 메뉴 없음")
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<MenuResponse> createMenu(@Valid @RequestBody MenuCreateRequest request) {
        MenuResponse response = menuService.createMenu(request);
        return ResponseEntity.created(
                ServletUriComponentsBuilder.fromCurrentRequest()
                        .path("/{id}")
                        .buildAndExpand(response.getMenuNo())
                        .toUri()
        ).body(response);
    }

    @Operation(summary = "메뉴 수정", description = "부모(upMenuNo)는 변경할 수 없다. null 필드는 기존값을 유지한다.")
    @ApiResponse(responseCode = "200", description = "수정 성공")
    @ApiResponse(responseCode = "400", description = "요청값 검증 실패 또는 비활성 부모 아래로 재활성화 시도")
    @ApiResponse(responseCode = "404", description = "메뉴 없음")
    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<MenuResponse> updateMenu(
            @PathVariable Long id,
            @Valid @RequestBody MenuUpdateRequest request
    ) {
        return ResponseEntity.ok(menuService.updateMenu(id, request));
    }

    @Operation(summary = "메뉴 비활성화", description = "하드 삭제가 아닌 useYn=false 처리. 활성 하위 메뉴가 있으면 거부된다.")
    @ApiResponse(responseCode = "204", description = "비활성화 성공")
    @ApiResponse(responseCode = "404", description = "메뉴 없음")
    @ApiResponse(responseCode = "409", description = "활성 하위 메뉴 존재로 비활성화 거부")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deactivateMenu(@PathVariable Long id) {
        menuService.deactivateMenu(id);
        return ResponseEntity.noContent().build();
    }
}
