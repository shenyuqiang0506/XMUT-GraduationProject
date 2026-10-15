#!/usr/bin/env python3
"""候补与消息验收：真实 dev MySQL/Redis/RabbitMQ/Nacos，使用可清理的独立 DEMO 资料。"""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

spec = importlib.util.spec_from_file_location('availability_probe', Path(__file__).with_name('availability-smoke.py'))
v = importlib.util.module_from_spec(spec); spec.loader.exec_module(v)
s, BASE = v.s, v.BASE

class MessageStack(s.Stack):
    def __enter__(self):
        for port in [8085,18082,18083,18084]:
            with __import__('socket').socket() as probe:
                if probe.connect_ex(('127.0.0.1',port))==0:raise RuntimeError('M4 test port occupied; existing service was not stopped.')
        return super().__enter__()
    def ready(self):
        self.start('notification-service',8085)
        super().ready()
        until=time.monotonic()+60
        while time.monotonic()<until:
            try:
                if s.auth.request('http://127.0.0.1:8085/actuator/health/readiness')[0]==200:return
            except Exception:pass
            time.sleep(.3)
        raise RuntimeError('Notification readiness timeout.')
    def start(self, name, port, overrides=None):
        # 前半段主动触发到期，避免定时器先抢走版本；后半段另起实例验证重复扫描。
        settings = dict(JWT_AUDIENCE='campus-dev-messages-'+self.group,
                        BOOKING_LIFECYCLE_INITIAL_DELAY_MS='3600000')
        settings.update(overrides or {})
        super().start(name, port, settings)

