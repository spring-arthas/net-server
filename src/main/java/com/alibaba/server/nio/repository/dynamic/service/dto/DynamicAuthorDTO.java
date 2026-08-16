package com.alibaba.server.nio.repository.dynamic.service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 动态公开作者信息。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DynamicAuthorDTO {
    private Long id;
    private String username;
    private String nickname;
    private String avatar;
}
