package org.shen.platform.support;

import org.shen.platform.contracts.AuthActor;
import org.springframework.security.oauth2.jwt.Jwt;

public interface IdentityLookup {
    AuthActor resolve(Jwt jwt);
}