def main():
    prefix = 'msg-'+uuid.uuid4().hex[:10]; source = prefix.replace('-', '_')
    checks, previous = [], []
    private = s.runtime.load_env(s.PRIVATE/'identity.env')
    storage = Path(s.runtime.load_env(s.PRIVATE/'auth.env')['IMPORT_STORAGE_DIR'])
    day = (datetime.now(v.ZONE)+timedelta(days=1)).replace(hour=0, minute=0, second=0, microsecond=0)
    def at(hour): return (day+timedelta(hours=hour)).isoformat(timespec='milliseconds')
    def record(name): checks.append({'check':name, 'result':'PASS'}); print('PASS:', name, flush=True)
    def call(path, method='GET', body=None, token=None, key=None):
        return s.auth.request(BASE+path, method, body, token, prefix+'-request',
                              key or (prefix+'-'+uuid.uuid4().hex) if method!='GET' else None)
    def expect(result, status):
        assert result[0]==status, (result[0], result[1].get('code'), result[1].get('message'))
        return result[1]['data']
    def login(name, password):
        return expect(call('/auth/login', 'POST', {'username':name, 'password':password,
                     'deviceId':prefix+'-'+uuid.uuid4().hex, 'deviceType':'WEB'}),200)['accessToken']
    def coverage(admin, ids, rows=None):
        # 完整空文件只有在显式声明范围、采集时间和 COMPLETE 时才能证明没有来源占用。
        boundary = uuid.uuid4().hex
        context = {'from':at(8), 'to':at(20), 'capturedAt':(datetime.now(v.ZONE)-timedelta(seconds=1)).isoformat(timespec='milliseconds'),
                   'completeness':'COMPLETE', 'sourceDescription':prefix+' DEMO 验收假设', 'resourceIds':ids}
        fields = {'importType':'EXTERNAL_OCCUPANCY', 'sourceSystem':source, 'dataMode':'DEMO',
                  'fieldMapping':'{}', 'queryContext':json.dumps(context)}
        parts = [f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode() for name,value in fields.items()]
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="snapshot.json"\r\nContent-Type: application/json\r\n\r\n'.encode()+json.dumps(rows or []).encode()+b'\r\n')
        parts.append(f'--{boundary}--\r\n'.encode())
        req = urllib.request.Request(BASE+'/admin/imports/preview', data=b''.join(parts), method='POST',
              headers={'Authorization':'Bearer '+admin, 'Content-Type':'multipart/form-data; boundary='+boundary,
                       'Idempotency-Key':prefix+'-'+uuid.uuid4().hex, 'X-Trace-Id':prefix+'-request'})
        try: response=urllib.request.urlopen(req,timeout=30)
        except urllib.error.HTTPError as failure: response=failure
        with response:
            body=json.load(response); assert response.status==201,(response.status,body.get('code'))
        assert body['data']['summary']['errorRows']==0
        return expect(call('/admin/imports/'+body['data']['batchId']+'/commit','POST',{},admin),200)
    def cleanup():
        # 外键逆序清理，限定随机来源/请求前缀；不触碰正式账户、真实资料及 Flyway 记录。
        group_ids=s.sql("SELECT DISTINCT g.id FROM waitlist_group g JOIN waitlist_group_resource gr ON gr.group_id=g.id JOIN resource r ON r.id=gr.resource_id WHERE r.source_system='"+source+"'").splitlines()
        if scope:
            s.sql("DELETE d FROM delivery_attempt d JOIN notification n ON n.id=d.notification_id WHERE n.delivery_scope='"+scope+"';DELETE FROM notification WHERE delivery_scope='"+scope+"';DELETE FROM consumed_event WHERE consumer_name='notification:"+topic.split('.')[1]+"';DELETE FROM message_replay WHERE delivery_scope='"+scope+"';",'notification_db')
            s.sql("DELETE l FROM audit_log l JOIN outbox_event o ON l.object_type='OUTBOX' AND l.object_id=o.id WHERE o.delivery_scope='"+scope+"';DELETE FROM outbox_event WHERE delivery_scope='"+scope+"';")
        s.sql("DELETE l FROM audit_log l JOIN waitlist_entry w ON l.object_type='WAITLIST' AND l.object_id=w.id WHERE w.activity_name LIKE '"+prefix+"%';"
              "DELETE o FROM resource_occupancy o JOIN resource r ON r.id=o.resource_id WHERE r.source_system='"+source+"';"
              "DELETE FROM waitlist_entry WHERE activity_name LIKE '"+prefix+"%';"
              "DELETE gr FROM waitlist_group_resource gr JOIN resource r ON r.id=gr.resource_id WHERE r.source_system='"+source+"';"
              "DELETE FROM waitlist_group WHERE id IN ("+(",".join(group_ids) or "0")+");")
        files=s.sql("SELECT file_storage_key FROM import_batch WHERE source_system='"+source+"'").splitlines()
        s.sql("DELETE o FROM outbox_event o JOIN activity_application a ON o.aggregate_type='APPLICATION' AND o.aggregate_id=a.id WHERE a.activity_name LIKE '"+prefix+"%';"
              "DELETE l FROM audit_log l JOIN activity_application a ON l.object_type='APPLICATION' AND l.object_id=a.id WHERE a.activity_name LIKE '"+prefix+"%';"
              "DELETE r FROM approval_record r JOIN activity_application a ON r.application_id=a.id WHERE a.activity_name LIKE '"+prefix+"%';"
              "DELETE o FROM resource_occupancy o JOIN activity_application a ON o.application_id=a.id WHERE a.activity_name LIKE '"+prefix+"%';"
              "DELETE r FROM application_resource r JOIN activity_application a ON r.application_id=a.id WHERE a.activity_name LIKE '"+prefix+"%';"
              "DELETE FROM activity_application WHERE activity_name LIKE '"+prefix+"%';"
              "DELETE g FROM applicant_guard g LEFT JOIN activity_application a ON a.applicant_id=g.user_id WHERE a.id IS NULL AND g.user_id IN ("+(','.join(users.values()) or '0')+");"
              "DELETE l FROM audit_log l JOIN resource r ON l.object_type='RESOURCE' AND l.object_id=r.id WHERE r.source_system='"+source+"';"
              "DELETE l FROM audit_log l JOIN import_batch b ON l.object_type='IMPORT_BATCH' AND l.object_id=b.id WHERE b.source_system='"+source+"';"
              "DELETE l FROM audit_log l JOIN booking_policy p ON l.object_type='BOOKING_POLICY' AND l.object_id=p.id WHERE p.basis LIKE '"+prefix+"%';"
              "DELETE o FROM external_occupancy o JOIN resource r ON r.id=o.resource_id WHERE r.source_system='"+source+"';"
              "DELETE v FROM availability_coverage v JOIN resource r ON r.id=v.resource_id WHERE r.source_system='"+source+"';"
              "DELETE o FROM resource_opening_rule o JOIN resource r ON r.id=o.resource_id WHERE r.source_system='"+source+"';"
              "DELETE f FROM resource_feature f JOIN resource r ON r.id=f.resource_id WHERE r.source_system='"+source+"';"
              "DELETE e FROM equipment_detail e JOIN resource r ON r.id=e.resource_id WHERE r.source_system='"+source+"';"
              "DELETE m FROM resource_manager m JOIN resource r ON r.id=m.resource_id WHERE r.source_system='"+source+"';"
              "DELETE FROM resource WHERE source_system='"+source+"';"
              "DELETE e FROM import_error e JOIN import_batch b ON b.id=e.batch_id WHERE b.source_system='"+source+"';"
              "DELETE FROM import_batch WHERE source_system='"+source+"';"
              "DELETE m FROM approval_group_member m JOIN approval_group g ON g.id=m.group_id WHERE g.name LIKE '"+prefix+"%';"
              "DELETE FROM approval_group WHERE name LIKE '"+prefix+"%';"
              "DELETE FROM booking_policy WHERE basis LIKE '"+prefix+"%';"
              "DELETE FROM idempotency_record WHERE request_key LIKE '"+prefix+"%';DELETE FROM audit_log WHERE trace_id LIKE '"+prefix+"%';")
        for original in previous:
            s.sql("UPDATE booking_policy p SET active=1 WHERE p.id="+original+" AND NOT EXISTS(SELECT 1 FROM (SELECT scope_code FROM booking_policy WHERE active=1) current_policy WHERE current_policy.scope_code=p.scope_code)")
        for filename in files:
            if filename and '/' not in filename: (storage/filename).unlink(missing_ok=True)
        s.cleanup_identity(prefix)
    scope='';topic=''
    users={}; trigger='message_failure_'+uuid.uuid4().hex[:12]
    broker=s.runtime.load_env(s.PRIVATE/'backend.env')
    import base64,hashlib,hmac
    virtual=urllib.parse.quote(broker['RABBITMQ_VHOST'],safe='')
    def rabbit(path,method='GET',body=None):
        req=urllib.request.Request('http://127.0.0.1:15672/api/'+path,method=method,
            data=json.dumps(body).encode() if body is not None else None,
            headers={'Authorization':'Basic '+base64.b64encode((broker['RABBITMQ_USERNAME']+':'+broker['RABBITMQ_PASSWORD']).encode()).decode(),'Content-Type':'application/json'})
        try:response=urllib.request.urlopen(req,timeout=10)
        except urllib.error.HTTPError as failure:response=failure
        with response:
            raw=response.read();return response.status,json.loads(raw) if raw else None
    def wait(predicate,label,seconds=60):
        until=time.monotonic()+seconds
        while time.monotonic()<until:
            if predicate():return
            time.sleep(.4)
        raise AssertionError(label)
    initialized=False
    try:
        with MessageStack('messages-test') as stack:
            scope='campus-dev-messages-'+stack.group
            topic='campus.'+__import__('hashlib').sha256(scope.encode()).hexdigest()[:20]
            initialized=True
            previous=s.sql('SELECT id FROM booking_policy WHERE active=1 ORDER BY id').splitlines()
            admin=login(private['AUTH_ADMIN_USERNAME'],private['AUTH_ADMIN_PASSWORD'])
            tokens, passwords={}, {}
            for name,role in [('student','STUDENT'),('teacher','TEACHER'),('second','STUDENT'),('manager','RESOURCE_ADMIN'),('other','RESOURCE_ADMIN')]:
                passwords[name]=uuid.uuid4().hex+'Aa1!'
                users[name]=expect(call('/users','POST',{'username':prefix+'_'+name,'displayName':'DEMO '+name,
                         'initialPassword':passwords[name],'roleCodes':[role]},admin),201)['id']
                tokens[name]=login(prefix+'_'+name,passwords[name])
            group=expect(call('/admin/approval-groups','POST',{'name':prefix+'-group'},admin),201)['id']
            expect(call('/admin/approval-groups/'+group+'/members','PUT',{'userIds':[users['manager']]},admin),200)
            resources={}
            for name,kind in [('venue','VENUE'),('venue2','VENUE'),('eq1','EQUIPMENT'),('eq2','EQUIPMENT')]:
                payload={'resourceType':kind,'sourceSystem':source,'sourceId':name,'name':prefix+' 演示 '+name,'campus':prefix,
                         'capacity':40 if kind=='VENUE' else None,'sourceBorrowable':'YES','platformStatus':'OPEN',
                         'approvalGroupId':group,'dataMode':'DEMO','features':{'PROJECTOR':'YES'}}
                if kind=='EQUIPMENT': payload.update(assetCode=prefix+'-'+name,equipmentType='MICROPHONE',conditionStatus='GOOD')
                resources[name]=expect(call('/admin/resources','POST',payload,admin),201)['id']
            ids=list(resources.values()); members=[resources[n] for n in ['venue','eq1','eq2']]
            settings={'pendingApprovalTtlMinutes':60,'waitlistOfferTtlMinutes':15,'bookingLeadMinutes':0,'cancelLeadMinutes':30,
                      'maxBookingDaysAhead':30,'maxDurationMinutes':120,'maxActiveApplicationsPerUser':3,'coverageFreshnessMinutes':120}
            def policy(**changes):
                settings.update(changes)
                version=int(s.sql("SELECT COALESCE(MAX(policy_version),0) FROM booking_policy WHERE scope_code='DEMO'"))
                expect(call('/admin/policies/DEMO/versions','POST',{'expectedVersion':version,'settings':settings,'basis':prefix+' DEMO 验收假设'},admin),201)
            policy()
            rules=[{'weekday':day.isoweekday(),'startLocal':'08:00:00','endLocal':'20:00:00','validFrom':day.date().isoformat(),
                    'validTo':day.date().isoformat(),'enabled':True}]
            for resource in ids:
                expect(call('/admin/resources/'+resource+'/opening-rules','PUT',{'expectedVersion':0,'rules':rules},admin),200)
            coverage(admin,ids)
            def payload(name,hour=9,venue=None,equipment=None):
                return {'activityName':prefix+' '+name,'description':'独立 DEMO 预约验收','participantCount':20,
                        'startAt':at(hour),'endAt':at(hour+1),'venueId':venue or resources['venue'],
                        'equipmentIds':equipment if equipment is not None else [resources['eq1'],resources['eq2']],
                        'requiredFeatures':['PROJECTOR']}
            def create(name,hour=9,actor='student',**kwargs):
                return expect(call('/applications','POST',payload(name,hour,**kwargs),tokens[actor]),201)
            def detail(app,actor='student'):return expect(call('/applications/'+app['applicationId'],token=tokens[actor]),200)
            def action(app,decision,actor='manager',reason='DEMO 验收处理',key=None,version=None):
                path='/applications/' if decision=='cancel' else '/admin/applications/'
                return call(path+app['applicationId']+'/'+decision,'POST',
                            {'expectedVersion':app['version'] if version is None else version,'reason':reason},tokens[actor],key)
            def occupancy(app,status):
                assert s.sql("SELECT COUNT(*) FROM resource_occupancy WHERE application_id="+app['applicationId']+" AND status='"+status+"'")==str(len(app['resourceIds']))
                assert s.sql("SELECT COUNT(*) FROM resource_occupancy WHERE application_id="+app['applicationId'])==str(len(app['resourceIds']))
            def external(name,hour):
                return expect(call('/admin/resources/'+resources['venue']+'/external-occupancies','POST',
                              {'sourceSystem':'MANUAL','sourceKey':prefix+'-'+name,'startAt':at(hour),'endAt':at(hour+1),'reasonCode':'EXAM'},admin),201)['id']
            def remove_external(id,hour):
                expect(call('/admin/external-occupancies/'+id,'PATCH',{'expectedVersion':0,'active':False,
                            'startAt':at(hour),'endAt':at(hour+1),'reasonCode':'EXAM'},admin),200)
            def resolve(app,decision):
                return call('/admin/applications/'+app['applicationId']+'/resolve-conflict','POST',
                            {'expectedVersion':app['version'],'action':decision,'reason':'DEMO 核查来源后处理'},tokens['manager'])
            def wd(entry,actor='teacher'):
                return expect(call('/waitlists/'+entry['waitlistId'],token=tokens[actor]),200)
            def wa(entry,decision,actor='teacher',key=None):
                return call('/waitlists/'+entry['waitlistId']+'/'+decision,'POST',{'expectedVersion':entry['version'],'reason':'DEMO 验收'},tokens[actor],key)
            def wc(name,actor='teacher',hour=9,**kwargs):
                return expect(call('/waitlists','POST',payload(name,hour,**kwargs),tokens[actor]),201)
            def whold(entry,status='HOLD'):
                assert s.sql("SELECT COUNT(*) FROM resource_occupancy WHERE waitlist_entry_id="+entry['waitlistId']+" AND status='"+status+"'")==str(len(entry['resourceIds']))
            def offered(entry,actor='teacher'):
                wait(lambda:wd(entry,actor)['status']=='OFFERED','未获得整组候补资格');return wd(entry,actor)
            def notice_count(event):
                return int(s.sql("SELECT COUNT(*) FROM notification WHERE delivery_scope='"+scope+"' AND event_id='"+event+"'",'notification_db'))
            def publish(body,event):
                status,result=rabbit('exchanges/'+virtual+'/'+topic+'.events/publish','POST',{'routing_key':'changed','payload':body,'payload_encoding':'string','properties':{'message_id':event,'delivery_mode':2,'content_type':'application/json'}})
                assert status==200 and result['routed'], '测试事件未路由'
            # 普通 JWT 无权走服务资格查询；服务签名绑定路径/部署/时间，nonce 只可用一次。
            path='/internal/booking/applicants/'+users['teacher']
            timestamp=str(int(time.time()));nonce=str(uuid.uuid4());secret=(s.PRIVATE/'booking-service-call.key').read_bytes()
            signature=hmac.new(secret,('GET\n'+scope+'\n'+path+'\n'+timestamp+'\n'+nonce).encode(),hashlib.sha256).hexdigest()
            headers={'X-Service-Time':timestamp,'X-Service-Nonce':nonce,'X-Service-Signature':signature}
            def internal(extra=None):
                req=urllib.request.Request('http://127.0.0.1:8083'+path,headers=extra or headers)
                try:response=urllib.request.urlopen(req,timeout=10)
                except urllib.error.HTTPError as failure:response=failure
                with response:return response.status,json.load(response)
            assert internal({'Authorization':'Bearer '+tokens['teacher']})[0]==401
            status,body=internal();assert status==200 and set(body['data'])=={'userId','status','roles'}
            assert internal()[0]==401
            record('后台资格查询使用独立签名，普通 JWT 与签名重放均被拒绝')
            assert call('/waitlists','POST',payload('空闲不能排队'),tokens['teacher'])[1]['code']=='WAITLIST_NOT_NEEDED'
            blocker=create('占用组合')
            body=payload('候补第一');key=prefix+'-waitlist'
            first=expect(call('/waitlists','POST',body,tokens['teacher'],key),201)
            replay=call('/waitlists','POST',dict(body,equipmentIds=list(reversed(body['equipmentIds']))),tokens['teacher'],key)
            assert replay[0]==201 and replay[2]['Idempotency-Replayed']=='true' and replay[1]['data']['waitlistId']==first['waitlistId']
            assert call('/waitlists','POST',payload('重复候补'),tokens['teacher'])[1]['code']=='DUPLICATE_WAITLIST'
            second=wc('候补第二','second')
            assert first['queuePosition']==1 and second['queuePosition']==2
            assert s.sql('SELECT COUNT(*) FROM resource_occupancy WHERE waitlist_entry_id IN ('+first['waitlistId']+','+second['waitlistId']+')')=='0'
            assert call('/waitlists/'+first['waitlistId'],token=tokens['second'])[0]==404
            assert wa(first,'confirm')[0]==409
            assert call('/admin/outbox',token=tokens['teacher'])[0]==403
            assert call('/admin/notifications/dead-letters',token=tokens['manager'])[0]==403
            record('固定组合规范化幂等、禁止重复候补、WAITING 零占用与本人权限隔离')
            # 无后台提升时释放，再提交相同组合仍被队列优先保护。
            expect(action(blocker,'cancel','student'),200)
            assert call('/applications','POST',payload('插队'),tokens['student'])[1]['code']=='WAITLIST_PRIORITY'
            policy(maxActiveApplicationsPerUser=1)
            # 两个候补/发布实例并行扫描，使用相同独立 scope，不领取日常 dev 事件。
            for port in [18082,18083]:
                stack.start('booking-service',port,{'WAITLIST_SCHEDULING_ENABLED':'true','WAITLIST_INITIAL_DELAY_MS':'1000',
                    'BOOKING_OUTBOX_ENABLED':'true','OUTBOX_INITIAL_DELAY_MS':'1000','OUTBOX_DELAY_MS':'200',
                    'OUTBOX_MAX_ATTEMPTS':'2','OUTBOX_RETRY_BASE_MS':'100','OUTBOX_CONFIRM_TIMEOUT_MS':'500'})
            first=offered(first);whold(first)
            assert wd(second,'second')['status']=='WAITING'
            assert call('/applications','POST',payload('候补占额度',11),tokens['teacher'])[1]['code']=='APPLICATION_LIMIT_REACHED'
            calendar='/resources/'+resources['venue']+'/calendar?'+urllib.parse.urlencode({'from':at(8),'to':at(20)})
            intervals=expect(call(calendar,token=tokens['second']),200)['intervals']
            assert any(i['type']=='WAITLIST_HOLD' for i in intervals) and all(set(i)=={'startAt','endAt','type'} for i in intervals)
            record('两实例提升队首整组 HOLD，直接申请不可插队，候补暂占计入配额且日历隐藏身份')
            # 两个不同幂等键同时确认同一版本只能成功一次；确认排除自己的 offer 配额。
            with ThreadPoolExecutor(max_workers=2) as pool:
                results=list(pool.map(lambda _:wa(first,'confirm'),range(2)))
            assert sorted(r[0] for r in results)==[200,409]
            confirmed=next(r[1]['data'] for r in results if r[0]==200)
            assert confirmed['status']=='CONFIRMED' and confirmed['applicationId']
            app=expect(call('/applications/'+confirmed['applicationId'],token=tokens['teacher']),200)
            assert app['status']=='PENDING_APPROVAL';occupancy(app,'HOLD')
            assert s.sql('SELECT COUNT(*) FROM resource_occupancy WHERE waitlist_entry_id='+first['waitlistId'])=='0'
            assert wd(second,'second')['status']=='WAITING'
            expect(action(app,'cancel','teacher'),200)
            second=offered(second,'second');whold(second)
            record('并发确认只生成一个待审批申请，原子转交占用，释放后下一位获得整组资格')
            # 主动缩短本轮 DEMO offer，验证重启补偿扫描与整组释放。
            s.sql("UPDATE waitlist_entry SET offer_expires_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE id="+second['waitlistId']+";UPDATE resource_occupancy SET expires_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE waitlist_entry_id="+second['waitlistId'])
            wait(lambda:wd(second,'second')['status']=='EXPIRED','候补到期未释放')
            whold(second,'RELEASED')
            record('候补到期扫描整组释放并保留过期事件')
            blocker=create('来源变更占用',11)
            entry=wc('来源变更候补',hour=11)
            expect(action(blocker,'cancel','student'),200);entry=offered(entry)
            conflict=external('候补来源冲突',11)
            wait(lambda:wd(entry)['status']=='EXPIRED','来源变更未撤销 offer');whold(entry,'RELEASED')
            assert wd(entry)['lastReason'].startswith('SOURCE_CONFLICT:')
            remove_external(conflict,11)
            record('来源新增冲突在同一写入事务撤销整组候补机会')
            # 失去资格的队首永久过期，后一位才有机会；临时配额不足不能跳过队首。
            blocker=create('失去资格占用',15)
            inactive=wc('停用队首','second',15);eligible=wc('有效下一位','teacher',15)
            expect(call('/users/'+users['second']+'/status','PATCH',{'status':'DISABLED','expectedVersion':0},admin),200)
            expect(action(blocker,'cancel','student'),200);eligible=offered(eligible)
            assert s.sql('SELECT status FROM waitlist_entry WHERE id='+inactive['waitlistId'])=='EXPIRED'
            expect(wa(eligible,'cancel'),200)
            expect(call('/users/'+users['second']+'/status','PATCH',{'status':'ACTIVE','expectedVersion':1},admin),200)
            tokens['second']=login(prefix+'_second',passwords['second'])
            record('停用队首按实时服务资格检查过期，后续合格用户继续排队')
            occupying=external('配额阻挡',16)
            quota_app=create('临时额度已用',18,actor='teacher')
            quota_head=wc('配额队首','teacher',16);quota_next=wc('不得越过队首','second',16)
            remove_external(occupying,16);time.sleep(3)
            assert wd(quota_head)['status']=='WAITING' and wd(quota_next,'second')['status']=='WAITING'
            expect(action(quota_app,'cancel','teacher'),200);quota_head=offered(quota_head)
            policy(maxActiveApplicationsPerUser=2)
            changed_quota=create('确认前配额变化',18,actor='teacher')
            policy(maxActiveApplicationsPerUser=1)
            assert wa(quota_head,'confirm')[1]['code']=='APPLICATION_LIMIT_REACHED'
            assert wd(quota_head)['status']=='OFFERED';whold(quota_head)
            expect(action(changed_quota,'cancel','teacher'),200)
            # 确认和取消同版本竞争只能有一种结果，无额外有效占用或申请。
            with ThreadPoolExecutor(max_workers=2) as pool:
                futures=[pool.submit(wa,quota_head,decision) for decision in ['confirm','cancel']]
                race=[f.result() for f in futures]
            assert sorted(r[0] for r in race)==[200,409]
            current=wd(quota_head)
            if current['status']=='CONFIRMED':
                transferred=expect(call('/applications/'+current['applicationId'],token=tokens['teacher']),200)
                expect(action(transferred,'cancel','teacher'),200)
            else:assert current['status']=='CANCELLED';whold(current,'RELEASED')
            quota_next=offered(quota_next,'second');expect(wa(quota_next,'cancel','second'),200)
            record('临时配额不足不越过队首，确认重新检查配额，确认与取消竞争仅一个结果')
            # 来源覆盖未知即便已有 BUSY 也不能进入候补；排队不是绕过可信覆盖的捷径。
            occupying=external('覆盖缺失',17)
            s.sql('UPDATE availability_coverage SET active=0 WHERE resource_id='+resources['eq2'])
            try:assert call('/waitlists','POST',payload('覆盖未知候补',17),tokens['teacher'])[1]['code']=='COVERAGE_UNKNOWN'
            finally:s.sql('UPDATE availability_coverage SET active=1 WHERE resource_id='+resources['eq2'])
            timed=wc('超时确认候补',hour=17);remove_external(occupying,17);timed=offered(timed)
            s.sql("UPDATE waitlist_entry SET offer_expires_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE id="+timed['waitlistId']+";UPDATE resource_occupancy SET expires_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE waitlist_entry_id="+timed['waitlistId'])
            assert wa(timed,'confirm')[0]==409
            wait(lambda:wd(timed)['status']=='EXPIRED','已过期候补未终止');whold(timed,'RELEASED')
            assert s.sql("SELECT COUNT(*) FROM activity_application WHERE activity_name='"+prefix+" 超时确认候补'")=='0'
            record('覆盖未知不允许排队，过期与确认竞争不得生成新申请且全组释放')
            # 等待当前所有真实 Outbox 已获确认，并已消费：分别断言两种状态。
            wait(lambda:s.sql("SELECT COUNT(*) FROM outbox_event WHERE delivery_scope='"+scope+"' AND status<>'SENT'")=='0','Outbox 未投递完成')
            wait(lambda:s.sql("SELECT COUNT(*) FROM outbox_event WHERE delivery_scope='"+scope+"'")==s.sql("SELECT COUNT(*) FROM consumed_event WHERE consumer_name='notification:"+topic.split('.')[1]+"' AND processed_at IS NOT NULL",'notification_db'),'通知未提交')
            event=s.sql("SELECT event_id FROM outbox_event WHERE delivery_scope='"+scope+"' AND event_type='WAITLIST_CONFIRMED' LIMIT 1")
            raw=s.sql("SELECT payload FROM outbox_event WHERE event_id='"+event+"'");before=notice_count(event)
            publish(raw,event);publish(raw,event);time.sleep(1)
            assert notice_count(event)==before==1
            record('双发布实例获 broker 确认，通知同事务去重，重复事件不重复生成通知')
            # 模拟认领后进程退出的数据库现场，其他实例在租约过期后恢复；重复消费仍只有一份。
            s.sql("UPDATE outbox_event SET status='SENDING',attempts=0,claim_token='"+str(uuid.uuid4())+"',claim_expires_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE event_id='"+event+"'")
            wait(lambda:s.sql("SELECT status FROM outbox_event WHERE event_id='"+event+"'")=='SENT','过期发布租约未恢复')
            time.sleep(.5);assert notice_count(event)==1
            record('认领进程中断的过期 SENDING 租约由其他实例恢复，去重不增加通知')
            # 缓存只保存目录展示，腐坏、旧版本、写入失效均回数据库；真实可用性没有缓存输入。
            redis_source=s.runtime.load_env(s.REPO/'deployment/.env.dev')
            def redis(*args):
                import socket
                commands=[['AUTH',redis_source['REDIS_PASSWORD']],list(args)]
                with socket.create_connection(('127.0.0.1',6379),timeout=5) as connection:
                    stream=connection.makefile('rb')
                    replies=[]
                    for command in commands:
                        parts=[str(item).encode() for item in command]
                        connection.sendall(b'*'+str(len(parts)).encode()+b'\r\n'+b''.join(b'$'+str(len(part)).encode()+b'\r\n'+part+b'\r\n' for part in parts))
                        line=stream.readline();kind=line[:1];value=line[1:-2]
                        if kind==b'$':
                            count=int(value);value=stream.read(count)+stream.read(2) if count>=0 else None
                            if value is not None:value=value[:-2].decode()
                        elif kind==b':':value=int(value)
                        elif kind==b'-':raise RuntimeError('测试 Redis 命令失败')
                        else:value=value.decode()
                        replies.append(value)
                    return replies[-1]
            cache_key='campus:catalog:'+hashlib.sha256(scope.encode()).hexdigest()+':'+resources['venue']
            resource=expect(call('/resources/'+resources['venue'],token=tokens['teacher']),200)
            assert redis('TTL',cache_key)>0
            redis('SET',cache_key,'invalid-json','EX',30)
            assert expect(call('/resources/'+resources['venue'],token=tokens['teacher']),200)['name']==resource['name']
            expect(call('/admin/resources/'+resources['venue'],'PATCH',{'expectedVersion':resource['version'],'name':prefix+' 演示已更新'},admin),200)
            updated=expect(call('/resources/'+resources['venue'],token=tokens['teacher']),200)
            assert updated['name']==prefix+' 演示已更新' and updated['version']==resource['version']+1
            redis('SET',cache_key,json.dumps({'version':resource['version'],'value':dict(resource,name='DEMO 旧值')}),'EX',30)
            assert expect(call('/resources/'+resources['venue'],token=tokens['teacher']),200)['name']==updated['name']
            redis('DEL',cache_key)
            record('目录缓存有 TTL，腐坏与旧版本回源，资源提交后失效；可用性仍查 MySQL')
            # 仅该测试实例的 Redis 指向不可用端口，身份服务/网关的认证 Redis 仍正常。
            # 缓存故障与认证故障的语义不同；前者回源，后者保持拒绝。
            stack.start('booking-service',18084,{'REDIS_PORT':'1','SPRING_CLOUD_NACOS_DISCOVERY_REGISTER_ENABLED':'false'})
            def listening():
                import socket
                with socket.socket() as probe:return probe.connect_ex(('127.0.0.1',18084))==0
            wait(listening,'缓存降级实例未启动');time.sleep(2)
            direct=lambda path,method='GET',body=None:s.auth.request('http://127.0.0.1:18084'+path,method,body,tokens['teacher'],prefix+'-cache',prefix+'-'+uuid.uuid4().hex if method!='GET' else None)
            degraded=expect(direct('/resources/'+resources['venue']),200)
            assert degraded['name']==updated['name']
            cache_app=expect(direct('/applications','POST',payload('缓存失效仍预约',19)),201)
            assert cache_app['status']=='PENDING_APPROVAL';occupancy(cache_app,'HOLD')
            expect(direct('/applications/'+cache_app['applicationId']+'/cancel','POST',{'expectedVersion':cache_app['version'],'reason':'DEMO 缓存降级'}),200)
            record('目录 Redis 不可用时读取和预约继续由 MySQL 校验，整组占用不依赖缓存')


            notices=expect(call('/notifications',token=tokens['teacher']),200)
            notice=notices['items'][0];nid=notice['notificationId']
            assert notices['unreadCount']>0
            assert call('/notifications/'+nid+'/read','POST',{},tokens['second'])[0]==404
            read=expect(call('/notifications/'+nid+'/read','POST',{},tokens['teacher']),200)
            assert read['readAt']==expect(call('/notifications/'+nid+'/read','POST',{},tokens['teacher']),200)['readAt']
            assert call('/notifications',token=tokens['teacher'])[2]['Cache-Control']=='no-store'
            record('本人收件箱与跨账号隔离，重复标记已读保留原时间')
            # 复用 eventId 但篡改内容是毒消息，不能与已消费内容混为同一业务事件。
            changed=json.loads(raw);changed['payload']['reason']='DEMO 内容冲突';publish(json.dumps(changed),event)
            wait(lambda:expect(call('/admin/notifications/dead-letters',token=admin),200)['readyCount']>=1,'冲突载荷未进死信')
            assert notice_count(event)==1
            record('相同事件编号不同内容拒绝消费，有限重试后留存死信')
            # 一个收件人写入失败使 consumed_event/notification 整个事务回滚，随后可人工重放。
            s.sql("DELIMITER $$\nCREATE TRIGGER "+trigger+" BEFORE INSERT ON notification FOR EACH ROW BEGIN IF NEW.recipient_id="+users['teacher']+" AND NEW.kind='APPLICATION_SUBMITTED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='DEMO notification failure'; END IF; END$$\nDELIMITER ;\n",'notification_db')
            fault=create('消费失败死信',13,actor='teacher')
            fault_event=s.sql("SELECT event_id FROM outbox_event WHERE aggregate_type='APPLICATION' AND aggregate_id="+fault['applicationId']+" AND event_type='APPLICATION_SUBMITTED'")
            wait(lambda:any(i['eventId']==fault_event for i in expect(call('/admin/notifications/dead-letters',token=admin),200)['items']),'消费失败未进死信')
            assert notice_count(fault_event)==0
            assert s.sql("SELECT COUNT(*) FROM consumed_event WHERE event_id='"+fault_event+"'",'notification_db')=='0'
            s.sql('DROP TRIGGER '+trigger,'notification_db')
            key=prefix+'-dead-replay';body={'reason':'DEMO 已修复数据库故障'}
            path='/admin/notifications/dead-letters/'+fault_event+'/replay'
            result=expect(call(path,'POST',body,admin,key),200);assert result['status']=='REPUBLISHED'
            assert call(path,'POST',body,admin,key)[2]['Idempotency-Replayed']=='true'
            assert call(path,'POST',{'reason':'different'},admin,key)[0]==409
            wait(lambda:notice_count(fault_event)==2,'死信重放未生成申请人与审批人通知')
            expect(action(fault,'cancel','teacher'),200)
            record('消费数据库故障完整回滚，修复后死信重放和 HTTP 幂等恢复，不丢收件人')
            # 暂时移除本测试 scope 路由，验证 mandatory return 不被标 SENT；不影响其他环境队列。
            binding='bindings/'+virtual+'/e/'+topic+'.events/q/'+topic+'.notifications/changed'
            assert rabbit(binding,'DELETE')[0]==204
            try:
                failed=create('发布未路由',14,actor='teacher')
                oid=s.sql("SELECT id FROM outbox_event WHERE aggregate_type='APPLICATION' AND aggregate_id="+failed['applicationId']+" AND event_type='APPLICATION_SUBMITTED'")
                wait(lambda:s.sql('SELECT status FROM outbox_event WHERE id='+oid)=='FAILED','未路由发布未进入有限失败状态')
                assert s.sql('SELECT attempts FROM outbox_event WHERE id='+oid)=='2'
                wait(lambda:s.sql('SELECT dead_lettered_at IS NOT NULL FROM outbox_event WHERE id='+oid)=='1','失败事件未存入死信')
                assert expect(call('/applications/'+failed['applicationId'],token=tokens['teacher']),200)['status']=='PENDING_APPROVAL'
            finally:
                assert rabbit('bindings/'+virtual+'/e/'+topic+'.events/q/'+topic+'.notifications','POST',{'routing_key':'changed','arguments':{}})[0]==201
            row=next(i for i in expect(call('/admin/outbox?status=FAILED',token=admin),200)['items'] if i['id']==oid)
            retry_body={'expectedVersion':row['version'],'reason':'DEMO 已恢复测试路由'};key=prefix+'-retry'
            expect(call('/admin/outbox/'+oid+'/retry','POST',retry_body,admin,key),200)
            assert call('/admin/outbox/'+oid+'/retry','POST',retry_body,admin,key)[2]['Idempotency-Replayed']=='true'
            wait(lambda:s.sql('SELECT status FROM outbox_event WHERE id='+oid)=='SENT','人工重新投递未成功')
            expect(action(failed,'cancel','teacher'),200)
            record('未路由发布有限失败并留存死信，业务写入不回滚，恢复路由后人工幂等重试')
            # RabbitMQ 暂停是明确的 dev 故障注入；不删除卷，不操作 prod，finally 总会恢复。
            subprocess.run([str(s.REPO/'deployment/scripts/compose.sh'),'dev','pause','rabbitmq'],capture_output=True,check=True)
            try:
                broker_fault=create('消息服务不可用仍提交',19,actor='teacher')
                broker_oid=s.sql("SELECT id FROM outbox_event WHERE aggregate_type='APPLICATION' AND aggregate_id="+broker_fault['applicationId']+" AND event_type='APPLICATION_SUBMITTED'")
                wait(lambda:s.sql('SELECT status FROM outbox_event WHERE id='+broker_oid)=='FAILED','RabbitMQ 故障未进入有限重试终态',30)
                assert expect(call('/applications/'+broker_fault['applicationId'],token=tokens['teacher']),200)['status']=='PENDING_APPROVAL'
                occupancy(broker_fault,'HOLD')
            finally:
                subprocess.run([str(s.REPO/'deployment/scripts/compose.sh'),'dev','unpause','rabbitmq'],capture_output=True,check=True)
            wait(lambda:s.sql('SELECT dead_lettered_at IS NOT NULL FROM outbox_event WHERE id='+broker_oid)=='1','消息服务恢复后未完成失败留存')
            broker_row=next(i for i in expect(call('/admin/outbox?status=FAILED',token=admin),200)['items'] if i['id']==broker_oid)
            expect(call('/admin/outbox/'+broker_oid+'/retry','POST',{'expectedVersion':broker_row['version'],'reason':'DEMO RabbitMQ 已恢复'},admin),200)
            wait(lambda:s.sql('SELECT status FROM outbox_event WHERE id='+broker_oid)=='SENT','消息服务恢复后重试失败')
            broker_event=s.sql('SELECT event_id FROM outbox_event WHERE id='+broker_oid)
            wait(lambda:notice_count(broker_event)==2,'消息服务恢复后通知未完成')
            expect(action(broker_fault,'cancel','teacher'),200)
            record('真实 RabbitMQ 暂不可用不回滚预约，有限重试保留失败事件，恢复后重投并去重')
            # 已确认或取消的状态不会被晚到历史消息改变。
            publish(raw,event);time.sleep(.5)
            assert wd(confirmed)['status']=='CONFIRMED' and expect(call('/applications/'+app['applicationId'],token=tokens['teacher']),200)['status']=='CANCELLED'
            record('晚到事件只保留通知历史，不回退当前预约与候补状态')
            if os.environ.get('XMUT_WEB_TEST')=='1':
                web_blocker=create('浏览器候补占用',18)
                env=os.environ.copy();env.update(TEST_MESSAGES_PREFIX=prefix,TEST_MESSAGES_TEACHER=prefix+'_teacher',
                    TEST_MESSAGES_TEACHER_PASSWORD=passwords['teacher'],TEST_MESSAGES_WAITLIST_ID=confirmed['waitlistId'],
                    TEST_MESSAGES_VENUE=prefix+' 演示已更新',TEST_MESSAGES_EQ1=prefix+' 演示 eq1',
                    TEST_MESSAGES_START=(day+timedelta(hours=18)).strftime('%Y-%m-%dT%H:%M'),TEST_MESSAGES_END=(day+timedelta(hours=19)).strftime('%Y-%m-%dT%H:%M'),
                    TEST_MESSAGES_STUDENT=prefix+'_student',TEST_MESSAGES_STUDENT_PASSWORD=passwords['student'],TEST_MESSAGES_BLOCKER=web_blocker['applicationId'])
                env['VITE_TEST_DATA_MODE']='DEMO'
                fd=os.open(s.PRIVATE/'messages-web-test.log',os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
                with os.fdopen(fd,'w') as log:result=subprocess.run(['npm','test','--','tests/messages.spec.ts'],cwd=s.REPO/'web-admin',env=env,stdout=log,stderr=subprocess.STDOUT)
                assert result.returncode==0,'M4 浏览器验收失败，请检查私密 messages-web-test.log'
                record('真实浏览器完成本人候补详情、关联申请、通知已读与权限导航')
            destination=s.auth.REPORT_DIR;destination.mkdir(parents=True,exist_ok=True)
            (destination/'waitlists-messaging.json').write_text(json.dumps({'date':s.auth.REPORT_DATE,'mode':'dev-real-mysql-redis-rabbitmq-nacos',
                'checks':checks,'passed':len(checks),'failed':0,'scope':'M4 固定组合候补、站内通知及投递恢复；不含外部推送、移动端或线上部署'},ensure_ascii=False,indent=2)+'\n')
    finally:
        if initialized:
            s.sql('DROP TRIGGER IF EXISTS '+trigger,'notification_db')
            cleanup()
            if topic:
                for queue in [topic+'.notifications',topic+'.dead.notifications']:rabbit('queues/'+virtual+'/'+queue,'DELETE')
                for exchange in [topic+'.events',topic+'.dead']:rabbit('exchanges/'+virtual+'/'+exchange,'DELETE')

if __name__=='__main__':
    try:main()
    except (AssertionError,RuntimeError) as failure:
        import traceback
        line=traceback.extract_tb(failure.__traceback__)[-1].lineno
        raise SystemExit(f'候补与消息验收在第 {line} 行失败：{str(failure) or "请检查私密日志"}')
