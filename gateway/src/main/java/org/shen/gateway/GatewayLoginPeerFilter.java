package org.shen.gateway;

import org.shen.platform.security.LoginPeerSignatures;
import org.shen.platform.security.ServiceCallSignatures;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;

/** 在入口清除伪造的登录来源头，再以 TCP 对端地址签名；不能使用客户端的转发头。 */
@Component
@Profile("business")
class GatewayLoginPeerFilter implements WebFilter, Ordered {
    private final byte[] key;
    private final String audience;

    GatewayLoginPeerFilter(@Value("${LOGIN_PEER_KEY_PATH}") String path,@Value("${JWT_AUDIENCE}") String audience) {
        key=ServiceCallSignatures.readKey(path);this.audience=audience;
    }
    public int getOrder(){return Ordered.HIGHEST_PRECEDENCE+1;}

    public Mono<Void> filter(ServerWebExchange exchange,WebFilterChain chain) {
        var incoming=exchange.getRequest();
        var signed=new java.util.HashMap<String,String>();
        if(org.springframework.http.HttpMethod.POST.equals(incoming.getMethod())&&"/api/v1/auth/login".equals(incoming.getPath().value())) {
            var remote=incoming.getRemoteAddress();
            if(remote==null||remote.getAddress()==null)
                return Mono.error(new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"登录来源无法确定"));
            // IPv6 接口范围是本机路由信息；计数使用数字地址，不把接口名带到下游。
            String peer=remote.getAddress().getHostAddress().split("%",2)[0];
            signed.putAll(LoginPeerSignatures.headers(key,audience,peer));
        }
        var request=incoming.mutate().headers(headers -> {
            LoginPeerSignatures.HEADERS.forEach(headers::remove);
            signed.forEach(headers::set);
        }).build();
        return chain.filter(exchange.mutate().request(request).build());
    }
}
