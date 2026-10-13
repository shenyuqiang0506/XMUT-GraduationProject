package org.shen.notification;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.shen.platform.contracts.*;
import org.shen.platform.support.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

/** 消费去重记录与本人通知同事务写入；不同版本的事件保留历史，不把业务对象状态回退。 */
@Service @Profile("business") @RequiredArgsConstructor
public class NotificationStore {
    private final JdbcTemplate sql;
    private final TransactionTemplate transactions;
    @Value("${JWT_AUDIENCE}") private String scope;
    private static String display(Object value){if(value==null)return null;Instant instant=value instanceof Timestamp t?t.toInstant():((LocalDateTime)value).toInstant(ZoneOffset.UTC);return instant.atOffset(ZoneOffset.ofHours(8)).toString();}
    public void accept(BookingEvent event,String hash){
        event.validate(scope);
        transactions.executeWithoutResult(tx -> {
            String consumer="notification:"+EventTopics.digest(scope).substring(0,20);
            sql.update("INSERT INTO consumed_event(consumer_name,event_id,payload_sha256) VALUES (?,?,?) ON DUPLICATE KEY UPDATE id=id",consumer,event.eventId(),hash);
            var row=sql.queryForMap("SELECT * FROM consumed_event WHERE consumer_name=? AND event_id=? FOR UPDATE",consumer,event.eventId());
            if(!hash.equals(row.get("payload_sha256")))throw new IllegalArgumentException("同一事件编号对应不同消息内容");
            if(row.get("processed_at")!=null)return;
            String title=title(event.eventType());String body=title+"。请打开关联记录查看当前状态。";
            if("WAITLIST_OFFERED".equals(event.eventType()))body="整组资源已暂占，请在 "+Objects.toString(event.payload().get("offerExpiresAt"),"截止时间")+" 前确认；确认后仍需审批。";
            if("APPLICATION_SOURCE_CONFLICT".equals(event.eventType()))body="资源或学校来源发生变化，预约进入冲突复核，请勿按原批准结果使用资源。";
            for(String recipient:event.recipientIds()){
                sql.update("INSERT INTO notification(recipient_id,event_id,delivery_scope,kind,title,body,object_type,object_id,aggregate_version,occurred_at) VALUES (?,?,?,?,?,?,?,?,?,?)",Long.parseLong(recipient),event.eventId(),scope,event.eventType(),title,body,event.aggregateType(),Long.parseLong(event.aggregateId()),event.aggregateVersion(),Timestamp.from(OffsetDateTime.parse(event.occurredAt()).toInstant()));
                long id=sql.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
                sql.update("INSERT INTO delivery_attempt(notification_id,channel,status,attempt_no) VALUES (?,'IN_APP','DELIVERED',1)",id);
            }
            sql.update("UPDATE consumed_event SET processed_at=UTC_TIMESTAMP(3) WHERE id=?",row.get("id"));
        });
    }
    private static String title(String type){return switch(type){
        case "APPLICATION_SUBMITTED" -> "预约申请待审批";case "APPLICATION_APPROVED" -> "预约已批准";
        case "APPLICATION_REJECTED" -> "预约申请已驳回";case "APPLICATION_CANCELLED" -> "预约已取消";
        case "APPLICATION_EXPIRED" -> "预约申请已过期";case "APPLICATION_COMPLETED" -> "活动已结束";
        case "APPLICATION_SOURCE_CONFLICT" -> "预约冲突待复核";case "WAITLIST_CREATED" -> "已加入候补";
        case "WAITLIST_OFFERED" -> "候补获得确认机会";case "WAITLIST_CONFIRMED" -> "候补已转为待审批申请";
        case "WAITLIST_EXPIRED" -> "候补已过期";case "WAITLIST_CANCELLED" -> "候补已取消";default -> throw new IllegalArgumentException("通知类型无效");};}
    private Map<String,Object> view(Map<String,Object> row){
        var result=new LinkedHashMap<String,Object>();result.put("notificationId",row.get("id").toString());result.put("title",row.get("title"));result.put("body",row.get("body"));
        result.put("kind",row.get("kind"));result.put("objectType",row.get("object_type"));result.put("objectId",row.get("object_id").toString());result.put("aggregateVersion",row.get("aggregate_version"));
        result.put("occurredAt",display(row.get("occurred_at")));result.put("createdAt",display(row.get("created_at")));result.put("readAt",display(row.get("read_at")));return result;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> list(AuthActor actor,boolean unread,int page,int size){
        if(page<1||page>100000||size<1||size>100)throw new BusinessException(400,"VALIDATION_ERROR","通知分页无效");
        long uid=Long.parseLong(actor.userId());String where="delivery_scope=? AND recipient_id=?"+(unread?" AND read_at IS NULL":"");
        long total=sql.queryForObject("SELECT COUNT(*) FROM notification WHERE "+where,Long.class,scope,uid);
        long unreadCount=sql.queryForObject("SELECT COUNT(*) FROM notification WHERE delivery_scope=? AND recipient_id=? AND read_at IS NULL",Long.class,scope,uid);
        var rows=sql.queryForList("SELECT * FROM notification WHERE "+where+" ORDER BY id DESC LIMIT ? OFFSET ?",scope,uid,size,(page-1)*size);
        return Map.of("items",rows.stream().map(this::view).toList(),"total",total,"unreadCount",unreadCount,"page",page,"size",size);
    }
    @Transactional
    public Map<String,Object> read(AuthActor actor,long id){
        var rows=sql.queryForList("SELECT * FROM notification WHERE id=? AND delivery_scope=? AND recipient_id=? FOR UPDATE",id,scope,Long.parseLong(actor.userId()));
        if(rows.isEmpty())throw new BusinessException(404,"NOT_FOUND","通知不存在或不属于当前账号");
        sql.update("UPDATE notification SET read_at=COALESCE(read_at,UTC_TIMESTAMP(3)) WHERE id=?",id);
        return view(sql.queryForMap("SELECT * FROM notification WHERE id=?",id));
    }
}
