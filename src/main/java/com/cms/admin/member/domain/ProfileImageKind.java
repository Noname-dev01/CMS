package com.cms.admin.member.domain;

/**
 * {@code Member.profileImageUrl} 값이 지금 무엇을 의미하는지 명시적으로 표현한다.
 * 문자열 생김새(정규식·접두어)로 추론하지 않는 이유는
 * adversarial-review/plan/PLAN-profile-image-storage.md 쟁점 2(v2~v6 개정 이력) 참조 —
 * 공지 첨부파일과 같은 {@code FileStorage} 키 공간을 공유하는 상황에서, 문자열 셰이프만으로
 * "이 값을 지워도 되는 storageKey인가"를 판단하면 오염된 값이 다른 소비자의 파일을 가리킬 때
 * 그 파일을 잘못 읽거나 지울 수 있다.
 */
public enum ProfileImageKind {

    /** 이미지 없음 — {@code profileImageUrl == null}. */
    NONE,

    /** 정적 프리셋 경로(4종) 중 하나 — {@code profileImageUrl}은 그대로 pass-through 렌더링된다. */
    PRESET,

    /**
     * {@code com.cms.common.storage.FileStorage}의 {@code "profile"} 네임스페이스에 실파일로
     * 저장된 storageKey. {@link com.cms.common.storage.FileStorage#load(String, String)}/
     * {@link com.cms.common.storage.FileStorage#delete(String, String)}를 호출해도 되는
     * 유일한 상태다 — 이 값은 오직 {@code Member.changeUploadedProfileImage()}·
     * {@code Member.migrateProfileImageToStorage()}를 통해서만, {@code FileStorage.store()}가
     * 성공한 직후에만 설정된다.
     */
    UPLOADED,

    /**
     * 미이관 레거시 {@code data:<mime>;base64,...} 값. {@code FileStorage}는 절대 건드리지 않고
     * 그대로 pass-through 렌더링한다. {@code ProfileImageMigrationRunner}가 다음 기동에서 이관을
     * 시도하며, 실패(화이트리스트 밖 MIME·손상된 Base64 등)해도 이 상태로 남아 기존처럼 정상
     * 표시된다.
     */
    LEGACY_INLINE
}
