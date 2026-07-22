package com.alibaba.server.nio.handler.event.concret;

import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.nio.core.server.BasicServer;
import com.alibaba.server.nio.model.SocketChannelContext;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Map;

public class WriteQueueHelperTest {

    private Object originalSelectorConfig;

    @After
    public void tearDown() {
        if (originalSelectorConfig == null) {
            BasicServer.getMap().remove(BasicConstant.SELECTOR);
        } else {
            BasicServer.getMap().put(BasicConstant.SELECTOR, originalSelectorConfig);
        }
    }

    @Test
    public void partialTextWriteRegistersWriteInterestOnTextSelector() throws Exception {
        assertPartialWriteRegistersWriteInterestOnExpectedSelector("TEXT", true);
    }

    @Test
    public void partialUploadWriteKeepsWriteInterestOnFileSelector() throws Exception {
        assertPartialWriteRegistersWriteInterestOnExpectedSelector("UPLOAD", false);
    }

    private void assertPartialWriteRegistersWriteInterestOnExpectedSelector(String handlerType, boolean expectTextSelector)
            throws Exception {
        try (Selector textSelector = Selector.open();
                Selector fileSelector = Selector.open();
                ServerSocketChannel listener = ServerSocketChannel.open();
                SocketChannel client = SocketChannel.open()) {
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            client.connect(listener.getLocalAddress());

            try (SocketChannel server = listener.accept()) {
                server.configureBlocking(false);
                server.socket().setSendBufferSize(1024);
                client.socket().setReceiveBufferSize(1024);

                SelectionKey textKey = server.register(textSelector, SelectionKey.OP_READ);
                SelectionKey fileKey = server.register(fileSelector, SelectionKey.OP_READ);
                installSelectors(textSelector, fileSelector);
                SocketChannelContext context = new SocketChannelContext();
                context.setSocketChannel(server);
                context.setHandlerType(handlerType);
                context.setRemoteAddress("127.0.0.1:test");

                WriteQueueHelper.submitWrite(context, ByteBuffer.allocate(8 * 1024 * 1024));

                Assert.assertFalse(context.getPendingWriteQueue().isEmpty());
                SelectionKey expectedKey = expectTextSelector ? textKey : fileKey;
                SelectionKey otherKey = expectTextSelector ? fileKey : textKey;
                Assert.assertTrue((expectedKey.interestOps() & SelectionKey.OP_WRITE) != 0);
                Assert.assertFalse((otherKey.interestOps() & SelectionKey.OP_WRITE) != 0);
            }
        }
    }

    private void installSelectors(Selector textSelector, Selector fileSelector) {
        originalSelectorConfig = BasicServer.getMap().get(BasicConstant.SELECTOR);
        Map<String, Object> selectors = new HashMap<>();
        selectors.put(BasicConstant.NIO_SERVER_MAIN_CORE_TEXT_SELECTOR,
                selectorEntry(BasicConstant.NIO_SERVER_MAIN_CORE_TEXT_SELECTOR, textSelector));
        selectors.put(BasicConstant.NIO_SERVER_MAIN_CORE_FILE_SELECTOR,
                selectorEntry(BasicConstant.NIO_SERVER_MAIN_CORE_FILE_SELECTOR, fileSelector));
        BasicServer.getMap().put(BasicConstant.SELECTOR, selectors);
    }

    private Map<String, Object> selectorEntry(String name, Selector selector) {
        Map<String, Object> entry = new HashMap<>();
        entry.put(name, selector);
        return entry;
    }

}
