#!/usr/bin/env python3
"""联合预约闭环验收：真实 dev MySQL/Redis/Nacos，使用可清理的独立 DEMO 资料。"""
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

class ApplicationStack(s.Stack):
    def start(self, name, port, overrides=None):
        # 前半段主动触发到期，避免定时器先抢走版本；后半段另起实例验证重复扫描。
        settings = dict(JWT_AUDIENCE='campus-dev-applications-'+self.group,
                        BOOKING_LIFECYCLE_INITIAL_DELAY_MS='3600000')
        settings.update(overrides or {})
        super().start(name, port, settings)

def main():
    prefix = 'app-'+uuid.uuid4().hex[:10]; source = prefix.replace('-', '_')
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
    users={}; trigger='app_failure_'+uuid.uuid4().hex[:12]
    initialized=False
    try:
        with ApplicationStack('applications-test') as stack:
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
            key=prefix+'-submit'; body=payload('提交及幂等')
            app=expect(call('/applications','POST',body,tokens['student'],key),201)
            occupancy(app,'HOLD');assert app['status']=='PENDING_APPROVAL' and app['version']==0
            replay=call('/applications','POST',dict(body,equipmentIds=list(reversed(body['equipmentIds']))),tokens['student'],key)
            assert replay[0]==201 and replay[2]['Idempotency-Replayed']=='true' and replay[1]['data']['applicationId']==app['applicationId']
            assert call('/applications','POST',dict(body,activityName=prefix+' changed'),tokens['student'],key)[0]==409
            assert call('/applications','POST',dict(body,applicantId=users['second']),tokens['student'])[0]==400
            assert call('/applications','POST',body,admin)[0]==403
            assert call('/applications','POST',dict(body,equipmentIds=[resources['eq1'],resources['eq1']]),tokens['student'])[0]==400
            record('学生联合提交全组 HOLD，规范化幂等重放不重复写入，拒绝伪造身份和重复成员')
            assert call('/applications/'+app['applicationId'],token=tokens['student'])[2]['Cache-Control']=='no-store'
            assert call('/applications/'+app['applicationId'],token=tokens['second'])[0]==404
            assert action(app,'approve','teacher')[0]==404
            assert action(app,'approve','other')[0]==404
            assert call('/admin/applications',token=tokens['student'])[0]==403
            assert expect(call('/admin/applications',token=tokens['other']),200)['total']==0
            assert expect(call('/admin/applications',token=tokens['manager']),200)['total']==1
            assert call('/applications','POST',payload('设备冲突回滚',venue=resources['venue2']),tokens['second'])[0]==409
            assert s.sql("SELECT COUNT(*) FROM activity_application WHERE activity_name='"+prefix+" 设备冲突回滚'")=='0'
            adjacent=create('相接区间',10,actor='second')
            expect(action(adjacent,'cancel','second'),200)
            record('本人详情与审批组范围隔离，教师不会自动取得审批权')
            record('场地空闲但设备冲突时整组拒绝；相接时段可以创建独立申请')
            search={'campus':prefix,'startAt':at(9),'endAt':at(10),'participantCount':20,'requiredFeatures':['PROJECTOR'],
                    'equipmentRequirements':[{'type':'MICROPHONE','count':2}],'dataMode':'DEMO','page':1,'size':20}
            assert expect(call('/availability/search','POST',search,tokens['student']),200)['total']==0
            calendar='/resources/'+resources['venue']+'/calendar?'+urllib.parse.urlencode({'from':at(8),'to':at(20)})
            intervals=expect(call(calendar,token=tokens['second']),200)['intervals']
            assert any(i['type']=='HOLD' for i in intervals) and all(set(i)=={'startAt','endAt','type'} for i in intervals)
            approved=expect(action(app,'approve',key=prefix+'-approve'),200);occupancy(approved,'CONFIRMED')
            assert action(app,'approve',key=prefix+'-approve')[2]['Idempotency-Replayed']=='true'
            assert action(app,'reject')[0]==409
            cancelled=expect(action(approved,'cancel','student'),200);occupancy(cancelled,'RELEASED')
            assert expect(call('/availability/search','POST',search,tokens['student']),200)['total']==2
            record('批准全组 CONFIRMED，取消全组 RELEASED，平台占用参与查询且日历隐藏申请信息')
            teacher=create('教师工号预约',actor='teacher');assert teacher['applicant']['userId']==users['teacher']
            assert action(teacher,'reject',reason=' ')[0]==400
            teacher=expect(action(teacher,'reject',reason='DEMO 条件不满足'),200);occupancy(teacher,'RELEASED')
            record('教师可提交联合申请，驳回必须有理由并释放全组')
            # 独立用户争抢同一组合，获胜者之外不能留下申请、占用或事件。
            with ThreadPoolExecutor(max_workers=2) as pool:
                results=list(pool.map(lambda actor:call('/applications','POST',payload('资源竞争 '+actor),tokens[actor]),['student','second']))
            assert sorted(r[0] for r in results)==[201,409]
            winner=next(r[1]['data'] for r in results if r[0]==201);occupancy(winner,'HOLD')
            expect(action(winner,'cancel'),200)
            record('两个用户争抢同一场地和设备，只能一笔成功，整组无重复占用')
            policy(maxActiveApplicationsPerUser=1)
            with ThreadPoolExecutor(max_workers=2) as pool:
                results=list(pool.map(lambda n:call('/applications','POST',payload('配额竞争 '+n,venue=resources[n],equipment=[]),tokens['student']),['venue','venue2']))
            assert sorted(r[0] for r in results)==[201,422]
            assert next(r[1]['code'] for r in results if r[0]==422)=='APPLICATION_LIMIT_REACHED'
            expect(action(next(r[1]['data'] for r in results if r[0]==201),'cancel'),200)
            policy(maxActiveApplicationsPerUser=3)
            record('同一用户并发提交不同资源，稳定守卫仍严格执行有效申请额度')
            # 在第二个成员写入时注入局部故障，验证已写第一成员及申请/事件/幂等记录全部回滚。
            s.sql("DELIMITER //\nCREATE TRIGGER "+trigger+" BEFORE INSERT ON resource_occupancy FOR EACH ROW BEGIN IF NEW.resource_id="+resources['eq2']+" THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='DEMO scoped test fault'; END IF; END//\nDELIMITER ;")
            before_members=s.sql('SELECT COUNT(*) FROM application_resource')
            before_occupancy=s.sql('SELECT COUNT(*) FROM resource_occupancy')
            before_events=s.sql('SELECT COUNT(*) FROM outbox_event')
            try:
                assert call('/applications','POST',payload('事务回滚'),tokens['student'],prefix+'-rollback')[0]==500
                assert s.sql("SELECT COUNT(*) FROM activity_application WHERE activity_name='"+prefix+" 事务回滚'")=='0'
                assert s.sql("SELECT COUNT(*) FROM idempotency_record WHERE request_key='"+prefix+"-rollback'")=='0'
                assert s.sql('SELECT COUNT(*) FROM application_resource')==before_members
                assert s.sql('SELECT COUNT(*) FROM resource_occupancy')==before_occupancy
                assert s.sql('SELECT COUNT(*) FROM outbox_event')==before_events
            finally:s.sql('DROP TRIGGER IF EXISTS '+trigger)
            record('第二个成员写入失败时，申请、前序占用、事件和幂等记录整笔回滚')
            race=create('审批取消竞争')
            with ThreadPoolExecutor(max_workers=2) as pool:
                futures=[pool.submit(action,race,'approve'),pool.submit(action,race,'cancel','student')]
                results=[f.result() for f in futures]
            assert sorted(r[0] for r in results)==[200,409]
            current=detail(race);assert current['version']==1
            occupancy(current,'CONFIRMED' if current['status']=='APPROVED' else 'RELEASED')
            assert len(current['approvalRecords'])==1
            if current['status']=='APPROVED':expect(action(current,'cancel','student'),200)
            record('审批与取消使用同一版本竞争，只允许一个状态变化及一条审批历史')
            pending=create('来源变化驳回',11);ex=external('pending',11)
            rejected=detail(pending);assert rejected['status']=='REJECTED';occupancy(rejected,'RELEASED');remove_external(ex,11)
            approved=expect(action(create('批准后来源复核',13),'approve'),200)
            # 来源写入成功但关联状态变化失败，也必须一起回滚，不能隐瞒已批准的来源冲突。
            s.sql("DELIMITER //\nCREATE TRIGGER "+trigger+" BEFORE INSERT ON approval_record FOR EACH ROW BEGIN IF NEW.application_id="+approved['applicationId']+" THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='DEMO source transition fault'; END IF; END//\nDELIMITER ;")
            try:
                failed=call('/admin/resources/'+resources['venue']+'/external-occupancies','POST',{'sourceSystem':'MANUAL','sourceKey':prefix+'-atomic-source','startAt':at(13),'endAt':at(14),'reasonCode':'EXAM'},admin)
                assert failed[0]==500
                assert s.sql("SELECT COUNT(*) FROM external_occupancy WHERE source_key='"+prefix+"-atomic-source'")=='0'
                assert detail(approved)['status']=='APPROVED';occupancy(approved,'CONFIRMED')
                assert s.sql('SELECT COUNT(*) FROM approval_record WHERE application_id='+approved['applicationId'])=='1'
                assert s.sql('SELECT COUNT(*) FROM outbox_event WHERE aggregate_id='+approved['applicationId']+" AND aggregate_type='APPLICATION'")=='2'
            finally:s.sql('DROP TRIGGER IF EXISTS '+trigger)
            record('来源变更的关联状态转换失败时，来源占用、复核历史与事件共同回滚')
            ex=external('approved',13)
            conflict=detail(approved);assert conflict['status']=='CONFLICT_REVIEW';occupancy(conflict,'CONFIRMED')
            assert resolve(conflict,'REVALIDATE')[0]==409
            remove_external(ex,13);assert detail(conflict)['status']=='CONFLICT_REVIEW'
            restored=expect(resolve(conflict,'REVALIDATE'),200);assert restored['status']=='APPROVED';occupancy(restored,'CONFIRMED')
            expect(action(restored,'cancel','student'),200)
            record('外部冲突驳回待审批整组；已批准保留占用并转复核，来源清除后仅显式复核可恢复')
            app=expect(action(create('导入冲突复核',15),'approve'),200)
            imported=coverage(admin,[resources['venue']],[{'sourceId':'venue','sourceKey':'import-conflict','startAt':at(15),'endAt':at(16),'reasonCode':'COURSE'}])
            assert imported['affectedApplications']['conflictReview']==1,imported.keys()
            conflict=detail(app);assert conflict['status']=='CONFLICT_REVIEW';occupancy(conflict,'CONFIRMED')
            cancelled=expect(resolve(conflict,'CANCEL'),200);occupancy(cancelled,'RELEASED');coverage(admin,ids)
            record('整批来源导入与申请复核同事务，响应包含影响数量，冲突处置可取消整组')
            pending=create('开放规则变化',11)
            version=expect(call('/resources/'+resources['eq2'],token=admin),200)['version']
            expect(call('/admin/resources/'+resources['eq2']+'/opening-rules','PUT',{'expectedVersion':version,'rules':[]},admin),200)
            assert detail(pending)['status']=='REJECTED';occupancy(pending,'RELEASED')
            expect(call('/admin/resources/'+resources['eq2']+'/opening-rules','PUT',{'expectedVersion':version+1,'rules':rules},admin),200)
            record('任一设备开放规则变化触发整组重新校验，不保留失效的待审批暂占')
            app=create('主动到期释放',11)
            s.sql("UPDATE activity_application SET hold_expires_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE id="+app['applicationId']+";UPDATE resource_occupancy SET expires_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE application_id="+app['applicationId'])
            assert expect(call('/availability/search','POST',dict(search,startAt=at(11),endAt=at(12)),tokens['student']),200)['total']==2
            expired=action(app,'approve',key=prefix+'-expired-action')
            assert expired[0]==409 and expired[1]['code']=='APPROVAL_HOLD_EXPIRED'
            assert action(app,'approve',key=prefix+'-expired-action')[2]['Idempotency-Replayed']=='true'
            assert detail(app)['status']=='EXPIRED';occupancy(app,'RELEASED')
            record('到期审批返回 409 同时提交 EXPIRED 和释放，错误结果也能幂等重放')
            multiple=create('多个管理员竞争',11)
            with ThreadPoolExecutor(max_workers=2) as pool:
                futures=[pool.submit(action,multiple,'approve'),pool.submit(call,'/admin/applications/'+multiple['applicationId']+'/approve','POST',{'expectedVersion':0,'reason':'DEMO 系统管理员审批'},admin)]
                results=[f.result() for f in futures]
            assert sorted(r[0] for r in results)==[200,409]
            current=detail(multiple);assert current['version']==1 and len(current['approvalRecords'])==1
            expect(action(current,'cancel','student'),200)
            cutoff=create('取消截止保护',11)
            s.sql("UPDATE activity_application SET start_at=UTC_TIMESTAMP(3)+INTERVAL 5 MINUTE,end_at=UTC_TIMESTAMP(3)+INTERVAL 65 MINUTE WHERE id="+cutoff['applicationId']+";UPDATE resource_occupancy SET start_at=UTC_TIMESTAMP(3)+INTERVAL 5 MINUTE,end_at=UTC_TIMESTAMP(3)+INTERVAL 65 MINUTE WHERE application_id="+cutoff['applicationId'])
            refused=action(cutoff,'cancel','student');assert refused[0]==422 and refused[1]['code']=='CANCELLATION_DEADLINE_PASSED'
            occupancy(cutoff,'HOLD');expect(action(cutoff,'reject'),200)
            record('多个管理员同时审批只生效一次，超过取消截止不会释放或改写申请')
            ineligible=create('申请人停用',11,actor='second')
            expect(call('/users/'+users['second']+'/status','PATCH',{'status':'DISABLED','expectedVersion':0},admin),200)
            denied=action(ineligible,'approve')
            assert denied[0]==422 and denied[1]['code']=='APPLICANT_INELIGIBLE'
            assert detail(ineligible,'manager')['status']=='PENDING_APPROVAL';occupancy(ineligible,'HOLD')
            expect(action(ineligible,'reject'),200)
            record('批准前重新检查申请人实时资格，停用账号不能批准且拒绝不会部分修改占用')
            pending=create('定时超时',11)
            completed=expect(action(create('定时完成',13),'approve'),200)
            s.sql("UPDATE activity_application SET hold_expires_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE id="+pending['applicationId']+";UPDATE resource_occupancy SET expires_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE application_id="+pending['applicationId']+";"
                  "UPDATE activity_application SET start_at=UTC_TIMESTAMP(3)-INTERVAL 2 HOUR,end_at=UTC_TIMESTAMP(3)-INTERVAL 1 HOUR WHERE id="+completed['applicationId']+";"
                  "UPDATE resource_occupancy SET start_at=UTC_TIMESTAMP(3)-INTERVAL 2 HOUR,end_at=UTC_TIMESTAMP(3)-INTERVAL 1 HOUR WHERE application_id="+completed['applicationId'])
            unresolved=expect(action(create('已结束冲突人工处置',17),'approve'),200)
            ex=external('review-past',17);unresolved=detail(unresolved)
            s.sql("UPDATE activity_application SET start_at=UTC_TIMESTAMP(3)-INTERVAL 2 HOUR,end_at=UTC_TIMESTAMP(3)-INTERVAL 1 HOUR WHERE id="+unresolved['applicationId']+";UPDATE resource_occupancy SET start_at=UTC_TIMESTAMP(3)-INTERVAL 2 HOUR,end_at=UTC_TIMESTAMP(3)-INTERVAL 1 HOUR WHERE application_id="+unresolved['applicationId'])
            # 两个扫描实例访问同一个 dev 数据库，模拟重启补偿和重复扫描。
            for port in [18082,18083]:
                stack.start('booking-service',port,{'BOOKING_LIFECYCLE_INITIAL_DELAY_MS':'1000','BOOKING_LIFECYCLE_DELAY_MS':'1000'})
            deadline=time.monotonic()+90
            while time.monotonic()<deadline:
                if s.sql("SELECT GROUP_CONCAT(status ORDER BY id) FROM activity_application WHERE id IN ("+pending['applicationId']+","+completed['applicationId']+")")=='EXPIRED,COMPLETED':break
                time.sleep(.5)
            else:raise AssertionError('定时到期未完成')
            time.sleep(2)
            occupancy(pending,'RELEASED');occupancy(completed,'RELEASED')
            for item in [pending,completed]:
                assert s.sql('SELECT COUNT(*) FROM approval_record WHERE application_id='+item['applicationId'])==('1' if item is pending else '2')
            assert detail(unresolved)['status']=='CONFLICT_REVIEW';occupancy(unresolved,'CONFIRMED')
            expect(resolve(unresolved,'CANCEL'),200);occupancy(unresolved,'RELEASED');remove_external(ex,17)
            record('多实例到期扫描补偿超时和已结束活动，重复扫描不重复释放或生成历史')
            record('冲突待复核不因结束自动完成，授权管理员可带理由处置取消整组')
            assert s.sql("SELECT COUNT(*) FROM (SELECT aggregate_id,aggregate_version,COUNT(*) n FROM outbox_event WHERE aggregate_type='APPLICATION' AND aggregate_id IN (SELECT id FROM activity_application WHERE activity_name LIKE '"+prefix+"%') GROUP BY aggregate_id,aggregate_version HAVING n<>1) bad")=='0'
            assert s.sql("SELECT COUNT(*) FROM outbox_event o JOIN activity_application a ON a.id=o.aggregate_id WHERE a.activity_name LIKE '"+prefix+"%' AND o.status<>'PENDING'")=='0'
            assert s.sql("SELECT COUNT(*) FROM activity_application a WHERE a.activity_name LIKE '"+prefix+"%' AND (SELECT COUNT(*) FROM outbox_event o WHERE o.aggregate_type='APPLICATION' AND o.aggregate_id=a.id)<>a.version+1")=='0'
            record('每个成功申请版本原子写入唯一 Outbox，本回归关闭投递以独立核对 PENDING 记录')
            if os.environ.get('XMUT_WEB_TEST')=='1':
                env=os.environ.copy();env.update(TEST_APPLICATION_PREFIX=prefix,TEST_APPLICATION_CAMPUS=prefix,
                    TEST_APPLICATION_VENUE=prefix+' 演示 venue',TEST_APPLICATION_START=(day+timedelta(hours=17)).strftime('%Y-%m-%d %H:%M:%S'),
                    TEST_APPLICATION_END=(day+timedelta(hours=18)).strftime('%Y-%m-%d %H:%M:%S'))
                env['VITE_TEST_DATA_MODE']='DEMO'
                for name in ['student','teacher','manager']:env.update({('TEST_APPLICATION_'+name).upper():prefix+'_'+name,('TEST_APPLICATION_'+name+'_PASSWORD').upper():passwords[name]})
                fd=os.open(s.PRIVATE/'applications-web-test.log',os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
                with os.fdopen(fd,'w') as log:result=subprocess.run(['npm','test','--','tests/applications.spec.ts'],cwd=s.REPO/'web-admin',env=env,stdout=log,stderr=subprocess.STDOUT)
                assert result.returncode==0,'预约浏览器验收失败，请检查私密 applications-web-test.log'
                record('真实浏览器完成学生申请与取消、授权管理员批准、教师申请和权限隔离')
            destination=s.auth.REPORT_DIR;destination.mkdir(parents=True,exist_ok=True)
            (destination/'applications.json').write_text(json.dumps({'date':s.auth.REPORT_DATE,'mode':'dev-real-mysql-redis-nacos','checks':checks,'passed':len(checks),'failed':0,
                 'scope':'M3 联合预约闭环；不含 M4 候补与消息投递、移动端和线上部署'},ensure_ascii=False,indent=2)+'\n')
            # 先关闭本轮全部服务，再由 finally 清理资料，避免定时器与删除交叉。
    finally:
        if initialized:
            s.sql('DROP TRIGGER IF EXISTS '+trigger)
            cleanup()

if __name__=='__main__':
    try:main()
    except (AssertionError,RuntimeError) as failure:
        import traceback
        line=traceback.extract_tb(failure.__traceback__)[-1].lineno
        raise SystemExit(f'联合预约验收在第 {line} 行失败：{str(failure) or "请检查私密日志"}')
