package org.shen.graduationproject.application;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.shen.graduationproject.domain.*;
import org.shen.graduationproject.domain.ApplicationCommands.*;
import org.shen.graduationproject.infrastructure.*;
import org.shen.platform.contracts.*;
import org.shen.platform.support.BusinessException;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.event.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 固定组合候补：WAITING 不占资源，OFFERED 全组 HOLD，确认原子转交为待审批申请。
 * 组内按 created_at/id 先后处理；资格查询在事务外，所有实际写入遵循 M3 共同锁协议。
 */
@Service @Profile("business") @RequiredArgsConstructor @Slf4j
public class Waitlists {
    private final JdbcTemplate sql;
    private final CatalogIdempotency idempotency;
    private final BookingWriteCoordinator coordinator;
    private final ApplicantQuota quota;
    private final BookingPolicies policies;
    private final ApplicationLifecycle lifecycle;
    private final Applications applications;
    private final AuthorizedApplicants applicants;
    private final CatalogAccess access;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    @Value("${JWT_AUDIENCE}") private String deliveryScope;
    @Value("${WAITLIST_SCHEDULING_ENABLED:true}") private boolean scheduling;
    private final Set<Long> wakeResources=new ConcurrentSkipListSet<>();
    private long scanCursor;
    private static final Set<String> STATUSES=Set.of("WAITING","OFFERED","CONFIRMED","EXPIRED","CANCELLED");

    private long number(Object value){return ((Number)value).longValue();}
    private List<Long> members(long group){return sql.queryForList("SELECT resource_id FROM waitlist_group_resource WHERE group_id=? ORDER BY resource_id",Long.class,group);}
    private Map<String,Object> group(long id,boolean lock){return sql.queryForMap("SELECT * FROM waitlist_group WHERE id=?"+(lock?" FOR UPDATE":""),id);}
    private Map<String,Object> row(long id,boolean lock){
        var rows=sql.queryForList("SELECT * FROM waitlist_entry WHERE id=?"+(lock?" FOR UPDATE":""),id);
        if(rows.isEmpty())throw new BusinessException(404,"NOT_FOUND","候补不存在或不可见");return rows.getFirst();
    }
    private TimeWindow window(Map<String,Object> group){return new TimeWindow(UtcTimes.instant(group.get("start_at")),UtcTimes.instant(group.get("end_at")));}
    private boolean owns(AuthActor actor,Map<String,Object> row){return actor.userId().equals(row.get("applicant_id").toString());}
    private void visible(AuthActor actor,Map<String,Object> entry){
        if(!owns(actor,entry)&&!access.system(actor)&&!(actor.roles().contains("RESOURCE_ADMIN")&&access.groupMember(actor,number(entry.get("approval_group_id")))))
            throw new BusinessException(404,"NOT_FOUND","候补不存在或不可见");
        policies.mode(group(number(entry.get("group_id")),false).get("data_mode").toString());
    }
    private void owner(AuthActor actor,Map<String,Object> entry){if(!owns(actor,entry))throw new BusinessException(404,"NOT_FOUND","候补不存在或不可操作");}
    private boolean due(Map<String,Object> entry,Instant now){
        return ("WAITING".equals(entry.get("status"))&&!UtcTimes.instant(entry.get("expires_at")).isAfter(now))
            ||("OFFERED".equals(entry.get("status"))&&!UtcTimes.instant(entry.get("offer_expires_at")).isAfter(now));
    }

    /** 哈希是索引，命中后仍核对全部成员、UTC 区间与模式，不能只信 hash 或客户端 groupId。 */
    private long ensureGroup(List<Long> ids,TimeWindow window,String mode){
        String key=ApplicationRequests.groupKey(ids,window,mode);
        sql.update("INSERT INTO waitlist_group(group_key,start_at,end_at,data_mode) VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE id=id",key,Timestamp.from(window.start()),Timestamp.from(window.end()),mode);
        long id=sql.queryForObject("SELECT id FROM waitlist_group WHERE group_key=? FOR UPDATE",Long.class,key);
        var saved=group(id,false);var savedIds=members(id);
        if(savedIds.isEmpty())for(long resource:ids)sql.update("INSERT INTO waitlist_group_resource(group_id,resource_id) VALUES (?,?)",id,resource);
        else if(!savedIds.equals(ids)||!mode.equals(saved.get("data_mode"))||!window.equals(window(saved)))throw new IllegalStateException("候补组身份不一致");
        return id;
    }

