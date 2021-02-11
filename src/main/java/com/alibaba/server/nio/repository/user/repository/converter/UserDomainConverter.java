package com.alibaba.server.nio.repository.user.repository.converter;

import com.alibaba.server.nio.repository.user.repository.dataobject.UserDo;
import com.alibaba.server.nio.repository.user.service.dto.UserDTO;
import com.alibaba.server.nio.repository.user.service.param.UserCreateParam;
import org.mapstruct.Mapper;

/**
 * @author spring
 * @date 2020/9/27
 * @description 用户转换器
 */
@Mapper(componentModel = "spring")
public interface UserDomainConverter {

    /**
     * 用户服务创建参数转DO
     * @param param
     * @return
     */
    UserDo createParamToDo(UserCreateParam param);

    /**
     * 用户服务 DO 转 DTO
     * @param userDo
     * @return
     */
    UserDTO doToDto(UserDo userDo);
}
