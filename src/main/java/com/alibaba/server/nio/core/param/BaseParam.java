package com.alibaba.server.nio.core.param;

import com.alibaba.server.nio.core.facade.CloneableSupport;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 参数基类
 *
 * @author liubei
 * @date 2020/08/04
 */
@Getter
@Setter
@ToString
public abstract class BaseParam extends Param implements CloneableSupport {

    // 请求时间、来源ip

}
