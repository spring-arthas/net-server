package com.alibaba.server.nio.repository.user.service.impl;

import com.alibaba.server.nio.repository.user.mapper.UserFriendsRepository;
import com.alibaba.server.nio.repository.user.repository.dataobject.UserFriendsDo;
import com.alibaba.server.nio.repository.user.repository.param.UserFriendsDalQueryParam;
import com.alibaba.server.nio.repository.user.service.UserFriendsService;
import com.alibaba.server.nio.repository.user.service.dto.UserFriendsDTO;
import com.alibaba.server.nio.repository.user.service.param.UserFriendsCreateParam;
import com.alibaba.server.nio.repository.user.service.param.UserFriendsQueryParam;
import com.alibaba.server.nio.repository.user.service.param.UserFriendsUpdateParam;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.List;

/**
 * 用户好友关系服务实现
 * 
 * @author spring
 */
@Slf4j
@Service
public class UserFriendsServiceImpl implements UserFriendsService {

    @Autowired
    private UserFriendsRepository userFriendsRepository;

    @Override
    public UserFriendsDTO create(UserFriendsCreateParam param) {
        UserFriendsDo userFriendsDo = new UserFriendsDo();
        userFriendsDo.setUserId(param.getUserId());
        userFriendsDo.setFriendId(param.getFriendId());
        userFriendsDo.setAlias(param.getAlias());
        userFriendsDo.setDel("N");
        userFriendsDo.setGmtCreated(new Date());
        userFriendsDo.setGmtModified(new Date());

        userFriendsRepository.insertSelective(userFriendsDo);
        return doToDto(userFriendsDo);
    }

    @Override
    public void update(UserFriendsUpdateParam param) {
        UserFriendsDo userFriendsDo = new UserFriendsDo();
        userFriendsDo.setId(param.getId());
        userFriendsDo.setAlias(param.getAlias());
        if (StringUtils.hasText(param.getDel())) {
            userFriendsDo.setDel(param.getDel());
        }
        userFriendsDo.setGmtModified(new Date());

        userFriendsRepository.updateSelective(userFriendsDo);
    }

    @Override
    public List<UserFriendsDTO> query(UserFriendsQueryParam param) {
        UserFriendsDalQueryParam dalParam = new UserFriendsDalQueryParam();
        dalParam.setUserId(param.getUserId());
        dalParam.setFriendId(param.getFriendId());
        dalParam.setDel("N"); // 默认只查询未删除的

        List<UserFriendsDo> doList = userFriendsRepository.query(dalParam);
        List<UserFriendsDTO> dtoList = Lists.newArrayList();
        if (doList != null) {
            doList.forEach(item -> dtoList.add(doToDto(item)));
        }
        return dtoList;
    }

    @Override
    public void delete(Long id) {
        userFriendsRepository.logicDelete(id);
    }

    private UserFriendsDTO doToDto(UserFriendsDo userFriendsDo) {
        if (userFriendsDo == null) {
            return null;
        }
        UserFriendsDTO dto = new UserFriendsDTO();
        dto.setId(userFriendsDo.getId());
        dto.setUserId(userFriendsDo.getUserId());
        dto.setFriendId(userFriendsDo.getFriendId());
        dto.setAlias(userFriendsDo.getAlias());
        dto.setDel(userFriendsDo.getDel());
        dto.setGmtCreated(userFriendsDo.getGmtCreated());
        dto.setGmtModified(userFriendsDo.getGmtModified());
        return dto;
    }
}
