package org.shen.graduationproject.infrastructure;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.shen.platform.contracts.EventTopics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.event.*;
import tools.jackson.databind.ObjectMapper;

/**
 * 仅缓存已提交的目录展示字段，不缓存权限、来源覆盖、占用、可用性结论或申请额度。
 * 先读取 MySQL 当前资源与权限，再比对缓存版本；失效失败时靠版本和 TTL 修复。
 */
@Component @Profile("business") @RequiredArgsConstructor @Slf4j
public class ResourceViewCache {
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    @Value("${JWT_AUDIENCE}") private String scope;
    @Value("${CATALOG_CACHE_TTL_SECONDS:60}") private long ttl;
    private String key(Object id){return "campus:catalog:"+EventTopics.digest(scope)+":"+id;}
    public Map<String,Object> get(Map<String,Object> row,Supplier<Map<String,Object>> fresh){
        // 写事务及一致性快照不得发布未提交内容，也不得用缓存替代该事务内的读取。
        if(TransactionSynchronizationManager.isActualTransactionActive()||ttl<=0)return fresh.get();
        String key=key(row.get("id"));
        try {
            String cached=redis.opsForValue().get(key);
            if(cached!=null){var data=json.readValue(cached,Map.class);
                if(data.get("version") instanceof Number v&&v.longValue()==((Number)row.get("version")).longValue()&&data.get("value") instanceof Map<?,?> value&&row.get("id").toString().equals(value.get("id")))return (Map<String,Object>)value;}
        } catch(RuntimeException unavailable){log.debug("目录缓存读取失败，使用数据库");}
        var result=fresh.get();
        try {redis.opsForValue().set(key,json.writeValueAsString(Map.of("version",row.get("version"),"value",result)),Duration.ofSeconds(Math.min(ttl,300)));}
        catch(RuntimeException unavailable){log.debug("目录缓存写入失败，本次数据库结果仍有效");}
        return result;
    }
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
    public void invalidate(BookingWriteCoordinator.ResourcesChanged change){
        try {redis.delete(change.resourceIds().stream().map(this::key).toList());}
        catch(RuntimeException unavailable){log.debug("目录缓存失效未完成，后续读取核对 MySQL 版本并由 TTL 清理");}
    }
}
