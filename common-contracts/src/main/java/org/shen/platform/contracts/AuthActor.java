package org.shen.platform.contracts;

import java.util.List;

/**
 * 经身份服务确认的当前操作者快照。
 * 用户与会话 ID 使用字符串传输，避免前端 JavaScript 大整数精度丢失。
 * roles 来自当前数据库权限，authVersion 用于识别改角色、停用后失效的旧令牌。
 */
public record AuthActor(String userId, String sessionId, String username, String displayName,
                        String status, long authVersion, List<String> roles) {
}
