package com.alibaba.server.nio.core.facade;

import java.lang.reflect.Method;

/**
 * 拷贝支持类
 *
 * @author liubei
 * @date 2020/10/17
 */
public interface CloneableSupport extends Cloneable {

    /**
     * 默认使用Object#clone()实现浅
     *
     * @param <T>
     * @return
     */
    default <T extends Object> T shallowClone() {
        try {
            // TODO liubei 接口默认方法为什么调用不了Object的protected方法但可以调用public方法（接口方法都是public的原因？），为什么不能使用super？需要研究下
            //return (T)this.clone();
            Method clone = Object.class.getDeclaredMethod("clone");
            clone.setAccessible(true);
            return (T)clone.invoke(this);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("shallowClone error", e);
        }
    }

    /**
     * 深拷贝，需要自行实现，可以使用map struct等bean复制工具实现
     *
     * @param <T>
     * @return
     */
    default <T extends Object> T deepClone() {
        throw new UnsupportedOperationException("deepClone");
    }

}
