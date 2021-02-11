package com.alibaba.server.nio.repository.user.service.param;

import com.alibaba.server.nio.core.param.PageQueryParam;
import lombok.Data;
import java.util.Date;

/**
 * 用户查询 service param
 *
 * @author spring
 */

@Data
public class UserQueryParam extends PageQueryParam {

    private String userName;

    private String password;

    private Date lastLoginDate;

    private Date registerDate;

    private String register;

    private String status;
}
