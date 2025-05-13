package com.cms.admin.menu;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import lombok.*;

import java.util.Date;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Menu {

    @Id
    @GeneratedValue
    private Long menuNo;

    private String menuName;

    private String menuCategory;

    private String menuUrl;

    private String urlTarget;

    private String useYn;

    private String ord;

    private String menuLevel;

    private String topMenuNo;

    private String upMenuNo;

    private String menuDesc;

    private Date createDate;

    private Date updateDate;
}
