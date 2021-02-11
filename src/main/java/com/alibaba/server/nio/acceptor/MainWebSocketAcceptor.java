package com.alibaba.server.nio.acceptor;

import lombok.extern.slf4j.Slf4j;

/**
 * @Auther: YSFY
 * @Date: 2020-11-21 10:39
 * @Pacage_name: com.alibaba.server.nio.selector
 * @Project_Name: net-server
 * @Description: webSocket Selector
 */

@Slf4j
@SuppressWarnings("all")
public class MainWebSocketAcceptor implements Runnable {

    private String ACCEPTOR = "";

    public MainWebSocketAcceptor(String acceptor) {
        this.ACCEPTOR = acceptor;
    }

    @Override
    public void run() {

    }
}
