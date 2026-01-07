package com.volcano.chat.service;

import com.volcano.chat.dto.UserTokenInfo;

/**
 * 用户 Token 信息管理服务
 * 使用 JWT Token 作为 Key，存储手机号和 Coze Token
 */
public interface UserTokenService {

    /**
     * 创建用户 Token 信息
     * 
     * @param phone 用户手机号
     * @return 生成的 JWT Token
     */
    String createUserToken(String phone);

    /**
     * 根据 JWT Token 获取用户信息
     * 
     * @param jwtToken JWT Token
     * @return 用户信息（手机号、Coze Token），不存在返回 null
     */
    UserTokenInfo getUserTokenInfo(String jwtToken);

    /**
     * 删除用户 Token 信息
     * 
     * @param jwtToken JWT Token
     * @return 是否删除成功
     */
    boolean deleteUserToken(String jwtToken);
}
