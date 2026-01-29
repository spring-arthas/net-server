package com.alibaba.server.nio.repository.user.service.dto;

import com.alibaba.server.nio.core.service.BaseDTO;
import lombok.Data;
import java.util.Date;
import java.util.Map;

/**
 * 用户实体类
 *
 * @author spring
 */

@Data
public class UserDTO extends BaseDTO {

    private String userName;

    private String password;

    private Date lastLoginDate;

    private Date registerDate;

    private String phone;

    private String mail;

    /**
     * 上传的文件信息
     * */
    private Map<String, Map<String, Object>> uploadFileMap;

    /**
     * 下载的文件信息
     * */
    private Map<String, Map<String, Object>> downloadFileMap;
}