    public CatalogIdempotency.Result create(AuthActor actor,String key,Create request){
        ApplicationRules.applicant(actor);var input=ApplicationRequests.normalize(request);
        return idempotency.execute(actor,"WAITLIST_CREATE",key,input,() -> {
            long uid=CatalogAccess.id(actor.userId());quota.lock(uid);var ids=ApplicationRequests.members(input);coordinator.resources(ids);
            var venue=sql.queryForMap("SELECT * FROM resource WHERE id=?",CatalogAccess.id(input.venueId()));
            String mode=venue.get("data_mode").toString();policies.mode(mode);var policy=policies.active(mode);
            if(policy==null)throw new BusinessException(422,"POLICY_NOT_CONFIGURED","请先配置已确认的预约策略");
            var window=TimeWindow.of(input.startAt(),input.endAt());policies.eligible(window,policy,policies.now());
            lifecycle.expireForResources(ids,actor);
            Long approval=venue.get("approval_group_id")==null?null:number(venue.get("approval_group_id"));
            var resources=lifecycle.validate(ids,CatalogAccess.id(input.venueId()),input.participantCount(),input.requiredFeatures(),mode,approval,window,policy,null,null,true);
            long gid=ensureGroup(ids,window,mode);expireGroup(gid,actor);
            if(sql.queryForObject("SELECT COUNT(*) FROM waitlist_entry WHERE group_id=? AND applicant_id=? AND status IN ('WAITING','OFFERED')",Long.class,gid,uid)>0)
                throw new BusinessException(409,"DUPLICATE_WAITLIST","你已在同一组合和时段候补");
            // 全空闲且没有既有队列才直接申请。队首暂时不能接收 offer 时，后来者仍可排在
            // 同一固定组合之后；不能绕过 FIFO，也不能因 WAITLIST_PRIORITY 陷入无法入队的死路。
            boolean queued=sql.queryForObject("SELECT COUNT(*) FROM waitlist_entry WHERE group_id=? AND ((status='WAITING' AND expires_at>UTC_TIMESTAMP(3)) OR (status='OFFERED' AND offer_expires_at>UTC_TIMESTAMP(3)))",Long.class,gid)>0;
            if(!queued&&resources.stream().noneMatch(r -> "BUSY".equals(r.get("checked_availability"))))throw new BusinessException(409,"WAITLIST_NOT_NEEDED","资源已全部可用且没有候补队列，请提交普通申请");
            // 技术防滥用上限，不充当学校制度；WAITING 不占申请额度，不允许无限堆积请求。
            if(sql.queryForObject("SELECT COUNT(*) FROM waitlist_entry WHERE applicant_id=? AND status IN ('WAITING','OFFERED') AND expires_at>UTC_TIMESTAMP(3)",Long.class,uid)>=50)
                throw new BusinessException(422,"WAITLIST_LIMIT_REACHED","有效候补过多，请先处理已有候补");
            Instant expires=window.start().minusSeconds(policy.settings().bookingLeadMinutes()*60L);
            if(!expires.isAfter(policies.now()))throw new BusinessException(400,"TIME_RANGE_INVALID","已到候补最晚确认时间");
            var snapshot=Map.of("userId",actor.userId(),"username",actor.username(),"displayName",actor.displayName());
            sql.update("INSERT INTO waitlist_entry(group_id,applicant_id,activity_name,description,participant_count,approval_group_id,required_features,applicant_snapshot,policy_version,policy_snapshot,status,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,'WAITING',?)",gid,uid,input.activityName(),input.description(),input.participantCount(),approval,json.writeValueAsString(input.requiredFeatures()),json.writeValueAsString(snapshot),policy.version(),json.writeValueAsString(policy.settings()),Timestamp.from(expires));
            long id=sql.queryForObject("SELECT LAST_INSERT_ID()",Long.class);bump(gid);event(row(id,false),"WAITLIST_CREATED",actor,null);
            return new CatalogIdempotency.Result(201,detail(actor,id),false);
        });
    }

