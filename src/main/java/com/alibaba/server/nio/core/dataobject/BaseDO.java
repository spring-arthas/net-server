/*
 * Copyright 1999-2004 Alibaba.com All right reserved. This software is the confidential and proprietary information of
 * Alibaba.com ("Confidential Information"). You shall not disclose such Confidential Information and shall use it only
 * in accordance with the terms of the license agreement you entered into with Alibaba.com.
 */
package com.alibaba.server.nio.core.dataobject;

import com.alibaba.server.common.YesOrNoEnum;
import com.alibaba.server.nio.core.facade.CloneableSupport;
import com.alibaba.server.nio.core.facade.Identity;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import java.util.Date;

/**
 * 基础数据对象类。
 * 所有数据对象类都应该继承该类，该表的字段基本都是框架维护的。
 *
 * @author liubei
 * @date 2020/08/27
 */
@Getter
@Setter
@ToString
public abstract class BaseDO extends DO implements Identity, CloneableSupport {

    /**
     * 主键
     *
     * INSERT：插入时id留空走自动生成，见 {@link IdGenerator} ；插入时id非空不走自动生成，直接执行到数据库。
     * UPDATE：必传，根据id更新
     * 表结构约定：id字段不建议设置为自增，以免分库分表场景下出现问题。自行设置id请务必确保与分表字段路由结果一致。
     */
    private Long id;

    /**
     * 创建时间
     *
     * INSERT：无需手动维护，框架自动维护
     * UPDATE：无需手动维护，框架自动维护
     * 表结构约定：无需设置默认值
     */
    private Date gmtCreate;

    /**
     * 修改时间
     *
     * INSERT：无需手动维护，框架自动维护
     * UPDATE：无需手动维护，框架自动维护
     * 表结构约定：无需设置默认值
     */
    private Date gmtModified;

    /**
     * 删除标记
     *
     * INSERT：在构造器中设置为了del = N，请按需设置
     * UPDATE：在构造器中设置为了del = N，请按需设置
     * 表结构约定：无需设置默认值
     *
     * @see YesOrNoEnum
     */
    private String del;

    /**
     * 删除时间
     *
     * INSERT：无需手动维护，框架自动维护
     * UPDATE：如果设置del = Y，需要同时设置delTime = new Date()
     * 表结构约定：无需设置默认值
     */
    private Date delTime;

    public BaseDO() {
        this.del = YesOrNoEnum.N.name();
    }

}
