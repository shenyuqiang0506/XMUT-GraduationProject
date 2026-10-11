package org.shen.graduationproject.infrastructure;

import java.util.*;
import java.util.function.Supplier;
import org.shen.platform.contracts.AuthActor;
import org.shen.platform.support.BusinessException;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Component
@Profile("business & !m1")
public class CatalogIdempotency {
    public record Result(int status,Object data,boolean replayed) {}
    private final JdbcTemplate sql;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    public CatalogIdempotency(JdbcTemplate sql,TransactionTemplate transactions,ObjectMapper json) { this.sql=sql;this.transactions=transactions;this.json=json; }
    private Object canonical(Object value) {
        if(value instanceof Map<?,?> map) {
            Map<String,Object> ordered=new TreeMap<>();map.forEach((k,v) -> ordered.put(k.toString(),canonical(v)));return ordered;
        }
        if(value instanceof List<?> list) return list.stream().map(this::canonical).toList();
        return value;
    }
    public Result execute(AuthActor actor,String operation,String key,Object payload,Supplier<Result> work) {
        if(key==null || !key.matches("[A-Za-z0-9_-]{8,64}")) throw new BusinessException(400,"INVALID_IDEMPOTENCY_KEY","需要 8–64 位幂等键");
        String hash=ImportStorage.hash(json.writeValueAsString(canonical(json.convertValue(payload,Object.class))).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return transactions.execute(tx -> {
            sql.update("INSERT INTO idempotency_record(actor_id,operation,request_key,payload_hash,status,expires_at) VALUES (?,?,?,?,'IN_PROGRESS',UTC_TIMESTAMP(3)+INTERVAL 1 DAY) ON DUPLICATE KEY UPDATE id=id",Long.parseLong(actor.userId()),operation,key,hash);
            var row=sql.queryForMap("SELECT * FROM idempotency_record WHERE actor_id=? AND operation=? AND request_key=? FOR UPDATE",Long.parseLong(actor.userId()),operation,key);
            if(!hash.equals(row.get("payload_hash"))) throw new BusinessException(409,"IDEMPOTENCY_KEY_REUSED","同一幂等键对应的请求内容不同");
            if("SUCCEEDED".equals(row.get("status"))) return new Result(((Number)row.get("http_status")).intValue(),json.readValue(row.get("response_body").toString(),Object.class),true);
            Result result=work.get();
            sql.update("UPDATE idempotency_record SET status='SUCCEEDED',http_status=?,response_body=? WHERE id=?",result.status(),json.writeValueAsString(result.data()),row.get("id"));
            return result;
        });
    }
}
