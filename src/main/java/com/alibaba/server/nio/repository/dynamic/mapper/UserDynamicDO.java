package com.alibaba.server.nio.repository.dynamic.mapper;

import com.alibaba.server.nio.core.dataobject.BaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/** 用户动态及时间线查询结果。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserDynamicDO extends BaseDO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long userId;
    private String content;
    private String imagePaths;
    private String mediaJson;
    private String referenceJson;

    // [修改] 以下字段仅承载时间线联表结果，不参与动态写入。
    private String userName;
    private String nickName;
    private String avatar;
    private Integer likeCount;
    private Integer replyCount;
    private Integer repostCount;
    private Boolean liked;
    private Boolean reposted;
}
