package com.alibaba.server.nio.handler.event;

import com.alibaba.server.nio.model.EventModel;

/**
 * @Auther: YSFY
 * @Date: 2020-11-21 10:38
 * @Pacage_name: com.alibaba.server.nio.Handler
 * @Project_Name: net-server
 * @Description: 事件处理模型
 */

public interface EventHandler<T extends EventModel> {

    /**
     * 事件处理
     *
     * @param t
     *
     * @return T
     * */
    T eventHandler(T t);
}
