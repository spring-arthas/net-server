package com.alibaba.server.nio.handler.event.concret;

import com.alibaba.server.nio.handler.event.AbstractEventHandler;
import com.alibaba.server.nio.model.EventModel;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

/**
 * @Auther: YSFY
 * @Date: 2020-11-21 12:06
 * @Pacage_name: com.alibaba.server.nio.Handler.concret
 * @Project_Name: net-server
 * @Description: 连接[ConnectEventHandler]
 */

@Slf4j
@SuppressWarnings("all")
public class ConnectEventHandler extends AbstractEventHandler {

    @Override
    public EventModel eventHandler(EventModel eventModel) {
        if(!super.checkEvent(eventModel)) {
            return eventModel;
        }

        if(!eventModel.getSelectionKey().isConnectable()) {
            if(!Optional.ofNullable(super.getNextEventHandler()).isPresent()) {
                return eventModel;
            }
            return super.getNextEventHandler().eventHandler(eventModel);
        }

        return this.handler(eventModel);
    }

    /**
     * 执行处理
     *
     * @param eventModel
     *
     * @return eventModel
     * */
    private EventModel handler(EventModel eventModel) {

        return eventModel;
    }
}
