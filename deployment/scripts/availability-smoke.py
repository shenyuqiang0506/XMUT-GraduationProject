#!/usr/bin/env python3
"""M3 可用性基础验收：真实 dev 依赖，自动清理本轮资料和进程。"""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
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

spec=importlib.util.spec_from_file_location('support',Path(__file__).with_name('business-test-support.py'))
s=importlib.util.module_from_spec(spec);spec.loader.exec_module(s)
spec=importlib.util.spec_from_file_location('catalog',Path(__file__).with_name('catalog-smoke.py'))
c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
BASE='http://127.0.0.1:8081/api/v1'
ZONE=timezone(timedelta(hours=8))

# 为本轮验收使用独立 JWT audience，隔离与日常开发共用的登录限流键。
class AvailabilityStack(s.Stack):
    def start(self,name,port,overrides=None):
        # 三个服务使用同一独立 dev audience，登录保护保持启用且不消耗日常开发额度。
        super().start(name,port,dict(overrides or {},JWT_AUDIENCE='xmut-dev-api-'+self.group))

# 构造临时资源、策略和时间快照，验证完整覆盖、冲突、权限与并发导入边界。
# 测试输入不代表学校实际制度；原有启用策略在清理阶段恢复。
def main():
    prefix='ava-'+uuid.uuid4().hex[:10];source=prefix.replace('-','_');checks=[]
    private=s.runtime.load_env(s.PRIVATE/'identity.env')
    storage=Path(s.runtime.load_env(s.PRIVATE/'auth.env')['IMPORT_STORAGE_DIR'])
    previous=[]
    day=(datetime.now(ZONE)+timedelta(days=1)).replace(hour=0,minute=0,second=0,microsecond=0)
    def stamp(value):return value.isoformat(timespec='milliseconds')
    def at(hour):return stamp(day+timedelta(hours=hour))
    def captured():return stamp(datetime.now(ZONE)-timedelta(seconds=1))
    def record(name):checks.append({'check':name,'result':'PASS'});print('PASS:',name,flush=True)
    def call(path,method='GET',body=None,token=None,key=None):
        return s.auth.request(BASE+path,method,body,token,prefix+'-request',key or (prefix+'-'+uuid.uuid4().hex[:16]) if method!='GET' else None)
    def upload(token,kind,rows,context,mode='DEMO',filename='intervals.json',mapping=None,data=None):
        boundary=uuid.uuid4().hex;parts=[]
        fields={'importType':kind,'sourceSystem':source,'dataMode':mode,'fieldMapping':json.dumps(mapping or {}),'queryContext':json.dumps(context)}
        for name,value in fields.items():
            parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode())
        content=data if data is not None else json.dumps(rows).encode()
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\nContent-Type: application/octet-stream\r\n\r\n'.encode()+content+b'\r\n')
        parts.append(f'--{boundary}--\r\n'.encode())
        request=urllib.request.Request(BASE+'/admin/imports/preview',data=b''.join(parts),headers={'Content-Type':'multipart/form-data; boundary='+boundary,'Authorization':'Bearer '+token,'Idempotency-Key':prefix+'-'+uuid.uuid4().hex[:16],'X-Trace-Id':prefix+'-request'},method='POST')
        try:r=urllib.request.urlopen(request,timeout=30)
        except urllib.error.HTTPError as error:r=error
        with r:return r.status,json.load(r),dict(r.headers)
    # 显式声明资源范围、覆盖区间和完整性，不能凭空文件推断覆盖所有资源。
    def context(ids,from_=8,to=18,complete='COMPLETE'):
        return {'from':at(from_),'to':at(to),'capturedAt':captured(),'completeness':complete,'sourceDescription':'DEMO 自动验收来源，不代表学校制度','resourceIds':ids}
    # 先走预览再确认，与管理端导入流程一致，复用返回的批次信息。
    def commit_batch(token,kind,rows,ctx,**kwargs):
        status,body,_=upload(token,kind,rows,ctx,**kwargs)
        assert status==201,(status,body['code'])
        assert body['data']['summary']['errorRows']==0,body['data']['errors']
        batch=body['data']['batchId']
        status,body,headers=call('/admin/imports/'+batch+'/commit','POST',{},token)
        assert status==200,(status,body['code'])
        return batch,body,headers
    try:
        with AvailabilityStack('availability-test'):
            previous=s.sql("SELECT id FROM booking_policy WHERE active=1 ORDER BY id").splitlines()
            policy_max={scope:int(s.sql("SELECT COALESCE(MAX(policy_version),0) FROM booking_policy WHERE scope_code='"+scope+"'")) for scope in ['DEMO','REAL']}
            def login(name,password,device):
                status,body,_=call('/auth/login','POST',{'username':name,'password':password,'deviceId':prefix+'-'+device,'deviceType':'WEB'})
                assert status==200,(status,body.get('code'));return body['data']['accessToken']
            admin=login(private['AUTH_ADMIN_USERNAME'],private['AUTH_ADMIN_PASSWORD'],'admin')
            tokens={};users={};passwords={}
            for name,role in [('student','STUDENT'),('manager','RESOURCE_ADMIN'),('other','RESOURCE_ADMIN')]:
                password=uuid.uuid4().hex+'Aa1!';passwords[name]=password
                status,body,_=call('/users','POST',{'username':prefix+'_'+name,'displayName':'演示验收账号','initialPassword':password,'roleCodes':[role]},admin)
                assert status==201;users[name]=body['data']['id'];tokens[name]=login(prefix+'_'+name,password,name)
            groups=[]
            for suffix in ['A','B']:
                status,body,_=call('/admin/approval-groups','POST',{'name':prefix+'-'+suffix},admin)
                assert status==201;groups.append(body['data']['id'])
            ga,gb=groups
            assert call('/admin/approval-groups/'+ga+'/members','PUT',{'userIds':[users['manager']]},admin)[0]==200
            resources={}
            for name,kind,group,mode in [('venue','VENUE',ga,'DEMO'),('eq1','EQUIPMENT',ga,'DEMO'),('eq2','EQUIPMENT',ga,'DEMO'),('other','EQUIPMENT',gb,'DEMO'),('real','VENUE',ga,'REAL')]:
                payload={'resourceType':kind,'sourceSystem':source,'sourceId':name,'name':prefix+' 演示 '+name,'campus':prefix,'building':'演示楼',
                         'sourceBorrowable':'YES','platformStatus':'OPEN','approvalGroupId':group,'dataMode':mode,'capacity':40 if kind=='VENUE' else None,'features':{'PROJECTOR':'YES'}}
                if kind=='EQUIPMENT':payload.update(assetCode=prefix+'-'+name,equipmentType='MICROPHONE',conditionStatus='GOOD')
                status,body,_=call('/admin/resources','POST',payload,admin)
                assert status==201;resources[name]=body['data']['id']
            venue=resources['venue'];all_ids=[resources[k] for k in ['venue','eq1','eq2']]
            search={'campus':prefix,'startAt':at(10),'endAt':at(11),'participantCount':20,'requiredFeatures':['PROJECTOR'],
                    'equipmentRequirements':[{'type':'MICROPHONE','count':2}],'dataMode':'DEMO','page':1,'size':20}
            def query(**updates):
                status,body,_=call('/availability/search','POST',dict(search,**updates),tokens['student'])
                assert status==200,(status,body['code']);return body['data']
            assert call('/availability/search','POST',search)[0]==401
            assert query()['total']==0
            assert call('/admin/policies/DEMO',token=tokens['student'])[0]==403
            settings={'pendingApprovalTtlMinutes':60,'waitlistOfferTtlMinutes':15,'bookingLeadMinutes':0,'cancelLeadMinutes':30,
                      'maxBookingDaysAhead':30,'maxDurationMinutes':240,'maxActiveApplicationsPerUser':3,'coverageFreshnessMinutes':120}
            for scope in ['DEMO','REAL']:
                payload={'expectedVersion':policy_max[scope],'settings':settings,'basis':prefix+' DEMO 测试假设，仅用于本次验收'}
                assert call('/admin/policies/'+scope+'/versions','POST',payload,tokens['manager'])[0]==403
                status,body,_=call('/admin/policies/'+scope+'/versions','POST',payload,admin)
                assert status==201 and body['data']['version']==policy_max[scope]+1
            assert call('/admin/policies/DEMO/versions','POST',{'expectedVersion':policy_max['DEMO'],'settings':settings,'basis':prefix},admin)[0]==409
            assert call('/admin/policies/DEMO/versions','POST',{'expectedVersion':0.5,'settings':settings,'basis':prefix},admin)[0]==400
            record('无策略不提供方案；策略仅系统管理员可版本化配置，小数版本被拒绝')
            rules=[{'weekday':n,'startLocal':'08:00:00','endLocal':'20:00:00','validFrom':day.date().isoformat(),'validTo':(day+timedelta(days=30)).date().isoformat(),'enabled':True} for n in range(1,8)]
            for resource in resources.values():
                key=prefix+'-opening-'+resource
                body={'expectedVersion':0,'rules':rules}
                status,response,_=call('/admin/resources/'+resource+'/opening-rules','PUT',body,admin,key)
                assert status==200 and response['data']['version']==1
                assert call('/admin/resources/'+resource+'/opening-rules','PUT',body,admin,key)[2]['Idempotency-Replayed']=='true'
            assert call('/admin/resources/'+resources['other']+'/opening-rules','PUT',{'expectedVersion':1,'rules':rules},tokens['manager'])[0]==404
            record('开放规则按资源版本更新，幂等重放保持结果，跨管理范围被拒绝')
            assert query()['total']==0
            batch,_,_=commit_batch(admin,'EXTERNAL_OCCUPANCY',[],context(all_ids))
            assert call('/admin/imports/'+batch+'/commit','POST',{},admin)[0]==200
            result=query();assert result['total']==1
            plan=result['items'][0]
            assert plan['equipmentIds']==sorted([resources['eq1'],resources['eq2']],key=int)
            assert plan['approvalGroupId']==ga and plan['coverageStatus']=='COMPLETE'
            assert query(participantCount=41)['total']==0
            assert query(requiredFeatures=['WHITEBOARD'])['total']==0
            s.sql("UPDATE resource_feature SET feature_value='UNKNOWN' WHERE resource_id="+venue+" AND feature_code='PROJECTOR'")
            assert query()['total']==0
            s.sql("UPDATE resource_feature SET feature_value='YES' WHERE resource_id="+venue+" AND feature_code='PROJECTOR'")
            record('完整空占用快照产生覆盖；组合满足容量、特征、同组和设备数量')
            real_search=dict(search,dataMode='REAL',equipmentRequirements=[])
            assert call('/availability/search','POST',real_search,tokens['student'])[1]['data']['total']==0
            assert upload(admin,'EXTERNAL_OCCUPANCY',[],context(all_ids),mode='REAL')[0]==400
            commit_batch(admin,'EXTERNAL_OCCUPANCY',[],context([resources['real']]),mode='REAL')
            assert call('/availability/search','POST',real_search,tokens['student'])[1]['data']['total']==1
            record('REAL/DEMO 的资源、证明与策略隔离，不能跨模式声明覆盖')
            partial=[{'sourceId':'venue','sourceKey':'partial','startAt':at(15),'endAt':at(16),'reasonCode':'COURSE'}]
            commit_batch(admin,'EXTERNAL_OCCUPANCY',partial,context([venue],complete='PARTIAL'))
            assert query()['total']==0
            commit_batch(admin,'EXTERNAL_OCCUPANCY',[],context(all_ids))
            record('PARTIAL 替换旧证据后保持未知，不将缺失占用当作空闲')
            occupancy={'sourceSystem':'MANUAL','sourceKey':prefix+'-manual','startAt':at(10),'endAt':at(11),'reasonCode':'MAINTENANCE'}
            key=prefix+'-manual-create'
            status,body,_=call('/admin/resources/'+venue+'/external-occupancies','POST',occupancy,admin,key)
            assert status==201;occupancy_id=body['data']['id']
            assert query()['total']==0
            assert call('/admin/resources/'+venue+'/external-occupancies','POST',dict(occupancy,endAt=at(12)),admin,key)[0]==409
            calendar='/resources/'+venue+'/calendar?'+urllib.parse.urlencode({'from':at(8),'to':at(18)})
            status,body,_=call(calendar,token=tokens['student'])
            assert status==200 and body['data']['intervals']
            assert set(body['data']['intervals'][0])=={'startAt','endAt','type'}
            assert call('/admin/resources/'+venue+'/external-occupancies',token=tokens['student'])[0]==403
            update={'expectedVersion':0,'active':False,'startAt':at(10),'endAt':at(11),'reasonCode':'MAINTENANCE'}
            assert call('/admin/external-occupancies/'+occupancy_id,'PATCH',update,tokens['other'])[0]==404
            assert call('/admin/external-occupancies/'+occupancy_id,'PATCH',update,admin)[0]==200
            assert call('/admin/external-occupancies/'+occupancy_id,'PATCH',update,admin)[0]==409
            assert query()['total']==1
            assert call('/admin/resources/'+venue+'/external-occupancies','POST',dict(occupancy,sourceKey=prefix+'-adjacent',startAt=at(9),endAt=at(10)),admin)[0]==201
            assert query()['total']==1
            record('手工占用立即阻止组合；版本和幂等有效，相接时段不冲突，日历不泄露私有信息')
            commit_batch(admin,'EXTERNAL_OCCUPANCY',[{'sourceId':'venue','sourceKey':'span','startAt':at(8),'endAt':at(18),'reasonCode':'COURSE'}],context([venue]))
            assert query()['total']==0
            commit_batch(admin,'EXTERNAL_OCCUPANCY',[],context([venue],10,12))
            status,body,_=call(calendar,token=tokens['student'])
            intervals=body['data']['intervals']
            assert any(datetime.fromisoformat(i['startAt'])==datetime.fromisoformat(at(8)) and datetime.fromisoformat(i['endAt'])==datetime.fromisoformat(at(10)) for i in intervals)
            assert any(datetime.fromisoformat(i['startAt'])==datetime.fromisoformat(at(12)) and datetime.fromisoformat(i['endAt'])==datetime.fromisoformat(at(18)) for i in intervals)
            assert query()['total']==1
            record('完整替换只清理确认范围，跨边界占用拆分后保留左右部分')
            free=[{'sourceId':'venue','startAt':at(10),'endAt':at(11)},{'sourceId':'venue','startAt':at(11),'endAt':at(12)}]
            commit_batch(admin,'CONFIRMED_FREE_INTERVALS',free,context([venue],10,14))
            assert query(startAt=at(10),endAt=at(12))['total']==1
            assert query(startAt=at(12),endAt=at(13))['total']==0
            gap='/resources/'+venue+'/calendar?'+urllib.parse.urlencode({'from':at(12),'to':at(13)})
            assert call(gap,token=tokens['student'])[1]['data']['coverageStatus']=='UNKNOWN'
            record('空闲导入仅证明文件实际区间，相接证据可合并，范围内空隙仍未知')
            old=context([venue]);old['capturedAt']=stamp(datetime.now(ZONE)-timedelta(hours=1))
            status,body,_=upload(admin,'EXTERNAL_OCCUPANCY',[],old);assert status==201
            assert call('/admin/imports/'+body['data']['batchId']+'/commit','POST',{},admin)[0]==409
            future=context([venue]);future['capturedAt']=stamp(datetime.now(ZONE)+timedelta(days=1))
            assert upload(admin,'EXTERNAL_OCCUPANCY',[],future)[0]==400
            assert upload(admin,'EXTERNAL_OCCUPANCY',[],{'completeness':'COMPLETE'})[0]==400
            for value in [None,False,1]:
                invalid=context([venue]);invalid['completeness']=value
                assert upload(admin,'EXTERNAL_OCCUPANCY',[],invalid)[0]==400
            s.sql("UPDATE availability_coverage SET checked_at=UTC_TIMESTAMP(3)-INTERVAL 1 DAY WHERE resource_id="+venue)
            assert query()['total']==0
            record('迟到快照、未来采集和缺少完整范围被拒绝；过期证据不提供 AVAILABLE')
            bad=[{'sourceId':'venue','sourceKey':'bad','startAt':at(11),'endAt':at(10),'reasonCode':'EXAM'}]
            status,body,_=upload(admin,'EXTERNAL_OCCUPANCY',bad,context([venue]));assert status==201 and body['data']['summary']['errorRows']>0
            assert call('/admin/imports/'+body['data']['batchId']+'/commit','POST',{},admin)[0]==422
            good=[{'sourceId':'venue','sourceKey':'hash','startAt':at(10),'endAt':at(11),'reasonCode':'EXAM'}]
            status,body,_=upload(admin,'EXTERNAL_OCCUPANCY',good,context([venue]));assert status==201
            bid=body['data']['batchId'];storage_key=s.sql("SELECT file_storage_key FROM import_batch WHERE id="+bid)
            (storage/storage_key).write_bytes(b'[]')
            assert call('/admin/imports/'+bid+'/commit','POST',{},admin)[0]==409
            record('非法时段阻断整批确认；服务端保存文件 hash 防止确认时换文件')
            data=c.xlsx([['资源来源编号','占用编号','开始时间','结束时间','原因代码'],['eq1','xlsx',at(10),at(11),'EXAM']])
            commit_batch(admin,'EXTERNAL_OCCUPANCY',None,context([resources['eq1']]),filename='intervals.xlsx',data=data)
            assert query()['total']==0
            record('真实 XLSX 中文表头可导入设备占用，场地空闲也不会返回缺设备组合')
            for change in [dict(startAt=at(11),endAt=at(10)),dict(endAt=stamp(day+timedelta(days=1,hours=1))),dict(startAt=at(10)[:-6]),dict(participantCount=1.2),dict(requiredFeatures=[None]),dict(operatorId=users['student']),dict(equipmentRequirements=[{'type':'MICROPHONE','count':1},{'type':'MICROPHONE','count':1}])]:
                assert call('/availability/search','POST',dict(search,**change),tokens['student'])[0]==400
            record('无偏移、跨自然日、倒置、小数人数、未知字段和重复设备条件被拒绝')
            # 同一资源版本的并发维护只能成功一笔，另一次得到明确版本冲突。
            version=call('/resources/'+venue,token=admin)[1]['data']['version']
            with ThreadPoolExecutor(max_workers=2) as pool:
                results=list(pool.map(lambda _:call('/admin/resources/'+venue+'/opening-rules','PUT',{'expectedVersion':version,'rules':rules},admin),range(2)))
            assert sorted(r[0] for r in results)==[200,409]
            record('真实 InnoDB 下资源规则的版本竞争串行生效')
            # 第二个资源发生范围冲突时，前一个资源的覆盖和占用必须一起回滚。
            commit_batch(admin,'EXTERNAL_OCCUPANCY',[{'sourceId':'eq2','sourceKey':'range-owned','startAt':at(18),'endAt':at(19),'reasonCode':'EXAM'}],context([resources['eq2']],8,20))
            before=s.sql("SELECT COUNT(*) FROM availability_coverage WHERE resource_id="+venue)
            rows=[{'sourceId':'venue','sourceKey':'rollback-new','startAt':at(10),'endAt':at(11),'reasonCode':'COURSE'},
                  {'sourceId':'eq2','sourceKey':'range-owned','startAt':at(10),'endAt':at(11),'reasonCode':'EXAM'}]
            status,body,_=upload(admin,'EXTERNAL_OCCUPANCY',rows,context([venue,resources['eq2']],10,12,'PARTIAL'))
            assert status==201 and body['data']['summary']['errorRows']==0
            status,body,_=call('/admin/imports/'+body['data']['batchId']+'/commit','POST',{},admin)
            assert status==409 and body['code']=='IMPORT_RANGE_CONFLICT'
            assert s.sql("SELECT COUNT(*) FROM availability_coverage WHERE resource_id="+venue)==before
            assert s.sql("SELECT COUNT(*) FROM external_occupancy WHERE resource_id="+venue+" AND source_key='rollback-new'")=='0'
            status,body,_=upload(admin,'EXTERNAL_OCCUPANCY',rows,context([venue,resources['eq2']],10,12))
            assert status==201 and body['data']['summary']['errorRows']==0
            assert call('/admin/imports/'+body['data']['batchId']+'/commit','POST',{},admin)[0]==409
            assert s.sql("SELECT COUNT(*) FROM availability_coverage WHERE resource_id="+venue)==before
            assert s.sql("SELECT COUNT(*) FROM external_occupancy WHERE resource_id="+venue+" AND source_key='rollback-new'")=='0'
            record('多资源导入在后续资源失败时整体回滚，前序占用与覆盖均不残留')
            if os.environ.get('XMUT_WEB_TEST')=='1':
                commit_batch(admin,'EXTERNAL_OCCUPANCY',[],context(all_ids))
                env=os.environ.copy()
                env.update(TEST_ADMIN_USERNAME=private['AUTH_ADMIN_USERNAME'],TEST_ADMIN_PASSWORD=private['AUTH_ADMIN_PASSWORD'],
                           TEST_AVAILABILITY_STUDENT=prefix+'_student',TEST_AVAILABILITY_PASSWORD=passwords['student'],
                           TEST_AVAILABILITY_START=(day+timedelta(hours=10)).strftime('%Y-%m-%d %H:%M:%S'),
                           TEST_AVAILABILITY_END=(day+timedelta(hours=11)).strftime('%Y-%m-%d %H:%M:%S'),
                           TEST_AVAILABILITY_CAMPUS=prefix,TEST_AVAILABILITY_SOURCE=source,TEST_AVAILABILITY_VENUE=prefix+' 演示 venue')
                fd=os.open(s.PRIVATE/'availability-web-test.log',os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
                with os.fdopen(fd,'w') as log:
                    result=subprocess.run(['npm','test','--','tests/availability.spec.ts'],cwd=s.REPO/'web-admin',env=env,stdout=log,stderr=subprocess.STDOUT)
                assert result.returncode==0,'可用性浏览器验收失败，请检查私密 availability-web-test.log'
                record('真实浏览器：学生查询与日历、路由保护、规则策略保存及来源时间导入')
            destination=s.auth.REPORT_DIR;destination.mkdir(parents=True,exist_ok=True)
            (destination/'availability.json').write_text(json.dumps({'date':s.auth.REPORT_DATE,'mode':'dev-real-mysql-redis-nacos','checks':checks,'passed':len(checks),'failed':0,'scope':'M3 可用性基础；不含申请、审批、候补及线上部署'},ensure_ascii=False,indent=2)+'\n')
    finally:
        # 按外键依赖逆序清理，只处理本轮随机来源，不删除管理员和迁移记录。
        files=s.sql("SELECT file_storage_key FROM import_batch WHERE source_system='"+source+"'").splitlines()
        s.sql("DELETE i FROM idempotency_record i JOIN resource r ON i.operation IN (CONCAT('OPENING_RULES:',r.id),CONCAT('EXTERNAL_CREATE:',r.id)) WHERE r.source_system='"+source+"';"
              "DELETE i FROM idempotency_record i JOIN external_occupancy o ON i.operation=CONCAT('EXTERNAL_UPDATE:',o.id) JOIN resource r ON r.id=o.resource_id WHERE r.source_system='"+source+"';"
              "DELETE i FROM idempotency_record i JOIN import_batch b ON i.operation=CONCAT('TEMPORAL_COMMIT:',b.id) OR (i.operation='TEMPORAL_PREVIEW' AND JSON_UNQUOTE(JSON_EXTRACT(i.response_body,'$.batchId'))=CAST(b.id AS CHAR)) WHERE b.source_system='"+source+"';"
              "DELETE FROM idempotency_record WHERE operation LIKE 'POLICY_VERSION:%' AND JSON_UNQUOTE(JSON_EXTRACT(response_body,'$.basis')) LIKE '"+prefix+"%';"
              "DELETE a FROM audit_log a JOIN import_batch b ON a.object_type='IMPORT_BATCH' AND a.object_id=b.id WHERE b.source_system='"+source+"';"
              "DELETE o FROM external_occupancy o JOIN resource r ON r.id=o.resource_id WHERE r.source_system='"+source+"';"
              "DELETE a FROM audit_log a JOIN resource r ON a.object_type='RESOURCE' AND a.object_id=r.id WHERE r.source_system='"+source+"';"
              "DELETE a FROM audit_log a JOIN booking_policy p ON a.object_type='BOOKING_POLICY' AND a.object_id=p.id WHERE p.basis LIKE '"+prefix+"%';"
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
            if filename and '/' not in filename:(storage/filename).unlink(missing_ok=True)
        s.cleanup_identity(prefix)

if __name__=='__main__':
    try:main()
    except (AssertionError,RuntimeError) as failure:
        import traceback
        line=traceback.extract_tb(failure.__traceback__)[-1].lineno
        raise SystemExit(str(failure) or f'可用性验收在第 {line} 行失败，请检查私密日志。')
