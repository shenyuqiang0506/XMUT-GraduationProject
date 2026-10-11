package org.shen.identity.application;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.shen.identity.domain.AuthCommands;
import org.shen.identity.infrastructure.*;
import org.shen.platform.contracts.AuthActor;
import org.shen.platform.support.BusinessException;
import org.shen.platform.support.IdentityLookup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Profile("business & !m1")
public class AuthenticationService implements IdentityLookup {
    private final IdentityRepository repo;
    private final PasswordEncoder passwords;
    private final AccessTokens tokens;
    private final RevocationCache cache;
    private final TransactionTemplate transactions;
    private final long refreshSeconds;
    private final String dummyHash;
    public AuthenticationService(IdentityRepository repo,PasswordEncoder passwords,AccessTokens tokens,
            RevocationCache cache,TransactionTemplate transactions,@Value("${AUTH_REFRESH_TTL_SECONDS:604800}") long refreshSeconds) {
        if(refreshSeconds<60 || refreshSeconds>2592000) throw new IllegalStateException("Refresh TTL must be 60..2592000 seconds");
        this.repo=repo;this.passwords=passwords;this.tokens=tokens;this.cache=cache;this.transactions=transactions;
        this.refreshSeconds=refreshSeconds;this.dummyHash=passwords.encode(TokenSecrets.refreshToken());
    }
    private static BusinessException invalid() { return new BusinessException(401,"UNAUTHORIZED","账号或密码错误"); }
    public AccessTokens.Tokens login(AuthCommands.Login command) {
        if(command.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72) throw invalid();
        return transactions.execute(tx -> {
            var user=repo.username(command.username(),true);
            boolean matched=passwords.matches(command.password(),user==null?dummyHash:user.passwordHash());
            if(user==null || !matched || !"ACTIVE".equals(user.status())) throw invalid();
            var prior=repo.sql().queryForList("SELECT id FROM user_session WHERE user_id=? AND device_id=? AND revoked_at IS NULL",Long.class,user.id(),command.deviceId());
            repo.sql().update("UPDATE user_session SET revoked_at=UTC_TIMESTAMP(3),version=version+1 WHERE user_id=? AND device_id=? AND revoked_at IS NULL",user.id(),command.deviceId());
            prior.forEach(cache::afterCommit);
            String refresh=TokenSecrets.refreshToken();
            repo.sql().update("INSERT INTO user_session(user_id,device_id,device_type,refresh_token_hash,expires_at) VALUES (?,?,?,?,?)",
                    user.id(),command.deviceId(),command.deviceType(),TokenSecrets.hash(refresh),Timestamp.from(Instant.now().plusSeconds(refreshSeconds)));
            long sid=repo.sql().queryForObject("SELECT LAST_INSERT_ID()",Long.class);
            repo.audit(user.id(),"LOGIN",user.id(),null,null);
            return tokens.issue(repo.actor(user,Long.toString(sid)),refresh);
        });
    }
    private record RefreshOutcome(AccessTokens.Tokens tokens,BusinessException error) {}
    public AccessTokens.Tokens refresh(AuthCommands.Refresh command) {
        String hash=TokenSecrets.hash(command.refreshToken());
        var candidate=repo.findRefresh(hash);
        if(candidate==null) throw new BusinessException(401,"INVALID_REFRESH_TOKEN","刷新凭据无效");
        RefreshOutcome outcome=transactions.execute(tx -> {
            var user=repo.user(candidate.userId(),true); // Global lock order: user, then session.
            var session=repo.session(candidate.id(),true);
            if(session==null || session.revoked() || !session.expiresAt().isAfter(Instant.now())
                    || user==null || !"ACTIVE".equals(user.status())) {
                return new RefreshOutcome(null,new BusinessException(401,"SESSION_REVOKED","登录状态已失效"));
            }
            if(!session.deviceId().equals(command.deviceId())) {
                return new RefreshOutcome(null,new BusinessException(401,"INVALID_REFRESH_TOKEN","设备与刷新凭据不匹配"));
            }
            if(!hash.equals(session.refreshHash())) {
                revoke(session.id());
                repo.audit(user.id(),"REFRESH_REUSE",user.id(),null,null);
                return new RefreshOutcome(null,new BusinessException(401,"REFRESH_TOKEN_REUSED","刷新凭据已使用，请重新登录"));
            }
            String refresh=TokenSecrets.refreshToken();
            repo.sql().update("INSERT INTO used_refresh_token(token_hash,session_id,expires_at) VALUES (?,?,?)",hash,session.id(),Timestamp.from(session.expiresAt()));
            repo.sql().update("UPDATE user_session SET refresh_token_hash=?,previous_token_hash=?,previous_token_until=expires_at,version=version+1 WHERE id=?",TokenSecrets.hash(refresh),hash,session.id());
            return new RefreshOutcome(tokens.issue(repo.actor(user,Long.toString(session.id())),refresh),null);
        });
        // Throw after the transaction commits, so replay detection cannot roll back revocation.
        if(outcome.error()!=null) throw outcome.error();
        return outcome.tokens();
    }
    public void logout(AuthActor actor) {
        transactions.executeWithoutResult(tx -> {
            repo.user(Long.parseLong(actor.userId()),true);
            var session=repo.session(Long.parseLong(actor.sessionId()),true);
            if(session!=null && session.userId()==Long.parseLong(actor.userId())) revoke(session.id());
            repo.audit(Long.parseLong(actor.userId()),"LOGOUT",Long.parseLong(actor.userId()),null,null);
        });
    }
    private void revoke(long sid) {
        repo.sql().update("UPDATE user_session SET revoked_at=UTC_TIMESTAMP(3),version=version+1 WHERE id=? AND revoked_at IS NULL",sid);
        cache.afterCommit(sid);
    }
    public AuthActor resolve(Jwt jwt) {
        long uid=Long.parseLong(jwt.getSubject()),sid=Long.parseLong(jwt.getClaimAsString("sid"));
        if(cache.revoked(sid)) throw new BusinessException(401,"SESSION_REVOKED","登录状态已撤销");
        // Read roles/version/session as one coherent identity while administration is serialized.
        return transactions.execute(tx -> {
        var user=repo.user(uid,true);
        var session=repo.session(sid,true);
        long version=((Number)jwt.getClaim("auth_version")).longValue();
        if(user==null || session==null || session.userId()!=uid || session.revoked()
                || !session.expiresAt().isAfter(Instant.now()) || user.version()!=version || !"ACTIVE".equals(user.status())) {
            throw new BusinessException(401,"SESSION_REVOKED","登录状态已失效");
        }
        return repo.actor(user,Long.toString(sid));
        });
    }
}
