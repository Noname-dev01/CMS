package com.cms.admin.search.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class AdminSearchRequest {

    @Size(max = 100)
    private String keyword;
}
