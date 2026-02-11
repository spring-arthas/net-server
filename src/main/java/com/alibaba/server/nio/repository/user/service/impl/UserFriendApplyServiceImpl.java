package com.alibaba.server.nio.repository.user.service.impl;

import com.alibaba.server.nio.repository.user.mapper.UserFriendApplyRepository;
import com.alibaba.server.nio.repository.user.repository.dataobject.UserFriendApplyDo;
import com.alibaba.server.nio.repository.user.repository.param.UserFriendApplyDalQueryParam;
import com.alibaba.server.nio.repository.user.service.UserFriendApplyService;
import com.alibaba.server.nio.repository.user.service.dto.UserFriendApplyDTO;
import com.alibaba.server.nio.repository.user.service.param.UserFriendApplyCreateParam;
import com.alibaba.server.nio.repository.user.service.param.UserFriendApplyQueryParam;
import com.alibaba.server.nio.repository.user.service.param.UserFriendApplyUpdateParam;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.List;

/**
 * 好友添加请求服务实现
 * 
 * @author spring
 */
@Slf4j
@Service
public class UserFriendApplyServiceImpl implements UserFriendApplyService {

    @Autowired
    private UserFriendApplyRepository userFriendApplyRepository;

    @Override
    public UserFriendApplyDTO create(UserFriendApplyCreateParam param) {
        UserFriendApplyDo userFriendApplyDo = new UserFriendApplyDo();
        userFriendApplyDo.setSenderId(param.getSenderId());
        userFriendApplyDo.setReceiverId(param.getReceiverId());
        userFriendApplyDo.setRequestMsg(param.getRequestMsg());
        userFriendApplyDo.setStatus(0); // 默认待处理
        userFriendApplyDo.setGmtCreated(new Date());
        userFriendApplyDo.setGmtModified(new Date());

        userFriendApplyRepository.insertSelective(userFriendApplyDo);
        return doToDto(userFriendApplyDo);
    }

    @Override
    public void update(UserFriendApplyUpdateParam param) {
        UserFriendApplyDo userFriendApplyDo = new UserFriendApplyDo();
        userFriendApplyDo.setId(param.getId());
        userFriendApplyDo.setStatus(param.getStatus());
        if (StringUtils.hasText(param.getDel())) {
            userFriendApplyDo.setDel(param.getDel());
        }
        userFriendApplyDo.setGmtModified(new Date());

        userFriendApplyRepository.updateSelective(userFriendApplyDo);
    }

    @Override
    public List<UserFriendApplyDTO> query(UserFriendApplyQueryParam param) {
        UserFriendApplyDalQueryParam dalParam = new UserFriendApplyDalQueryParam();
        dalParam.setSenderId(param.getSenderId());
        dalParam.setReceiverId(param.getReceiverId());
        dalParam.setStatus(param.getStatus());

        List<UserFriendApplyDo> doList = userFriendApplyRepository.query(dalParam);
        List<UserFriendApplyDTO> dtoList = Lists.newArrayList();
        if (doList != null) {
            doList.forEach(item -> dtoList.add(doToDto(item)));
        }
        return dtoList;
    }

    private UserFriendApplyDTO doToDto(UserFriendApplyDo userFriendApplyDo) {
        if (userFriendApplyDo == null) {
            return null;
        }
        UserFriendApplyDTO dto = new UserFriendApplyDTO();
        dto.setId(userFriendApplyDo.getId());
        dto.setSenderId(userFriendApplyDo.getSenderId());
        dto.setReceiverId(userFriendApplyDo.getReceiverId());
        dto.setRequestMsg(userFriendApplyDo.getRequestMsg());
        dto.setStatus(userFriendApplyDo.getStatus());
        dto.setDel(userFriendApplyDo.getDel());
        dto.setGmtCreated(userFriendApplyDo.getGmtCreated());
        dto.setGmtModified(userFriendApplyDo.getGmtModified());
        return dto;
    }
}
