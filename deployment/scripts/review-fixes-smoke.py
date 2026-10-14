#!/usr/bin/env python3
"""复审修复的真实 dev API 回归：独立端口/audience，随机 DEMO 资料，finally 清理。"""
from datetime import datetime, timedelta
import hashlib
import hmac
import importlib.util
import json
from pathlib import Path
import socket
import time
import urllib.error
import urllib.request
import uuid
from zoneinfo import ZoneInfo

spec=importlib.util.spec_from_file_location('support',Path(__file__).with_name('business-test-support.py'))
s=importlib.util.module_from_spec(spec);spec.loader.exec_module(s)
PORTS={'gateway':18981,'booking-service':18982,'identity-service':18983}
BASE='http://127.0.0.1:18981/api/v1'

class ReviewStack(s.Stack):
    def __enter__(self):
        for port in PORTS.values():
            with socket.socket() as probe:
                if probe.connect_ex(('127.0.0.1',port))==0:raise RuntimeError('验收端口已占用；没有停止已有服务。')
        try:
            for name in ['identity-service','booking-service','gateway']:self.start(name,PORTS[name])
            pending=set(PORTS.values());deadline=time.monotonic()+100
            while pending and time.monotonic()<deadline:
                if any(p.poll() is not None for _,p,_ in self.children):raise RuntimeError('验收服务启动失败，详见被忽略的私密日志。')
                for port in list(pending):
                    try:
                        if s.auth.request(f'http://127.0.0.1:{port}/actuator/health/readiness')[0]==200:pending.remove(port)
                    except (urllib.error.URLError,TimeoutError,ValueError):pass
                time.sleep(.3)
            if pending:raise RuntimeError('验收服务就绪超时')
            time.sleep(2)
            return self
        except BaseException:
            self.__exit__(None,None,None);raise
    def start(self,name,port,overrides=None):
        super().start(name,port,dict(overrides or {},JWT_AUDIENCE='campus-dev-review-'+self.group,
            BOOKING_LIFECYCLE_INITIAL_DELAY_MS='3600000'))

