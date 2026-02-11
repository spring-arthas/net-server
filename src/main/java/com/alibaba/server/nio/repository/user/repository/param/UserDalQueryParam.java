package com.alibaba.server.nio.repository.user.repository.param;

import com.alibaba.server.nio.core.param.DalPageQueryParam;
import lombok.Data;
import java.util.Date;

/**
 * 用户查询dal param
 * @author spring
 */
@Data
public class UserDalQueryParam extends DalPageQueryParam {

    private String userName;

    private String password;

    private Date lastLoginDate;

    private Date registerDate;
}
