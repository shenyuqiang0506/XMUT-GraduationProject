#!/usr/bin/env python3
"""M5 真实 dev 会话/只读工具验收；默认本地模型，显式变量才调用已有真实模型配置。"""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime,timedelta
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import importlib.util
import json
import os
from pathlib import Path
import socket
import subprocess
import threading
import time
import uuid
from zoneinfo import ZoneInfo

spec=importlib.util.spec_from_file_location('review',Path(__file__).with_name('review-fixes-smoke.py'))
r=importlib.util.module_from_spec(spec);spec.loader.exec_module(r)
s=r.s;BASE=r.BASE;AI_PORT=18984;MOCK_PORT=18990

class AiStack(r.ReviewStack):
    def start(self,name,port,overrides=None):
        if name=='ai-service':
            defaults={'AI_PROVIDER':'openai','AI_BASE_URL':f'http://127.0.0.1:{MOCK_PORT}','AI_MODEL':'mock-conditions','AI_API_KEY':'local-mock-key',
                      'AI_HOURLY_LIMIT':'200','SPRING_PROFILES_ACTIVE':'dev,business,ai'}
            defaults.update(overrides or {});overrides=defaults
        super().start(name,port,overrides)
        if name=='gateway':self.start('ai-service',AI_PORT)
    def ready_ai(self,port=AI_PORT):
        deadline=time.monotonic()+90
        while time.monotonic()<deadline:
            try:
                if s.auth.request(f'http://127.0.0.1:{port}/actuator/health/readiness')[0]==200:return
            except (OSError,ValueError):pass
            if self.children[-1][1].poll() is not None:raise RuntimeError('AI 服务启动失败，详见私密日志。')
            time.sleep(.3)
        raise RuntimeError('AI 服务就绪超时')

