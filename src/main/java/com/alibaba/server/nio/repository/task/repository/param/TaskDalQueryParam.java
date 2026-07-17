package com.alibaba.server.nio.repository.task.repository.param;

import com.alibaba.server.nio.core.annotation.QueryOperator;
import com.alibaba.server.nio.core.param.DalPageQueryParam;
import lombok.Data;

import java.util.Date;

/**
 * 任务查询dal param
 *
 * @author spring
 */

@Data
public class TaskDalQueryParam extends DalPageQueryParam {

    @QueryOperator("LIKE")
    private String userName;

    private String password;

    private String phone;

    private String mail;

    private Date lastLoginDate;

    private Date registerDate;

    private String register;

    private String status;
}
