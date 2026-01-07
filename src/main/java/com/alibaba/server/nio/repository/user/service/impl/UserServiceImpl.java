package com.alibaba.server.nio.repository.user.service.impl;

import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.nio.core.server.BasicServer;
import com.alibaba.server.nio.core.server.NioServerContext;
import com.alibaba.server.nio.reactor.SubReactor;
import com.alibaba.server.nio.repository.user.mapper.UserRepository;
import com.alibaba.server.nio.repository.user.repository.dataobject.UserDo;
import com.alibaba.server.nio.repository.user.repository.param.UserDalQueryParam;
import com.alibaba.server.nio.repository.user.service.UserService;
import com.alibaba.server.nio.repository.user.service.dto.UserDTO;
import com.alibaba.server.nio.repository.user.service.param.UserCreateParam;
import com.alibaba.server.nio.repository.user.service.param.UserQueryParam;
import com.alibaba.server.nio.repository.user.service.param.UserUpdateParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 用户服务
 *
 * @author spring
 * */

@Slf4j
@Service
@SuppressWarnings("all")
public class UserServiceImpl implements UserService {

    @Autowired
    private UserRepository userRepository;

    @Override
    public UserDTO getUserByName(UserQueryParam userQueryParam) {
        try {
            UserDalQueryParam userDalQueryParam = new UserDalQueryParam();
            userDalQueryParam.setUserName(userQueryParam.getUserName());
            List<UserDo> list = this.userRepository.query(userDalQueryParam);
            if (Optional.ofNullable(list).isPresent() && !list.isEmpty()) {
                return this.doToDto(list.get(0));
            }
        } catch (Exception e) {
            log.error(e.getMessage());
        }
        return null;
    }

    @Override
    public UserDTO create(UserCreateParam param) {
        UserDo userDo = this.createParamToDo(param);
        this.userRepository.insertSelective(userDo);
        return this.doToDto(userDo);
    }

    @Override
    public void update(UserUpdateParam param) {
        UserDo userDo = this.updateParamToDo(param);
        this.userRepository.updateSelective(userDo);
    }

    @Override
    public UserDTO getOnlineUser(UserQueryParam param) {
        Map userMap = (Map) BasicServer.getMap().get(BasicConstant.USER);
        Map<String, List<UserDTO>> onlineUserMap = (new ArrayList<UserDTO>(userMap.values())).stream().collect(Collectors.groupingBy(UserDTO::getUserName));
        if(onlineUserMap.containsKey(param.getUserName())) {
            return ((UserDTO) (((List<UserDTO>) onlineUserMap.get(param.getUserName())).get(0)));
        }
        return null;
    }

    private UserDo createParamToDo(UserCreateParam param) {
        UserDo userDo = new UserDo();
        userDo.setUserName(param.getUserName());
        userDo.setPassword(param.getPassword());
        userDo.setPhone(param.getPhone());
        userDo.setMail(param.getMail());
        userDo.setLastLoginDate(param.getLastLoginDate());
        userDo.setRegisterDate(param.getRegisterDate());
        userDo.setRegister(param.getRegister());
        userDo.setStatus(param.getStatus());
        userDo.setGmtCreated(param.getRegisterDate());
        userDo.setGmtModified(param.getRegisterDate());
        userDo.setDelTime(param.getRegisterDate());
        return userDo;
    }

    private UserDo updateParamToDo(UserUpdateParam param) {
        UserDo userDo = new UserDo();
        userDo.setId(param.getId());
        userDo.setUserName(param.getUserName());
        userDo.setPassword(param.getPassword());
        userDo.setPhone(param.getPhone());
        userDo.setMail(param.getMail());
        userDo.setLastLoginDate(param.getLastLoginDate());
        userDo.setRegisterDate(param.getRegisterDate());
        userDo.setRegister(param.getRegister());
        userDo.setStatus(param.getStatus());
        userDo.setGmtModified(param.getLastLoginDate() == null?new Date():param.getLastLoginDate());
        return userDo;
    }

    private UserDTO doToDto(UserDo userDo) {
        UserDTO userDto = new UserDTO();
        userDto.setId(userDo.getId());
        userDto.setUserName(userDo.getUserName());
        userDto.setPassword(userDo.getPassword());
        userDto.setLastLoginDate(userDo.getLastLoginDate());
        userDto.setRegisterDate(userDo.getRegisterDate());
        userDto.setRegister(userDo.getStatus());
        userDto.setStatus(userDo.getStatus());
        return userDto;
    }
}