def main():
    prefix='ai-'+uuid.uuid4().hex[:10];source=prefix.replace('-','_');checks=[];users={};previous=[];initialized=False
    responses={};calls=[];mutex=threading.Lock();samples=[]
    class Model(BaseHTTPRequestHandler):
        def log_message(self,*_):pass
        def do_POST(self):
            body=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            assert body.get('response_format',{}).get('type')=='json_object'
            assert body.get('thinking',{}).get('type')=='disabled'
            assert not body.get('tools'), '模型仅提取条件，不能获得预约写工具'
            context=json.loads(body['messages'][-1]['content']);text=context['currentText']
            with mutex:calls.append(text)
            item=responses.get(text,{'changes':{},'evidence':{}})
            if isinstance(item,tuple):delay,item=item;time.sleep(delay)
            status=500 if item=='unavailable' else 200
            if status==500:reply={'error':{'message':'模拟模型暂不可用','type':'server_error'}}
            else:
                content=item if isinstance(item,str) else json.dumps(item,ensure_ascii=False)
                reply={'id':'mock-'+uuid.uuid4().hex,'object':'chat.completion','created':int(time.time()),'model':body['model'],
                       'choices':[{'index':0,'message':{'role':'assistant','content':content},'finish_reason':'stop'}],
                       'usage':{'prompt_tokens':10,'completion_tokens':10,'total_tokens':20}}
            try:
                self.send_response(status);self.send_header('Content-Type','application/json');self.end_headers();self.wfile.write(json.dumps(reply).encode())
            except OSError:pass
    class Mock(ThreadingHTTPServer):daemon_threads=True
    for port in [MOCK_PORT,AI_PORT,18985,18986]:
        with socket.socket() as probe:
            if probe.connect_ex(('127.0.0.1',port))==0:raise RuntimeError('验收端口被占用，没有停止已有服务。')
    server=Mock(('127.0.0.1',MOCK_PORT),Model);threading.Thread(target=server.serve_forever,daemon=True).start()
    private=s.runtime.load_env(s.PRIVATE/'identity.env');storage=Path(s.runtime.load_env(s.PRIVATE/'auth.env')['IMPORT_STORAGE_DIR'])
    zone=ZoneInfo('Asia/Shanghai');day=(datetime.now(zone)+timedelta(days=1)).replace(hour=0,minute=0,second=0,microsecond=0)
    def at(hour):return (day+timedelta(hours=hour)).isoformat(timespec='milliseconds')
    def record(name):checks.append({'check':name,'result':'PASS'});print('PASS:',name,flush=True)
    def call(path,method='GET',body=None,token=None,key=None,base=BASE):
        return s.auth.request(base+path,method,body,token,prefix+'-request',key or prefix+'-'+uuid.uuid4().hex if method!='GET' else None)
    def expect(result,status):
        assert result[0]==status,(result[0],result[1].get('code'),result[1].get('message'))
        return result[1]['data']
    def login(name,password):return expect(call('/auth/login','POST',{'username':name,'password':password,'deviceId':prefix+'-'+uuid.uuid4().hex,'deviceType':'WEB'}),200)['accessToken']
    def patch(changes,evidence):return {'changes':changes,'evidence':evidence}
    def create(token,base=BASE):return expect(call('/ai/conversations','POST',{'dataMode':'DEMO'},token,base=base),201)
    def send(cid,text,version,token,client=None,base=BASE):
        return call('/ai/conversations/'+cid+'/messages','POST',{'text':text,'clientMessageId':client or prefix+'-'+uuid.uuid4().hex,'expectedConditionVersion':version},token,base=base)
    def coverage(admin,ids):
        import urllib.request
        boundary=uuid.uuid4().hex;context={'from':at(8),'to':at(20),'capturedAt':datetime.now(zone).isoformat(timespec='milliseconds'),
            'completeness':'COMPLETE','sourceDescription':'M5 独立 DEMO 验收假设','resourceIds':ids}
        fields={'importType':'EXTERNAL_OCCUPANCY','sourceSystem':source,'dataMode':'DEMO','fieldMapping':'{}','queryContext':json.dumps(context)}
        parts=[f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n{value}\r\n'.encode() for key,value in fields.items()]
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="ai-coverage.json"\r\nContent-Type: application/json\r\n\r\n[]\r\n--{boundary}--\r\n'.encode())
        request=urllib.request.Request(BASE+'/admin/imports/preview',data=b''.join(parts),method='POST',headers={'Content-Type':'multipart/form-data; boundary='+boundary,'Authorization':'Bearer '+admin,'Idempotency-Key':prefix+'-'+uuid.uuid4().hex,'X-Trace-Id':prefix+'-request'})
        with urllib.request.urlopen(request,timeout=20) as response:body=json.load(response);assert response.status==201
        assert body['data']['summary']['errorRows']==0
        expect(call('/admin/imports/'+body['data']['batchId']+'/commit','POST',{},admin),200)
    def cleanup():
        owned=','.join(users.values()) or '0'
        s.sql('DELETE t FROM ai_tool_call t JOIN ai_conversation c ON c.id=t.conversation_id WHERE c.owner_id IN ('+owned+');'
              'DELETE m FROM ai_message m JOIN ai_conversation c ON c.id=m.conversation_id WHERE c.owner_id IN ('+owned+');'
              'DELETE FROM ai_conversation WHERE owner_id IN ('+owned+');DELETE FROM ai_user_budget WHERE owner_id IN ('+owned+');','ai_db')
        files=s.sql("SELECT file_storage_key FROM import_batch WHERE source_system='"+source+"'").splitlines()
        groups=s.sql("SELECT DISTINCT g.id FROM waitlist_group g JOIN waitlist_group_resource gr ON gr.group_id=g.id JOIN resource r ON r.id=gr.resource_id WHERE r.source_system='"+source+"'").splitlines()
        s.sql("DELETE FROM outbox_event WHERE delivery_scope='"+audience+"';DELETE FROM audit_log WHERE trace_id LIKE '"+prefix+"%' OR actor_id IN ("+owned+");"
              "DELETE o FROM resource_occupancy o JOIN waitlist_entry w ON w.id=o.waitlist_entry_id WHERE w.activity_name LIKE '"+prefix+"%';"
              "DELETE FROM waitlist_entry WHERE activity_name LIKE '"+prefix+"%';"
              "DELETE gr FROM waitlist_group_resource gr JOIN resource r ON r.id=gr.resource_id WHERE r.source_system='"+source+"';"
              "DELETE FROM waitlist_group WHERE id IN ("+(','.join(groups) or '0')+");"
              "DELETE o FROM resource_occupancy o JOIN activity_application a ON a.id=o.application_id WHERE a.activity_name LIKE '"+prefix+"%';"
              "DELETE p FROM approval_record p JOIN activity_application a ON a.id=p.application_id WHERE a.activity_name LIKE '"+prefix+"%';"
              "DELETE r FROM application_resource r JOIN activity_application a ON a.id=r.application_id WHERE a.activity_name LIKE '"+prefix+"%';"
              "DELETE FROM activity_application WHERE activity_name LIKE '"+prefix+"%';DELETE FROM applicant_guard WHERE user_id IN ("+owned+");"
              "DELETE o FROM external_occupancy o JOIN resource r ON r.id=o.resource_id WHERE r.source_system='"+source+"';"
              "DELETE c FROM availability_coverage c JOIN resource r ON r.id=c.resource_id WHERE r.source_system='"+source+"';"
              "DELETE o FROM resource_opening_rule o JOIN resource r ON r.id=o.resource_id WHERE r.source_system='"+source+"';"
              "DELETE f FROM resource_feature f JOIN resource r ON r.id=f.resource_id WHERE r.source_system='"+source+"';"
              "DELETE e FROM equipment_detail e JOIN resource r ON r.id=e.resource_id WHERE r.source_system='"+source+"';"
              "DELETE FROM resource WHERE source_system='"+source+"';"
              "DELETE e FROM import_error e JOIN import_batch b ON b.id=e.batch_id WHERE b.source_system='"+source+"';"
              "DELETE FROM import_batch WHERE source_system='"+source+"';"
              "DELETE m FROM approval_group_member m JOIN approval_group g ON g.id=m.group_id WHERE g.name LIKE '"+prefix+"%';"
              "DELETE FROM approval_group WHERE name LIKE '"+prefix+"%';DELETE FROM booking_policy WHERE basis LIKE '"+prefix+"%';"
              "DELETE FROM idempotency_record WHERE request_key LIKE '"+prefix+"%' OR actor_id IN ("+owned+");")
        for original in previous:s.sql("UPDATE booking_policy p SET active=1 WHERE p.id="+original+" AND NOT EXISTS(SELECT 1 FROM (SELECT scope_code FROM booking_policy WHERE active=1) current_policy WHERE current_policy.scope_code=p.scope_code)")
        for filename in files:
            if filename and '/' not in filename:(storage/filename).unlink(missing_ok=True)
        s.cleanup_identity(prefix)
    audience=''
    try:
        with AiStack('ai-test') as stack:
            stack.ready_ai();time.sleep(2);audience='campus-dev-review-'+stack.group;initialized=True
            previous=s.sql("SELECT id FROM booking_policy WHERE scope_code='DEMO' AND active=1").splitlines()
            admin=login(private['AUTH_ADMIN_USERNAME'],private['AUTH_ADMIN_PASSWORD']);tokens={};passwords={}
            for name,role in [('student','STUDENT'),('teacher','TEACHER'),('other','STUDENT')]:
                passwords[name]=uuid.uuid4().hex+'Aa1!';users[name]=expect(call('/users','POST',{'username':prefix+'_'+name,'displayName':'AI DEMO '+name,'initialPassword':passwords[name],'roleCodes':[role]},admin),201)['id']
                tokens[name]=login(prefix+'_'+name,passwords[name])
            group=expect(call('/admin/approval-groups','POST',{'name':prefix+'-group'},admin),201)['id'];resources={}
            for name,kind,status in [('venue','VENUE','OPEN'),('unknown','VENUE','OPEN'),('hidden','VENUE','DRAFT'),('eq1','EQUIPMENT','OPEN'),('eq2','EQUIPMENT','OPEN'),('pc1','EQUIPMENT','OPEN'),('pc2','EQUIPMENT','OPEN')]:
                body={'resourceType':kind,'sourceSystem':source,'sourceId':name,'name':prefix+' '+name,'campus':prefix,'capacity':40,'sourceBorrowable':'YES',
                      'platformStatus':status,'approvalGroupId':group,'dataMode':'DEMO','features':{'PROJECTOR':'YES'}}
                if kind=='EQUIPMENT':body.update(assetCode=prefix+'-'+name,equipmentType='电脑' if name.startswith('pc') else '无线 话筒',conditionStatus='GOOD')
                resources[name]=expect(call('/admin/resources','POST',body,admin),201)['id']
            settings={'pendingApprovalTtlMinutes':60,'waitlistOfferTtlMinutes':15,'bookingLeadMinutes':0,'cancelLeadMinutes':0,'maxBookingDaysAhead':30,
                      'maxDurationMinutes':180,'maxActiveApplicationsPerUser':10,'coverageFreshnessMinutes':120}
            version=int(s.sql("SELECT COALESCE(MAX(policy_version),0) FROM booking_policy WHERE scope_code='DEMO'"))
            expect(call('/admin/policies/DEMO/versions','POST',{'expectedVersion':version,'settings':settings,'basis':prefix+' DEMO AI 规则假设'},admin),201)
            rules=[{'weekday':day.isoweekday(),'startLocal':'08:00:00','endLocal':'20:00:00','validFrom':day.date().isoformat(),'validTo':day.date().isoformat(),'enabled':True}]
            for resource in resources.values():expect(call('/admin/resources/'+resource+'/opening-rules','PUT',{'expectedVersion':0,'rules':rules},admin),200)
            coverage(admin,[resources[n] for n in ['venue','eq1','eq2','pc1','pc2']])
            assert call('/ai/conversations','POST',{'dataMode':'DEMO'},admin)[0]==403
            key=prefix+'-create';created=expect(call('/ai/conversations','POST',{'dataMode':'DEMO'},tokens['student'],key),201);cid=created['conversationId']
            replay=call('/ai/conversations','POST',{'dataMode':'DEMO'},tokens['student'],key);assert replay[2]['Idempotency-Replayed']=='true' and replay[1]['data']['conversationId']==cid
            assert call('/ai/conversations/'+cid+'/messages',token=tokens['other'])[0]==404
            assert call('/ai/conversations','POST',{'dataMode':'DEMO','ownerId':users['student']},tokens['other'])[0]==400
            assert expect(call('/booking-rules?dataMode=DEMO',token=tokens['student']),200)['configured']
            assert call('/admin/policies/DEMO',token=tokens['student'])[0]==403
            assert call('/resources/details','POST',[resources['venue'],resources['hidden']],tokens['student'])[0]==404
            record('AI 分库迁移、本人会话、学生教师角色、幂等创建和只读工具权限有效')
            vague='校区为'+prefix+'，明天下午六个人讨论两小时，需要投影'
            responses[vague]=patch({'campus':prefix,'date':'明天','startLocal':'14:00','participantCount':6,'durationMinutes':120,'requiredFeatures':{'operation':'MERGE','items':['PROJECTOR']}},
                {'campus':prefix,'date':'明天','startLocal':'下午','participantCount':'六个人','durationMinutes':'两小时','requiredFeatures':'投影'})
            msg=prefix+'-first';first=expect(send(cid,vague,0,tokens['student'],msg),200)
            assert first['status']=='NEED_CLARIFICATION' and 'startLocal' in first['missingFields'] and 'startAt' not in first['conditions']
            replay=send(cid,vague,0,tokens['student'],msg);assert replay[2]['Idempotency-Replayed']=='true' and replay[1]['data']==first and calls.count(vague)==1
            assert send(cid,vague+'改动',0,tokens['student'],msg)[1]['code']=='AI_MESSAGE_ID_REUSED'
            assert s.sql('SELECT requests FROM ai_user_budget WHERE owner_id='+users['student'],'ai_db')=='1'
            record('含糊下午不填默认时刻；重复消息原结果回放、不重复调用模型或计费预算')
            clear='开始时间下午3:00';responses[clear]=patch({'startLocal':'15:00'},{'startLocal':'下午3:00'});readyClient=prefix+'-ready'
            ready=expect(send(cid,clear,first['conditionVersion'],tokens['student'],readyClient),200)
            assert ready['status']=='READY',(ready.get('errorCode'),ready.get('missingFields'))
            assert ready['plans'][0]['venueId']==resources['venue'] and ready['conditions']['startAt']==day.date().isoformat()+'T15:00+08:00'
            assert all(p['venueId']!=resources['unknown'] for p in ready['plans'])
            assert s.sql("SELECT COUNT(*) FROM activity_application WHERE activity_name LIKE '"+prefix+"%'")=='0'
            responses['再加上白板']=patch({'requiredFeatures':{'operation':'ADD','items':['WHITEBOARD']}},{'requiredFeatures':'再加上白板'})
            added=expect(send(cid,'再加上白板',ready['conditionVersion'],tokens['student']),200)
            assert added['conditions']['requiredFeatures']==['PROJECTOR','WHITEBOARD'] and added['status']=='NO_MATCH'
            responses['取消白板']=patch({'requiredFeatures':{'operation':'REMOVE','items':['WHITEBOARD']}},{'requiredFeatures':'取消白板'})
            ready=expect(send(cid,'取消白板',added['conditionVersion'],tokens['student']),200)
            assert ready['conditions']['requiredFeatures']==['PROJECTOR'] and ready['status']=='READY'
            record('v2 追加白板保留投影、局部取消仅删除白板；下午3:00按15:00落库并查询')
            responses['人数改成八']=patch({'participantCount':8},{'participantCount':'人数改成八'})
            changed=expect(send(cid,'人数改成八',ready['conditionVersion'],tokens['student']),200)
            assert changed['status']=='READY' and changed['conditions']['participantCount']==8 and changed['conditions']['startAt']==ready['conditions']['startAt']
            responses['需要两个无线话筒']=patch({'equipmentRequirements':{'operation':'MERGE','items':[{'type':'无线 话筒','count':2}]}},{'equipmentRequirements':'两个无线话筒'})
            equipment=expect(send(cid,'需要两个无线话筒',changed['conditionVersion'],tokens['student']),200)
            assert equipment['status']=='READY' and set(equipment['plans'][0]['equipmentIds'])=={resources['eq1'],resources['eq2']}
            responses['再加两台电脑']=patch({'equipmentRequirements':{'operation':'ADD','items':[{'type':'电脑','count':2}]}},{'equipmentRequirements':'再加两台电脑'})
            more=expect(send(cid,'再加两台电脑',equipment['conditionVersion'],tokens['student']),200)
            assert more['status']=='READY' and len(more['conditions']['equipmentRequirements'])==2
            assert set(more['plans'][0]['equipmentIds'])=={resources[n] for n in ['eq1','eq2','pc1','pc2']}
            responses['取消电脑']=patch({'equipmentRequirements':{'operation':'REMOVE','items':['电脑']}},{'equipmentRequirements':'取消电脑'})
            equipment=expect(send(cid,'取消电脑',more['conditionVersion'],tokens['student']),200)
            assert equipment['status']=='READY' and equipment['conditions']['equipmentRequirements']==[{'type':'无线 话筒','count':2}]
            responses['讨论两个半小时']=patch({'durationMinutes':150},{'durationMinutes':'两个半小时'})
            equipment=expect(send(cid,'讨论两个半小时',equipment['conditionVersion'],tokens['student']),200)
            assert equipment['status']=='READY' and equipment['conditions']['durationMinutes']==150
            assert equipment['conditions']['endAt']==day.date().isoformat()+'T17:30+08:00'
            saved=expect(call('/ai/conversations/'+cid+'/messages',token=tokens['student']),200)
            assert saved['conversation']['conditions']==equipment['conditions']
            assert saved['conversation']['version']==equipment['conditionVersion']
            assert s.sql("SELECT prompt_version FROM ai_message WHERE conversation_id="+cid+" ORDER BY id DESC LIMIT 1",'ai_db')=='conditions-v2'
            record('追加电脑保留话筒，局部取消设备不丢其他项；两个半小时与v2提示版本持久化并可恢复')
            record('多轮只改明确条件；真实组合和设备名称来自工具，UNKNOWN 场地不进入方案，AI 不写预约')
            if os.getenv('XMUT_WEB_TEST')=='1':
                responses['浏览器六人']=patch({'participantCount':6},{'participantCount':'六人'})
                env=dict(os.environ,TEST_AI_PREFIX=prefix,TEST_AI_USER=prefix+'_student',TEST_AI_PASSWORD=passwords['student'],
                         TEST_AI_CONVERSATION_ID=cid,TEST_AI_GATEWAY_BASE=BASE,TEST_AI_DATE=day.date().isoformat())
                env['VITE_TEST_DATA_MODE']='DEMO'
                # 浏览器日志仅落私密目录；凭据通过环境传递，不进入参数、截图或公开报告。
                log_path=s.PRIVATE/'ai-test-logs/browser.log'
                fd=os.open(log_path,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
                with os.fdopen(fd,'w') as log:
                    browser=subprocess.run(['npm','test','--','tests/ai-live.spec.ts'],cwd=s.REPO/'web-admin',env=env,stdout=log,stderr=subprocess.STDOUT)
                if browser.returncode:raise RuntimeError('AI 真实浏览器验收失败，详见私密 browser.log')
                stats=json.loads((s.REPO/'web-admin/test-results/results.json').read_text())['stats']
                assert stats['expected']==2 and not stats['unexpected'] and not stats['skipped']
                record('真实浏览器验证读取故障保留条件、新建后列表故障不串会话、2000字符整组提交并取消')
            responses['不用再加网络']=patch({'requiredFeatures':{'operation':'ADD','items':['NETWORK']}},{'requiredFeatures':'不用再加网络'})
            negated=expect(send(cid,'不用再加网络',equipment['conditionVersion'],tokens['student']),200)
            assert negated['errorCode']=='AI_UNGROUNDED_CONDITION' and negated['conditions']==equipment['conditions']
            equipment=negated
            record('模型错误标记否定追加时服务器拒绝修改，保留全部旧条件')
            responses['注入资源']=patch({'resourceIds':[resources['hidden']]},{'resourceIds':'注入资源'})
            rejected=expect(send(cid,'注入资源',equipment['conditionVersion'],tokens['student']),200)
            assert rejected['status']=='FAILED' and rejected['errorCode']=='AI_INVALID_OUTPUT' and not rejected['plans']
            responses['非法输出']='not-json';malformed=expect(send(cid,'非法输出',rejected['conditionVersion'],tokens['student']),200)
            assert malformed['errorCode']=='AI_INVALID_OUTPUT' and malformed['conditions']==equipment['conditions']
            responses['模型故障']='unavailable';failed=expect(send(cid,'模型故障',malformed['conditionVersion'],tokens['student']),200)
            assert failed['status']=='FAILED' and failed['conditions']==equipment['conditions'] and calls.count('模型故障')==1
            record('资源 ID 注入、非法 JSON 与模型故障均降级，原条件保留，没有自动重试模型')
            slow=create(tokens['teacher'])['conversationId'];responses['缓慢模型']=(12,patch({},{}))
            with ThreadPoolExecutor(max_workers=1) as pool:
                future=pool.submit(send,slow,'缓慢模型',0,tokens['teacher'],prefix+'-slow')
                deadline=time.monotonic()+10
                while '缓慢模型' not in calls and time.monotonic()<deadline:time.sleep(.1)
                assert send(slow,'缓慢模型',0,tokens['teacher'],prefix+'-slow')[1]['code']=='AI_MESSAGE_PROCESSING'
                assert send(slow,'另一消息',0,tokens['teacher'])[1]['code']=='AI_CONVERSATION_BUSY'
                timeout=expect(future.result(timeout=15),200);assert timeout['status']=='FAILED'
            assert expect(send(slow,'缓慢模型',0,tokens['teacher'],prefix+'-slow'),200)==timeout and calls.count('缓慢模型')==1
            record('模型超时有界；单会话并发拒绝、同消息在途不会重复调用，失败结果可回放')
            interrupted=create(tokens['other'])['conversationId'];claim=str(uuid.uuid4());message=prefix+'-orphan'
            s.sql(f"UPDATE ai_conversation SET processing_token='{claim}',processing_until=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE id={interrupted};"
                  f"INSERT INTO ai_message(conversation_id,client_message_id,request_hash,content,base_version,status,claim_token,prompt_version,model_name,trace_id,accepted_conditions) VALUES ({interrupted},'{message}',REPEAT('a',64),'中断现场',0,'PROCESSING','{claim}','conditions-v2','mock','{prefix}-request',JSON_OBJECT('participantCount',9));",'ai_db')
            recovered=expect(call('/ai/conversations/'+interrupted+'/messages',token=tokens['other']),200)
            assert recovered['latest']['errorCode']=='AI_PROCESSING_EXPIRED' and recovered['conversation']['conditions']['participantCount']==9 and recovered['conversation']['version']==1
            record('过期处理租约按请求恢复，检查点保留已确认条件，不重放可能已收费的模型调用')
            plan=saved['latest']['plans'][0]
            occupied=expect(call('/admin/resources/'+resources['venue']+'/external-occupancies','POST',{'sourceSystem':'MANUAL','sourceKey':prefix+'-conflict',
                'startAt':plan['startAt'],'endAt':plan['endAt'],'reasonCode':'EXAM'},admin),201)
            responses['换一间']=patch({},{})
            no_match=expect(send(cid,'换一间',failed['conditionVersion'],tokens['student']),200)
            assert no_match['status']=='NO_MATCH' and not no_match['plans'] and 'COVERAGE_UNKNOWN' in no_match['reasonCodes']
            record('无匹配返回可见范围内的占用/覆盖原因类别，未知来源不会成为备选方案')
            ordinary={'activityName':prefix+' 用户确认','description':'AI 只读方案确认回归','venueId':plan['venueId'],'equipmentIds':plan['equipmentIds'],
                      'startAt':plan['startAt'],'endAt':plan['endAt'],'participantCount':plan['participantCount'],'requiredFeatures':plan['requiredFeatures']}
            assert call('/applications','POST',ordinary,tokens['student'])[1]['code']=='RESOURCE_CONFLICT'
            waiting=expect(call('/waitlists','POST',dict(ordinary,activityName=prefix+' 候补模式验证'),tokens['student']),201)
            expect(call('/waitlists/'+waiting['waitlistId']+'/cancel','POST',{'expectedVersion':0,'reason':'DEMO模式验证清理'},tokens['student']),200)
            expect(call('/admin/external-occupancies/'+occupied['id'],'PATCH',{'expectedVersion':0,'active':False,'startAt':plan['startAt'],'endAt':plan['endAt'],'reasonCode':'EXAM'},admin),200)
            assert call('/applications','POST',dict(ordinary,description='测'*2001),tokens['student'])[0]==400
            ordinary['description']='测'*2000
            application=expect(call('/applications','POST',ordinary,tokens['student']),201);assert application['status']=='PENDING_APPROVAL'
            assert len(expect(call('/applications/'+application['applicationId'],token=tokens['student']),200)['description'])==2000
            expect(call('/applications/'+application['applicationId']+'/cancel','POST',{'expectedVersion':0,'reason':'DEMO 清理'},tokens['student']),200)
            record('AI 查询后新增占用仍阻止普通申请；资源恢复后整组提交并等待审批')
            assert expect(call('/applications?size=1',token=tokens['student']),200)['total']==(2 if os.getenv('XMUT_WEB_TEST')=='1' else 1)
            assert expect(call('/waitlists?size=1',token=tokens['student']),200)['total']==1
            # 同一 dev 凭据与库，暂不启用 dev/test profile，执行正式 DataModes 的禁止 DEMO 分支；不启动 prod。
            restricted='http://127.0.0.1:18986'
            stack.start('booking-service',18986,{'SPRING_PROFILES_ACTIVE':'business,nacos','SPRING_CLOUD_NACOS_DISCOVERY_REGISTER_ENABLED':'false'})
            stack.ready_ai(18986)
            for path in ['/applications?size=1','/applications?page=2&size=1','/waitlists?size=1','/waitlists?page=2&size=1']:
                listed=expect(call(path,token=tokens['student'],base=restricted),200)
                assert listed['total']==0 and listed['items']==[]
            administrative=expect(call('/admin/applications?size=1',token=admin,base=restricted),200)
            assert administrative['total']==int(s.sql("SELECT COUNT(*) FROM activity_application WHERE data_mode='REAL'"))
            assert all(item['dataMode']=='REAL' for item in administrative['items'])
            assert call('/applications/'+application['applicationId'],token=tokens['student'],base=restricted)[0]==403
            assert call('/waitlists/'+waiting['waitlistId'],token=tokens['student'],base=restricted)[0]==403
            record('真实MySQL申请和候补列表在统计分页前排除DEMO，管理列表与详情权限一致；未启动prod')
            lowbase='http://127.0.0.1:18985';stack.start('ai-service',18985,{'AI_HOURLY_LIMIT':'2','SPRING_CLOUD_NACOS_DISCOVERY_REGISTER_ENABLED':'false'})
            stack.ready_ai(18985);low=create(tokens['other'],lowbase)['conversationId']
            responses['六人']=patch({'participantCount':6},{'participantCount':'六人'})
            firstlow=expect(send(low,'六人',0,tokens['other'],prefix+'-low1',lowbase),200)
            secondlow=expect(send(low,'六人',firstlow['conditionVersion'],tokens['other'],prefix+'-low2',lowbase),200)
            assert send(low,'六人',secondlow['conditionVersion'],tokens['other'],base=lowbase)[1]['code']=='AI_RATE_LIMITED'
            assert expect(send(low,'六人',0,tokens['other'],prefix+'-low1',lowbase),200)==firstlow
            record('跨会话按用户限制模型预算；额度耗尽后既有消息仍可回放')
            revoked=create(tokens['other'])['conversationId'];responses['状态核验：六人']=(2,patch({'participantCount':6},{'participantCount':'六人'}))
            with ThreadPoolExecutor(max_workers=1) as pool:
                future=pool.submit(send,revoked,'状态核验：六人',0,tokens['other'])
                deadline=time.monotonic()+10
                while '状态核验：六人' not in calls and time.monotonic()<deadline:time.sleep(.1)
                expect(call('/users/'+users['other']+'/status','PATCH',{'expectedVersion':0,'status':'DISABLED'},admin),200)
                assert future.result(timeout=15)[0]==401
            assert s.sql("SELECT COUNT(*) FROM ai_message WHERE conversation_id="+revoked+" AND status='FAILED' AND JSON_LENGTH(JSON_EXTRACT(result_json,'$.plans'))=0",'ai_db')=='1'
            record('模型等待期间停用用户，提交前再次核验会话并拒绝返回方案')
            history_base=BASE
            if os.getenv('XMUT_AI_REAL_TEST')=='1':
                for n,p,_ in stack.children:
                    if n=='ai-service' and p.poll() is None:
                        p.terminate()
                        try:p.wait(timeout=15)
                        except Exception:p.kill();p.wait(timeout=5)
                real=s.runtime.service_env('dev','ai-service');assert real.get('AI_PROVIDER')=='openai'
                overrides={k:real[k] for k in ['AI_PROVIDER','AI_BASE_URL','AI_MODEL','AI_API_KEY']}
                overrides.update(SPRING_CLOUD_NACOS_DISCOVERY_REGISTER_ENABLED='false',AI_HOURLY_LIMIT='200')
                stack.start('ai-service',18985,overrides);stack.ready_ai(18985)
                history_base=lowbase
                actual=create(tokens['teacher'],lowbase)['conversationId']
                question='校区为'+prefix+'，明天14:00开始，六个人讨论两小时，需要投影';started=time.monotonic()
                result=expect(send(actual,question,0,tokens['teacher'],base=lowbase),200)
                samples.append({'input':question,'manualExpected':{'status':'READY','participantCount':6,'date':day.date().isoformat(),'startLocal':'14:00','durationMinutes':120},
                    'actualStatus':result.get('status'),'conditions':result.get('conditions'),'planCount':len(result.get('plans',[])),
                    'errorCode':result.get('errorCode'),'elapsedMs':round((time.monotonic()-started)*1000),'dataMode':'DEMO','model':real['AI_MODEL'],'promptVersion':'conditions-v2'})
                assert result['status']=='READY',(result.get('status'),result.get('errorCode'),result.get('missingFields'))
                original=result['conditions'];started=time.monotonic()
                result=expect(send(actual,'人数改成八',result['conditionVersion'],tokens['teacher'],base=lowbase),200)
                samples.append({'input':'人数改成八','manualExpected':{'status':'READY','participantCount':8,'otherConditionsUnchanged':True},
                    'actualStatus':result.get('status'),'conditions':result.get('conditions'),'planCount':len(result.get('plans',[])),
                    'errorCode':result.get('errorCode'),'elapsedMs':round((time.monotonic()-started)*1000),'dataMode':'DEMO','model':real['AI_MODEL'],'promptVersion':'conditions-v2'})
                assert result['status']=='READY' and result['conditions']['participantCount']==8
                assert all(result['conditions'].get(k)==v for k,v in original.items() if k!='participantCount')
                record('真实 DeepSeek 经正式会话和只读工具生成方案，多轮改人数保留时间和设施')
            row=expect(call('/resources/'+resources['venue'],token=admin),200)
            expect(call('/admin/resources/'+resources['venue'],'PATCH',{'expectedVersion':row['version'],'platformStatus':'CLOSED'},admin),200)
            redacted=expect(send(cid,clear,first['conditionVersion'],tokens['student'],readyClient,history_base),200)
            assert redacted['errorCode']=='PLAN_RECHECK_REQUIRED' and not redacted['plans']
            record('幂等旧方案沿用当前权限；不可见资源被隐藏，不能用 AI 对话绕过目录范围')
            assert s.sql("SELECT COUNT(*) FROM ai_tool_call t JOIN ai_conversation c ON c.id=t.conversation_id WHERE c.owner_id IN ("+','.join(users.values())+") AND t.tool_name NOT IN ('getVisibleOptions','getBookingRules','searchAvailableResources','getResourceDetails')",'ai_db')=='0'
            expect(call('/ai/conversations/'+cid+'/close','POST',{'expectedConditionVersion':no_match['conditionVersion']},tokens['student'],base=history_base),200)
            assert send(cid,'六人',no_match['conditionVersion']+1,tokens['student'],base=history_base)[1]['code']=='AI_CONVERSATION_CLOSED'
            record('只读工具轨迹与检查时间落库，关闭会话后不能发送新消息')
    finally:
        server.shutdown();server.server_close()
        if initialized:cleanup()
    destination=s.auth.REPORT_DIR/'ai';destination.mkdir(parents=True,exist_ok=True)
    filename='real-model.json' if os.getenv('XMUT_AI_REAL_TEST')=='1' else 'mock-model.json'
    (destination/filename).write_text(json.dumps({'date':s.auth.REPORT_DATE,'checks':checks,'samples':samples,'realInfrastructure':True,
        'model':'DeepSeek + local fault model' if os.getenv('XMUT_AI_REAL_TEST')=='1' else 'local OpenAI-compatible fault model',
        'productionStarted':False,'fixturesCleaned':True,'testProcessesStopped':True,'realDataModified':False,'leaseRecovery':'simulated interrupted committed claim'},ensure_ascii=False,indent=2)+'\n')
    print('完成',len(checks),'组 AI 验收；临时资料及进程已清理。',flush=True)

if __name__=='__main__':main()