    private void bump(long group){sql.update("UPDATE waitlist_group SET version=version+1 WHERE id=?",group);}
    /** 事件包含最少状态和截止信息，收件人仅候补本人，不保存或发送其访问令牌。 */
    private void event(Map<String,Object> entry,String type,AuthActor actor,String from){
        long id=number(entry.get("id"));String trace=TraceIds.acceptOrCreate(MDC.get(TraceIds.MDC_KEY));
        sql.update("INSERT INTO audit_log(actor_id,action,object_type,object_id,from_status,to_status,reason,trace_id) VALUES (?,?,'WAITLIST',?,?,?,?,?)",actor==null?null:CatalogAccess.id(actor.userId()),type,id,from,entry.get("status"),entry.get("last_reason"),trace);
        String eventId=UUID.randomUUID().toString();var body=new LinkedHashMap<String,Object>();
        body.put("eventId",eventId);body.put("eventType",type);body.put("aggregateType","WAITLIST");body.put("aggregateId",Long.toString(id));
        body.put("aggregateVersion",entry.get("version"));body.put("occurredAt",TimeWindow.display(policies.now()));body.put("recipientIds",List.of(entry.get("applicant_id").toString()));
        var payload=new LinkedHashMap<String,Object>();payload.put("status",entry.get("status"));payload.put("reason",entry.get("last_reason"));
        payload.put("offerExpiresAt",entry.get("offer_expires_at")==null?null:TimeWindow.display(UtcTimes.instant(entry.get("offer_expires_at"))));
        payload.put("applicationId",entry.get("application_id")==null?null:entry.get("application_id").toString());body.put("payload",payload);
        body.put("traceId",trace);body.put("deliveryScope",deliveryScope);
        sql.update("INSERT INTO outbox_event(event_id,event_type,aggregate_type,aggregate_id,aggregate_version,payload,delivery_scope) VALUES (?,?,'WAITLIST',?,?,?,?)",eventId,type,id,entry.get("version"),json.writeValueAsString(body),deliveryScope);
    }
    /** 终止 OFFERED 必须检查全组释放数量；WAITING 无占用，不凭空创建 RELEASED 行。 */
    private void terminate(Map<String,Object> entry,String target,String reason,AuthActor actor){
        long id=number(entry.get("id")),gid=number(entry.get("group_id"));String from=entry.get("status").toString();
        if(!Set.of("WAITING","OFFERED").contains(from))return;
        if("OFFERED".equals(from)){
            int count=sql.update("UPDATE resource_occupancy SET status='RELEASED',expires_at=NULL,released_at=UTC_TIMESTAMP(3) WHERE waitlist_entry_id=? AND status='HOLD'",id);
            if(count!=members(gid).size())throw new IllegalStateException("候补整组释放数量不一致");
            coordinator.released(members(gid));
        }
        sql.update("UPDATE waitlist_entry SET status=?,offer_expires_at=NULL,last_reason=?,version=version+1 WHERE id=?",target,reason,id);
        bump(gid);event(row(id,false),"WAITLIST_"+target,actor,from);
    }
    private boolean expire(Map<String,Object> entry,AuthActor actor){
        if(!due(entry,policies.now()))return false;
        terminate(entry,"EXPIRED","OFFERED".equals(entry.get("status"))?"OFFER_EXPIRED":"WAITLIST_DEADLINE_PASSED",actor);return true;
    }
    private void expireGroup(long gid,AuthActor actor){
        for(var entry:sql.queryForList("SELECT * FROM waitlist_entry WHERE group_id=? AND status IN ('WAITING','OFFERED') ORDER BY created_at,id FOR UPDATE",gid))expire(entry,actor);
    }

