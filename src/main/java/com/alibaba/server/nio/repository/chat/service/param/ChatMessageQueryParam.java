package com.alibaba.server.nio.repository.chat.service.param;

import com.alibaba.server.nio.core.param.DalPageQueryParam;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ChatMessageQueryParam extends DalPageQueryParam {
    private Long id;
    private Integer senderId;
    private Integer receiverId;
    private Integer status;
}
