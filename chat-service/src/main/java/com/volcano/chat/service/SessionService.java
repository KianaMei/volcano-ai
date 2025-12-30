package com.volcano.chat.service;

public interface SessionService {
    /**
     * 根据 Token 获取关联的用户手机号
     * 
     * @param token 凭证
     * @return 手机号，通过校验失败则返回 null
     */
    String getUserUuid(String token);

    /**
     * 为指定手机号生成唯一的访问 Token
     * 
     * @param phone 手机号
     * @return 生成的 Token
     */
    String createToken(String phone);
}