    private Map<String,Object> summary(AuthActor actor,Map<String,Object> entry,Map<String,Object> group){
        var result=new LinkedHashMap<String,Object>();long gid=number(entry.get("group_id"));String state=entry.get("status").toString();
        result.put("waitlistId",entry.get("id").toString());result.put("groupId",Long.toString(gid));result.put("activityName",entry.get("activity_name"));
        result.put("status",state);result.put("version",entry.get("version"));result.put("participantCount",entry.get("participant_count"));
        result.put("dataMode",group.get("data_mode"));result.put("startAt",TimeWindow.display(window(group).start()));result.put("endAt",TimeWindow.display(window(group).end()));
        result.put("expiresAt",TimeWindow.display(UtcTimes.instant(entry.get("expires_at"))));
        result.put("offerExpiresAt",entry.get("offer_expires_at")==null?null:TimeWindow.display(UtcTimes.instant(entry.get("offer_expires_at"))));
        result.put("applicationId",entry.get("application_id")==null?null:entry.get("application_id").toString());result.put("lastReason",entry.get("last_reason"));
        result.put("updatedAt",TimeWindow.display(UtcTimes.instant(entry.get("updated_at"))));
        result.put("queuePosition",Set.of("WAITING","OFFERED").contains(state)&&!due(entry,policies.now())?sql.queryForObject("SELECT COUNT(*) FROM waitlist_entry WHERE group_id=? AND ((status='WAITING' AND expires_at>UTC_TIMESTAMP(3)) OR (status='OFFERED' AND offer_expires_at>UTC_TIMESTAMP(3))) AND (created_at<? OR (created_at=? AND id<=?))",Long.class,gid,entry.get("created_at"),entry.get("created_at"),entry.get("id")):null);
        var actions=new ArrayList<String>();
        if(owns(actor,entry)&&Set.of("WAITING","OFFERED").contains(state)&&!due(entry,policies.now())){
            actions.add("CANCEL");if("OFFERED".equals(state)&&(actor.roles().contains("STUDENT")||actor.roles().contains("TEACHER")))actions.add("CONFIRM");
        }
        result.put("allowedActions",actions);return result;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> detail(AuthActor actor,long id){
        var entry=row(id,false);visible(actor,entry);var group=group(number(entry.get("group_id")),false);var result=summary(actor,entry,group);
        result.put("description",entry.get("description"));result.put("resourceIds",members(number(entry.get("group_id"))).stream().map(Object::toString).toList());
        result.put("resources",sql.queryForList("SELECT r.id,r.name,r.resource_type FROM waitlist_group_resource gr JOIN resource r ON r.id=gr.resource_id WHERE gr.group_id=? ORDER BY r.id",entry.get("group_id")).stream().map(r -> Map.of("id",r.get("id").toString(),"name",r.get("name"),"resourceType",r.get("resource_type"))).toList());
        result.put("requiredFeatures",json.readValue(entry.get("required_features").toString(),List.class));return result;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> list(AuthActor actor,String state,int page,int size){
        if(page<1||page>100000||size<1||size>100||(!state.isBlank()&&!STATUSES.contains(state)))throw new BusinessException(400,"VALIDATION_ERROR","候补状态或分页无效");
        var args=new ArrayList<Object>();args.add(CatalogAccess.id(actor.userId()));String where="w.applicant_id=?";
        if(!state.isBlank()){where+=" AND w.status=?";args.add(state);}
        long total=sql.queryForObject("SELECT COUNT(*) FROM waitlist_entry w WHERE "+where,Long.class,args.toArray());args.add(size);args.add((page-1)*size);
        var rows=sql.queryForList("SELECT w.* FROM waitlist_entry w WHERE "+where+" ORDER BY w.id DESC LIMIT ? OFFSET ?",args.toArray());
        return Map.of("items",rows.stream().map(e -> summary(actor,e,group(number(e.get("group_id")),false))).toList(),"total",total,"page",page,"size",size);
    }

    private CatalogIdempotency.Result expiredResponse(AuthActor actor,long id){
        return new CatalogIdempotency.Result(409,Map.of("code","OFFER_EXPIRED","message","候补已到期，资源已释放","application",detail(actor,id)),false);
    }
    public CatalogIdempotency.Result cancel(AuthActor actor,String key,long id,Action input){
        owner(actor,row(id,false));return idempotency.execute(actor,"WAITLIST_CANCEL:"+id,key,input,() -> {
            var initial=row(id,false);long gid=number(initial.get("group_id"));coordinator.resources(members(gid));group(gid,true);var entry=row(id,true);owner(actor,entry);visible(actor,entry);
            if(number(entry.get("version"))!=input.expectedVersion())throw new BusinessException(409,"VERSION_CONFLICT","候补版本已变化，请刷新");
            if(expire(entry,actor))return expiredResponse(actor,id);
            if(!Set.of("WAITING","OFFERED").contains(entry.get("status")))throw new BusinessException(409,"INVALID_STATE","当前候补不能取消");
            terminate(entry,"CANCELLED",input.reason(),actor);return new CatalogIdempotency.Result(200,detail(actor,id),false);
        });
    }
    public CatalogIdempotency.Result confirm(AuthActor actor,String key,long id,Action input){
        ApplicationRules.applicant(actor);owner(actor,row(id,false));return idempotency.execute(actor,"WAITLIST_CONFIRM:"+id,key,input,() -> {
            var initial=row(id,false);long gid=number(initial.get("group_id")),uid=CatalogAccess.id(actor.userId());quota.lock(uid);
            var ids=members(gid);coordinator.resources(ids);var group=group(gid,true);var entry=row(id,true);owner(actor,entry);visible(actor,entry);
            if(number(entry.get("version"))!=input.expectedVersion())throw new BusinessException(409,"VERSION_CONFLICT","候补版本已变化，请刷新");
            if(expire(entry,actor))return expiredResponse(actor,id);
            if(!"OFFERED".equals(entry.get("status")))throw new BusinessException(409,"INVALID_STATE","当前候补尚未获得确认资格");
            var policy=policies.active(group.get("data_mode").toString());if(policy==null)throw new BusinessException(422,"POLICY_NOT_CONFIGURED","当前策略未配置");
            quota.requireSpace(uid,policy.settings().maxActiveApplicationsPerUser(),id);
            var request=request(entry,group);policies.eligible(window(group),policy,policies.now());
            var rows=lifecycle.validate(ids,CatalogAccess.id(request.venueId()),request.participantCount(),request.requiredFeatures(),group.get("data_mode").toString(),number(entry.get("approval_group_id")),window(group),policy,null,id,false);
            if(expire(entry,actor))return expiredResponse(actor,id);
            Instant hold=ApplicationRules.holdUntil(policies.now(),window(group).start(),policy.settings().pendingApprovalTtlMinutes(),policy.settings().bookingLeadMinutes());
            long application=applications.persist(actor,request,rows,group.get("data_mode").toString(),number(entry.get("approval_group_id")),policy,hold,id);
            sql.update("UPDATE waitlist_entry SET status='CONFIRMED',offer_expires_at=NULL,application_id=?,last_reason=NULL,version=version+1 WHERE id=?",application,id);
            bump(gid);event(row(id,false),"WAITLIST_CONFIRMED",actor,"OFFERED");
            return new CatalogIdempotency.Result(200,detail(actor,id),false);
        });
    }
    private Create request(Map<String,Object> entry,Map<String,Object> group){
        var ids=members(number(group.get("id")));long venue=sql.queryForObject("SELECT r.id FROM waitlist_group_resource gr JOIN resource r ON r.id=gr.resource_id WHERE gr.group_id=? AND r.resource_type='VENUE'",Long.class,group.get("id"));
        return new Create(entry.get("activity_name").toString(),(String)entry.get("description"),((Number)entry.get("participant_count")).intValue(),window(group).start().atOffset(ZoneOffset.UTC),window(group).end().atOffset(ZoneOffset.UTC),Long.toString(venue),ids.stream().filter(id -> id!=venue).map(Object::toString).toList(),new TreeSet<>(json.readValue(entry.get("required_features").toString(),List.class)));
    }

    /** 发布后只记录有限唤醒资源；周期轮转扫描独立补偿，因此重启丢失唤醒也不会永远卡住。 */
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
    public void released(BookingWriteCoordinator.ResourcesReleased event){for(long id:event.resourceIds())if(wakeResources.size()<10000)wakeResources.add(id);}
    @EventListener
    public void changed(BookingWriteCoordinator.ResourcesChanged event){
        var entries=new TreeSet<Long>();
        for(long resource:event.resourceIds())entries.addAll(sql.queryForList("SELECT w.id FROM waitlist_entry w JOIN waitlist_group_resource gr ON gr.group_id=w.group_id WHERE gr.resource_id=? AND w.status='OFFERED'",Long.class,resource));
        for(long id:entries){var initial=row(id,false);var g=group(number(initial.get("group_id")),true);var entry=row(id,true);
            if(expire(entry,event.actor())){event.counts().merge("expiredOffers",1,Integer::sum);continue;}
            try {var policy=policies.active(g.get("data_mode").toString());if(policy==null)throw new BusinessException(422,"POLICY_NOT_CONFIGURED","策略未配置");var request=request(entry,g);
                lifecycle.validate(members(number(g.get("id"))),CatalogAccess.id(request.venueId()),request.participantCount(),request.requiredFeatures(),g.get("data_mode").toString(),number(entry.get("approval_group_id")),window(g),policy,null,id,false);
            } catch(BusinessException conflict){terminate(entry,"EXPIRED","SOURCE_CONFLICT:"+conflict.code(),event.actor());event.counts().merge("expiredOffers",1,Integer::sum);}
        }
        // 来源恢复也能让 WAITING 有机会提升；实际评估放到提交后的调度线程。
        coordinator.released(event.resourceIds());
    }

    @Scheduled(initialDelayString="${WAITLIST_INITIAL_DELAY_MS:3000}",fixedDelayString="${WAITLIST_DELAY_MS:1000}")
    public void tick(){
        if(!scheduling)return;
        try {
            for(long id:sql.queryForList("SELECT id FROM waitlist_entry WHERE (status='WAITING' AND expires_at<=UTC_TIMESTAMP(3)) OR (status='OFFERED' AND offer_expires_at<=UTC_TIMESTAMP(3)) ORDER BY id LIMIT 100",Long.class)){
                try {transactions.executeWithoutResult(tx -> {coordinator.lock();var initial=row(id,false);long gid=number(initial.get("group_id"));coordinator.resources(members(gid));group(gid,true);expire(row(id,true),null);});}
                catch(RuntimeException failure){log.warn("候补到期补偿暂未完成，waitlistId={}，异常类型={}",id,failure.getClass().getSimpleName());}
            }
            var groups=new LinkedHashSet<Long>();
            // 唤醒列表仅辅助低延迟，限制每轮消耗；不同组循环扫描避免前 100 个阻塞组饿死后续组。
            for(long resource:new ArrayList<>(wakeResources).stream().limit(100).toList()){
                if(wakeResources.remove(resource))groups.addAll(sql.queryForList("SELECT DISTINCT w.group_id FROM waitlist_entry w JOIN waitlist_group_resource gr ON gr.group_id=w.group_id WHERE gr.resource_id=? AND w.status='WAITING' LIMIT 100",Long.class,resource));
            }
            var batch=sql.queryForList("SELECT DISTINCT group_id FROM waitlist_entry WHERE status='WAITING' AND group_id>? ORDER BY group_id LIMIT 100",Long.class,scanCursor);
            if(batch.isEmpty()){scanCursor=0;batch=sql.queryForList("SELECT DISTINCT group_id FROM waitlist_entry WHERE status='WAITING' ORDER BY group_id LIMIT 100",Long.class);}
            if(!batch.isEmpty())scanCursor=batch.getLast();groups.addAll(batch);
            for(long gid:groups){try{evaluate(gid);}catch(RuntimeException failure){log.warn("候补评估暂未完成，groupId={}，异常类型={}",gid,failure.getClass().getSimpleName());}}
        } catch(RuntimeException failure){log.warn("候补补偿扫描暂未完成，异常类型={}",failure.getClass().getSimpleName());}
    }
    /** 查询资格后再加锁并重读队首，资格不可用时保持 WAITING，不误认停用或释放他人的资源。 */
    private void evaluate(long gid){
        var candidates=sql.queryForList("SELECT * FROM waitlist_entry WHERE group_id=? AND status='WAITING' ORDER BY created_at,id LIMIT 1",gid);
        if(candidates.isEmpty())return;var candidate=candidates.getFirst();long id=number(candidate.get("id")),uid=number(candidate.get("applicant_id"));
        boolean eligible=applicants.eligibleForBackground(uid);
        transactions.executeWithoutResult(tx -> {
            coordinator.lock();quota.lock(uid);var ids=members(gid);coordinator.resources(ids);var g=group(gid,true);expireGroup(gid,null);
            var heads=sql.queryForList("SELECT * FROM waitlist_entry WHERE group_id=? AND status='WAITING' ORDER BY created_at,id LIMIT 1 FOR UPDATE",gid);
            if(heads.isEmpty()||number(heads.getFirst().get("id"))!=id)return;var entry=heads.getFirst();
            if(!eligible){terminate(entry,"EXPIRED","APPLICANT_INELIGIBLE",null);return;}
            if(sql.queryForObject("SELECT COUNT(*) FROM waitlist_entry WHERE group_id=? AND status='OFFERED' AND offer_expires_at>UTC_TIMESTAMP(3)",Long.class,gid)>0)return;
            var policy=policies.active(g.get("data_mode").toString());if(policy==null)return;
            Instant now=policies.now(),deadline=window(g).start().minusSeconds(policy.settings().bookingLeadMinutes()*60L);
            if(!deadline.isAfter(now)){terminate(entry,"EXPIRED","WAITLIST_DEADLINE_PASSED",null);return;}
            if(quota.active(uid,null)>=policy.settings().maxActiveApplicationsPerUser())return;
            var request=request(entry,g);List<Map<String,Object>> rows;
            try {policies.eligible(window(g),policy,now);lifecycle.expireForResources(ids,null);
                rows=lifecycle.validate(ids,CatalogAccess.id(request.venueId()),request.participantCount(),request.requiredFeatures(),g.get("data_mode").toString(),number(entry.get("approval_group_id")),window(g),policy,null,null,false);
            } catch(BusinessException temporary){return;}
            Instant offer=ApplicationRules.holdUntil(policies.now(),window(g).start(),policy.settings().waitlistOfferTtlMinutes(),policy.settings().bookingLeadMinutes());
            // 队列初始截止不被新策略延长；本轮新 offer 使用当前 TTL，且仍不超过原始截止。
            Instant original=UtcTimes.instant(entry.get("expires_at"));if(original.isBefore(offer))offer=original;
            if(!offer.isAfter(policies.now())){terminate(entry,"EXPIRED","WAITLIST_DEADLINE_PASSED",null);return;}
            for(var resource:rows)sql.update("INSERT INTO resource_occupancy(resource_id,waitlist_entry_id,waitlist_group_id,start_at,end_at,status,expires_at) VALUES (?,?,?,?,?,'HOLD',?)",resource.get("id"),id,gid,Timestamp.from(window(g).start()),Timestamp.from(window(g).end()),Timestamp.from(offer));
            sql.update("UPDATE waitlist_entry SET status='OFFERED',offer_expires_at=?,policy_version=?,policy_snapshot=?,last_reason=NULL,version=version+1 WHERE id=?",Timestamp.from(offer),policy.version(),json.writeValueAsString(policy.settings()),id);
            bump(gid);event(row(id,false),"WAITLIST_OFFERED",null,"WAITING");
        });
    }
}
