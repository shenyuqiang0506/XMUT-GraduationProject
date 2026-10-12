package org.shen.platform.support;

import org.shen.platform.contracts.AuthActor;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 当前身份的查询边界。
 * 身份服务使用本地数据库实现，其他业务服务通过内部接口实现，统一返回可信操作者。
 */
public interface IdentityLookup {
    AuthActor resolve(Jwt jwt);
}
