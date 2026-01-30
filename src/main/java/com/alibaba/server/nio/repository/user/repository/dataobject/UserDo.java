package com.alibaba.server.nio.repository.user.repository.dataobject;

import com.alibaba.server.nio.core.dataobject.BaseDO;
import com.alibaba.server.nio.core.facade.CloneableSupport;
import com.alibaba.server.nio.core.facade.Identity;
import lombok.Data;
import java.util.Date;

/**
 * 用户 Do
 * @author spring
 */
@Data
public class UserDo extends BaseDO implements Identity, CloneableSupport {

    private String userName;

    private String password;

    private String phone;

    private String mail;

    private Date lastLoginDate;

    private Date registerDate;

}
