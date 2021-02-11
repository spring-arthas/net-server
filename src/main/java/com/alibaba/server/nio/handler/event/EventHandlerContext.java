package com.alibaba.server.nio.handler.event;

import com.alibaba.server.nio.model.EventModel;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

/**
 * @Auther: YSFY
 * @Date: 2020-11-21 11:51
 * @Pacage_name: com.alibaba.server.nio.Handler
 * @Project_Name: net-server
 * @Description: 事件处理上下文
 */

@Slf4j
@SuppressWarnings("all")
public final class EventHandlerContext<T extends EventModel> {
    private static EventHandlerContext eventHandlerContext = new EventHandlerContext();
    private AbstractEventHandler currentEventHandler = null;
    private AbstractEventHandler rootEventHandler = new RootEventHandler();

    private EventHandlerContext() {
        // 特殊情况可以通过反射创建
    }

    /**
     * 添加EventHandler
     *
     * @param eventHandler
     *
     * @return EventHandlerContext
     * */
    public EventHandlerContext addEventHandler(AbstractEventHandler eventHandler) {
        if(!Optional.ofNullable(this.currentEventHandler).isPresent()) {
            this.rootEventHandler.setNextEventHandler(eventHandler);
        } else {
            this.currentEventHandler.setNextEventHandler(eventHandler);
        }
        this.currentEventHandler = eventHandler;
        return this;
    }

    /**
     * 执行处理
     *
     * @param eventHandler
     *
     * @return EventHandlerContext
     * */
    public void execute(T t) {
        this.rootEventHandler.eventHandler(t);
    }

    private class RootEventHandler extends AbstractEventHandler {

        @Override
        public EventModel eventHandler(EventModel eventModel) {
            return super.getNextEventHandler().eventHandler(eventModel);
        }
    }

    public static EventHandlerContext getEventHandlerContext() {
        return eventHandlerContext;
    }
}