def main():
    prefix='review-'+uuid.uuid4().hex[:10];source=prefix.replace('-','_');checks=[];previous=[];users={}
    private=s.runtime.load_env(s.PRIVATE/'identity.env')
    storage=Path(s.runtime.load_env(s.PRIVATE/'auth.env')['IMPORT_STORAGE_DIR'])
    zone=ZoneInfo('Asia/Shanghai');day=(datetime.now(zone)+timedelta(days=1)).replace(hour=0,minute=0,second=0,microsecond=0)
    def at(hour):return (day+timedelta(hours=hour)).isoformat(timespec='milliseconds')
    def record(name):checks.append({'check':name,'result':'PASS'});print('PASS:',name,flush=True)
    def call(path,method='GET',body=None,token=None,key=None,extra=None):
        return s.auth.request(BASE+path,method,body,token,prefix+'-request',
            key or prefix+'-'+uuid.uuid4().hex if method!='GET' else None,extra)
    def expect(result,status):
        assert result[0]==status,(result[0],result[1].get('code'),result[1].get('message'))
        return result[1]['data']
    def login(username,password):
        return expect(call('/auth/login','POST',{'username':username,'password':password,'deviceId':prefix+'-'+uuid.uuid4().hex,'deviceType':'WEB'}),200)['accessToken']
    def snapshot(admin,ids,start,end,rows=None):
        boundary=uuid.uuid4().hex
        context={'from':at(start),'to':at(end),'capturedAt':datetime.now(zone).isoformat(timespec='milliseconds'),
                 'completeness':'COMPLETE','sourceDescription':'隔离 DEMO 回归，非学校真实来源','resourceIds':ids}
        fields={'importType':'EXTERNAL_OCCUPANCY','sourceSystem':source,'dataMode':'DEMO','fieldMapping':'{}','queryContext':json.dumps(context)}
        parts=[f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode() for name,value in fields.items()]
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="snapshot.json"\r\nContent-Type: application/json\r\n\r\n'.encode()+json.dumps(rows or []).encode()+b'\r\n')
        parts.append(f'--{boundary}--\r\n'.encode())
        request=urllib.request.Request(BASE+'/admin/imports/preview',data=b''.join(parts),method='POST',headers={
            'Authorization':'Bearer '+admin,'Content-Type':'multipart/form-data; boundary='+boundary,
            'Idempotency-Key':prefix+'-'+uuid.uuid4().hex,'X-Trace-Id':prefix+'-request'})
        try:response=urllib.request.urlopen(request,timeout=30)
        except urllib.error.HTTPError as failure:response=failure
        with response:body=json.load(response);assert response.status==201,(response.status,body.get('code'))
        assert body['data']['summary']['errorRows']==0
        return expect(call('/admin/imports/'+body['data']['batchId']+'/commit','POST',{},admin),200)
    def cleanup():
        files=s.sql("SELECT file_storage_key FROM import_batch WHERE source_system='"+source+"'").splitlines()
        groups=s.sql("SELECT DISTINCT g.id FROM waitlist_group g JOIN waitlist_group_resource gr ON gr.group_id=g.id JOIN resource r ON r.id=gr.resource_id WHERE r.source_system='"+source+"'").splitlines()
        # 本轮资源、候补、申请及事件全带随机前缀；严格按外键逆序删除，不改迁移和 REAL 数据。
        s.sql("DELETE FROM outbox_event WHERE delivery_scope='"+audience+"';"
              "DELETE FROM audit_log WHERE trace_id LIKE '"+prefix+"%';"
              "DELETE o FROM resource_occupancy o JOIN waitlist_entry w ON w.id=o.waitlist_entry_id WHERE w.activity_name LIKE '"+prefix+"%';"
              "DELETE FROM waitlist_entry WHERE activity_name LIKE '"+prefix+"%';"
              "DELETE gr FROM waitlist_group_resource gr JOIN resource r ON r.id=gr.resource_id WHERE r.source_system='"+source+"';"
              "DELETE FROM waitlist_group WHERE id IN ("+(','.join(groups) or '0')+");"
              "DELETE p FROM approval_record p JOIN activity_application a ON a.id=p.application_id WHERE a.activity_name LIKE '"+prefix+"%';"
              "DELETE o FROM resource_occupancy o JOIN activity_application a ON a.id=o.application_id WHERE a.activity_name LIKE '"+prefix+"%';"
              "DELETE r FROM application_resource r JOIN activity_application a ON a.id=r.application_id WHERE a.activity_name LIKE '"+prefix+"%';"
              "DELETE FROM activity_application WHERE activity_name LIKE '"+prefix+"%';"
              "DELETE FROM applicant_guard WHERE user_id IN ("+(','.join(users.values()) or '0')+");"
              "DELETE o FROM external_occupancy o JOIN resource r ON r.id=o.resource_id WHERE r.source_system='"+source+"';"
              "DELETE c FROM availability_coverage c JOIN resource r ON r.id=c.resource_id WHERE r.source_system='"+source+"';"
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
              "DELETE FROM idempotency_record WHERE request_key LIKE '"+prefix+"%';")
        for original in previous:
            s.sql("UPDATE booking_policy p SET active=1 WHERE p.id="+original+" AND NOT EXISTS(SELECT 1 FROM (SELECT scope_code FROM booking_policy WHERE active=1) current_policy WHERE current_policy.scope_code=p.scope_code)")
        for filename in files:
            if filename and '/' not in filename:(storage/filename).unlink(missing_ok=True)
        s.cleanup_identity(prefix)
        # 独立 audience 的临时限流/nonce/缓存键自然短期过期，不删除日常开发键。
    audience='';initialized=False
    try:
        with ReviewStack('review-fixes-test') as stack:
            audience='campus-dev-review-'+stack.group;initialized=True
            previous=s.sql("SELECT id FROM booking_policy WHERE scope_code='DEMO' AND active=1").splitlines()
            admin=login(private['AUTH_ADMIN_USERNAME'],private['AUTH_ADMIN_PASSWORD'])
            tokens={}
            for name,role in [('first','STUDENT'),('second','TEACHER')]:
                password=uuid.uuid4().hex+'Aa1!'
                users[name]=expect(call('/users','POST',{'username':prefix+'_'+name,'displayName':'DEMO 回归 '+name,'initialPassword':password,'roleCodes':[role]},admin),201)['id']
                tokens[name]=login(prefix+'_'+name,password)
            group=expect(call('/admin/approval-groups','POST',{'name':prefix+'-group'},admin),201)['id']
            resources={}
            for name,kind in [('venue','VENUE'),('equipment','EQUIPMENT'),('fragment','VENUE')]:
                payload={'resourceType':kind,'sourceSystem':source,'sourceId':name,'name':prefix+' '+name,'campus':prefix,'capacity':40,
                         'sourceBorrowable':'YES','platformStatus':'OPEN','approvalGroupId':group,'dataMode':'DEMO'}
                if kind=='EQUIPMENT':payload.update(assetCode=prefix+'-'+name,equipmentType='无线 话筒',conditionStatus='GOOD')
                resources[name]=expect(call('/admin/resources','POST',payload,admin),201)['id']
            settings={'pendingApprovalTtlMinutes':60,'waitlistOfferTtlMinutes':15,'bookingLeadMinutes':0,'cancelLeadMinutes':0,
                      'maxBookingDaysAhead':30,'maxDurationMinutes':120,'maxActiveApplicationsPerUser':10,'coverageFreshnessMinutes':120}
            version=int(s.sql("SELECT COALESCE(MAX(policy_version),0) FROM booking_policy WHERE scope_code='DEMO'"))
            expect(call('/admin/policies/DEMO/versions','POST',{'expectedVersion':version,'settings':settings,'basis':prefix+' DEMO 回归假设'},admin),201)
            rules=[{'weekday':day.isoweekday(),'startLocal':'08:00:00','endLocal':'20:00:00','validFrom':day.date().isoformat(),'validTo':day.date().isoformat(),'enabled':True}]
            for resource in resources.values():expect(call('/admin/resources/'+resource+'/opening-rules','PUT',{'expectedVersion':0,'rules':rules},admin),200)
            snapshot(admin,list(resources.values()),8,20)
            search={'campus':prefix,'startAt':at(12),'endAt':at(13),'participantCount':20,'requiredFeatures':[],
                    'equipmentRequirements':[{'type':'无线 话筒','count':1}],'dataMode':'DEMO','page':1,'size':20}
            combos=expect(call('/availability/search','POST',search,tokens['first']),200)
            assert combos['total']==2
            record('中文及带空格设备类型可创建并参与真实可用组合查询')
            # 通过正式维护接口创建/停用/启用原记录，重现重复裁剪，不直接修改业务行构造假状态。
            original=expect(call('/admin/resources/'+resources['fragment']+'/external-occupancies','POST',{
                'sourceSystem':source,'sourceKey':'original','startAt':at(8),'endAt':at(12),'reasonCode':'COURSE'},admin),201)
            snapshot(admin,[resources['fragment']],10,11)
            def occupancies():return expect(call('/admin/resources/'+resources['fragment']+'/external-occupancies?size=100',token=admin),200)['items']
            rows=occupancies();fragments=[r for r in rows if r['sourceKey'].startswith('__fragment_')]
            assert len(fragments)==2 and all(r['active'] for r in fragments)
            # 片段允许正常人工停用；原证据重新启用并重裁剪时，稳定身份应恢复而非新建副本。
            left=fragments[0]
            expect(call('/admin/external-occupancies/'+left['id'],'PATCH',{'expectedVersion':left['version'],'active':False,
                'startAt':left['startAt'],'endAt':left['endAt'],'reasonCode':left['reasonCode']},admin),200)
            current=next(r for r in rows if r['id']==original['id'])
            expect(call('/admin/external-occupancies/'+current['id'],'PATCH',{'expectedVersion':current['version'],'active':True,
                'startAt':at(8),'endAt':at(12),'reasonCode':'EXAM'},admin),200)
            snapshot(admin,[resources['fragment']],10,11)
            rows=occupancies();after=[r for r in rows if r['sourceKey'].startswith('__fragment_')]
            assert {r['id'] for r in after}=={r['id'] for r in fragments} and len(after)==2
            assert all(r['version']==(2 if r['id']==left['id'] else 1) and r['active'] and r['reasonCode']=='EXAM' for r in after)
            assert not next(r for r in rows if r['id']==original['id'])['active']
            for old in fragments:
                right=next(r for r in after if r['id']==old['id'])
                assert (old['startAt'],old['endAt'])==(right['startAt'],right['endAt'])
            assert s.sql('SELECT COUNT(*) FROM external_occupancy f JOIN external_occupancy o ON o.id='+original['id']+
                ' WHERE f.id IN ('+','.join(r['id'] for r in after)+') AND f.observed_at=o.observed_at AND f.import_batch_id IS NULL')=='2'
            assert call('/admin/external-occupancies/'+left['id'],'PATCH',{'expectedVersion':left['version'],'active':False,
                'startAt':left['startAt'],'endAt':left['endAt'],'reasonCode':left['reasonCode']},admin)[1]['code']=='VERSION_CONFLICT'
            record('真实 MySQL 重复裁剪复用片段、恢复停用片段、拒绝旧版本并保留原观察时间和批次')
            def payload(name):return {'activityName':prefix+' '+name,'description':'隔离 DEMO 回归','participantCount':20,
                'startAt':at(14),'endAt':at(15),'venueId':resources['venue'],'equipmentIds':[resources['equipment']],'requiredFeatures':[]}
            assert call('/waitlists','POST',payload('空闲无队列'),tokens['first'])[1]['code']=='WAITLIST_NOT_NEEDED'
            occupied=expect(call('/admin/resources/'+resources['venue']+'/external-occupancies','POST',{'sourceSystem':'MANUAL',
                'sourceKey':prefix+'-busy','startAt':at(14),'endAt':at(15),'reasonCode':'EXAM'},admin),201)
            first=expect(call('/waitlists','POST',payload('队首'),tokens['first']),201)
            expect(call('/admin/external-occupancies/'+occupied['id'],'PATCH',{'expectedVersion':0,'active':False,
                'startAt':at(14),'endAt':at(15),'reasonCode':'EXAM'},admin),200)
            assert call('/applications','POST',payload('不能插队'),tokens['second'])[1]['code']=='WAITLIST_PRIORITY'
            second=expect(call('/waitlists','POST',payload('空闲后加入已有队列'),tokens['second']),201)
            assert second['queuePosition']==2 and first['queuePosition']==1
            assert call('/waitlists','POST',payload('重复队列'),tokens['second'])[1]['code']=='DUPLICATE_WAITLIST'
            assert s.sql('SELECT COUNT(*) FROM resource_occupancy WHERE waitlist_entry_id IN ('+first['waitlistId']+','+second['waitlistId']+')')=='0'
            expect(call('/waitlists/'+first['waitlistId']+'/cancel','POST',{'expectedVersion':0,'reason':'DEMO 清理队首'},tokens['first']),200)
            now=expect(call('/waitlists/'+second['waitlistId'],token=tokens['second']),200);assert now['queuePosition']==1
            expect(call('/waitlists/'+second['waitlistId']+'/cancel','POST',{'expectedVersion':0,'reason':'DEMO 清理队列'},tokens['second']),200)
            ordinary=expect(call('/applications','POST',payload('队列结束普通申请'),tokens['second']),201)
            expect(call('/applications/'+ordinary['applicationId']+'/cancel','POST',{'expectedVersion':0,'reason':'DEMO 清理申请'},tokens['second']),200)
            record('空闲但已有队列可继续入队；FIFO、去重、WAITING 不占资源及队列结束恢复普通申请')
            # Python 仅为测试模拟已授权网关签名；密钥不输出、不作为命令参数或写入报告。
            key=(s.PRIVATE/'login-peer.key').read_bytes()
            def signed(username,peer,headers=None):
                instant=str(int(time.time()));nonce=str(uuid.uuid4())
                message='LOGIN_PEER_V1\nPOST\n/auth/login\n'+audience+'\n'+peer+'\n'+instant+'\n'+nonce
                extra=headers or {'X-Login-Peer':peer,'X-Login-Time':instant,'X-Login-Nonce':nonce,'X-Login-Signature':hmac.new(key,message.encode(),hashlib.sha256).hexdigest()}
                result=s.auth.request('http://127.0.0.1:18983/auth/login','POST',{'username':username,'password':'wrong-test-password',
                    'deviceId':prefix+'-negative','deviceType':'WEB'},trace=prefix+'-request',extra=extra)
                return result,extra
            for i in range(1,36):assert signed(prefix+'_client_'+str(i),'192.0.2.'+str(i))[0][1]['code']=='UNAUTHORIZED'
            record('真实 Redis 下 35 个独立签名来源不会共用网关来源桶')
            for i in range(30):assert signed(prefix+'_peer_'+str(i),'198.51.100.1')[0][0]==401
            assert signed(prefix+'_peer_31','198.51.100.1')[0][1]['code']=='LOGIN_RATE_LIMITED'
            for i in range(1,11):assert signed(prefix+'_account','203.0.113.'+str(i))[0][0]==401
            assert signed(prefix+'_account','203.0.113.11')[0][1]['code']=='LOGIN_RATE_LIMITED'
            replay,headers=signed(prefix+'_replay','192.0.2.100')
            assert signed(prefix+'_replay','192.0.2.100',headers)[0][1]['code']=='INVALID_LOGIN_SOURCE'
            spoof=call('/auth/login','POST',{'username':prefix+'_spoof','password':'wrong-test-password','deviceId':prefix+'-negative','deviceType':'WEB'},
                extra={'X-Login-Peer':'192.0.2.201','X-Login-Signature':'spoof','X-Forwarded-For':'192.0.2.201'})
            assert spoof[1]['code']=='UNAUTHORIZED'
            record('来源 30 次与账号 10 次限流仍生效；重放拒绝，网关清除伪造来源头')
    finally:
        if initialized:cleanup()
    destination=s.auth.REPORT_DIR/'review-fixes';destination.mkdir(parents=True,exist_ok=True)
    (destination/'backend.json').write_text(json.dumps({'date':s.auth.REPORT_DATE,'mode':'isolated-dev-real-mysql-redis-nacos',
        'ports':PORTS,'checks':checks,'productionStarted':False,'fixturesCleaned':True,'testProcessesStopped':True,
        'realDataModified':False,'rabbitFaultInjected':False},ensure_ascii=False,indent=2)+'\n')
    print('完成',len(checks),'组真实 API 回归；资料和子进程已清理。',flush=True)

if __name__=='__main__':main()
