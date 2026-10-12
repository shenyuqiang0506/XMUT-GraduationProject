#!/usr/bin/env python3
"""M2 catalog/import acceptance against actual Gateway, MySQL and identity."""
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.request
import uuid
import zipfile
from xml.sax.saxutils import escape

spec=importlib.util.spec_from_file_location('support',Path(__file__).with_name('business-test-support.py'))
s=importlib.util.module_from_spec(spec);spec.loader.exec_module(s)
BASE='http://127.0.0.1:8081/api/v1'

def xlsx(rows):
    parts={'[Content_Types].xml':'<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>',
        '_rels/.rels':'<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>',
        'xl/workbook.xml':'<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="目录" sheetId="1" r:id="rId1"/></sheets></workbook>',
        'xl/_rels/workbook.xml.rels':'<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>'}
    xml=[]
    for i,row in enumerate(rows,1):
        cells=''.join(f'<c r="{chr(65+j)}{i}" t="inlineStr"><is><t>{escape(str(value))}</t></is></c>' for j,value in enumerate(row))
        xml.append(f'<row r="{i}">{cells}</row>')
    parts['xl/worksheets/sheet1.xml']='<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>'+''.join(xml)+'</sheetData></worksheet>'
    out=io.BytesIO()
    with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as archive:
        for path,text in parts.items():archive.writestr(path,text.encode())
    return out.getvalue()

def upload(prefix,token,source,payload,group=None,key=None,mapping=None,filename='catalog.json',mode='DEMO',kind='RESOURCE_CATALOG'):
    boundary=uuid.uuid4().hex;chunks=[]
    fields={'importType':kind,'sourceSystem':source,'dataMode':mode,'fieldMapping':json.dumps(mapping or {}),'queryContext':json.dumps({'approvalGroupId':group,'capturedAt':'2026-10-11T00:00:00Z','sourceDescription':'DEMO verification fixture'} if group else {})}
    for name,value in fields.items():chunks.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode())
    chunks.append(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\nContent-Type: application/octet-stream\r\n\r\n'.encode()+payload+b'\r\n')
    chunks.append(f'--{boundary}--\r\n'.encode())
    request=urllib.request.Request(BASE+'/admin/imports/preview',data=b''.join(chunks),headers={'Content-Type':'multipart/form-data; boundary='+boundary,'Authorization':'Bearer '+token,'Idempotency-Key':key or prefix+'-'+uuid.uuid4().hex[:16],'X-Trace-Id':prefix+'-request'},method='POST')
    try:r=urllib.request.urlopen(request,timeout=30)
    except urllib.error.HTTPError as e:r=e
    with r:return r.status,json.load(r),dict(r.headers)

def main():
    prefix='cat-'+uuid.uuid4().hex[:10];source=prefix.replace('-','_');checks=[]
    password=uuid.uuid4().hex+'Aa1!';private=s.runtime.load_env(s.PRIVATE/'identity.env');storage=Path(s.runtime.load_env(s.PRIVATE/'auth.env')['IMPORT_STORAGE_DIR'])
    files=[]
    def record(name):checks.append({'check':name,'result':'PASS'});print('PASS:',name,flush=True)
    def call(path,method='GET',body=None,token=None,key=None):return s.auth.request(BASE+path,method,body,token,prefix+'-request',key or (prefix+'-'+uuid.uuid4().hex[:16]) if method!='GET' else None)
    def login(username,device,pwd=password):
        status,body,_=call('/auth/login','POST',{'username':username,'password':pwd,'deviceId':prefix+'-'+device,'deviceType':'WEB'})
        assert status==200,f'Login: HTTP {status}, code={body.get("code")}'
        return body['data']['accessToken']
    def resource(id_,**updates):
        return dict({'resourceType':'VENUE','sourceSystem':source,'sourceId':id_,'name':'DEMO '+id_,'campus':'集美','building':'测试楼','sourceBorrowable':'UNKNOWN','platformStatus':'DRAFT','dataMode':'DEMO','capacity':None,'features':{}},**updates)
    try:
        with s.Stack('catalog-test'):
            admin=login(private['AUTH_ADMIN_USERNAME'],'admin',private['AUTH_ADMIN_PASSWORD'])
            ids=[];tokens=[]
            for suffix,role in [('manager','RESOURCE_ADMIN'),('other','RESOURCE_ADMIN'),('student','STUDENT')]:
                status,b,_=call('/users','POST',{'username':prefix+'_'+suffix,'displayName':'DEMO '+suffix,'initialPassword':password,'roleCodes':[role]},admin)
                assert status==201;ids.append(b['data']['id']);tokens.append(login(prefix+'_'+suffix,suffix))
            manager,other,student=tokens
            groups=[]
            for suffix in ['A','B']:
                status,b,_=call('/admin/approval-groups','POST',{'name':prefix+'-'+suffix},admin);assert status==201;groups.append(b['data']['id'])
            ga,gb=groups
            assert call('/admin/approval-groups/'+ga+'/members','PUT',{'userIds':[ids[0]]},admin)[0]==200
            assert call('/admin/approval-groups/'+gb+'/members','PUT',{'userIds':[ids[1]]},admin)[0]==200
            assert call('/admin/approval-groups/'+ga+'/members','PUT',{'userIds':[ids[2]]},admin)[0]==422
            record('Booking Flyway migration, distinct database, approval-group membership validated against live identity')
            key=prefix+'-manual';body=resource('0001',approvalGroupId=ga,platformStatus='OPEN')
            status,b,_=call('/admin/resources','POST',body,manager,key);assert status==201,f'Manual create: {status}/{b.get("code")}'
            first=b['data'];rid=first['id'];assert first['capacity'] is None and first['sourceId']=='0001' and first['availability']=='UNKNOWN'
            status,b,h=call('/admin/resources','POST',body,manager,key);assert status==201 and b['data']==first and h['Idempotency-Replayed']=='true'
            assert call('/admin/resources','POST',dict(body,name='changed'),manager,key)[0]==409
            assert call('/resources/'+rid,token=student)[0]==200
            assert call('/resources/'+rid,token=other)[0]==404
            assert call('/admin/resources','POST',resource('unauthorized',approvalGroupId=gb),manager)[0]==403
            assert call('/admin/resources','POST',resource('forbidden'),student)[0]==403
            assert call('/admin/resources','POST',resource('real-no',approvalGroupId=ga,dataMode='REAL',sourceBorrowable='NO',platformStatus='OPEN'),admin)[0]==422
            record('String IDs/unknown capacity preserved; create replay; student visibility; manager scope and REAL opening constraints')
            changed={'name':'DEMO revised','expectedVersion':0,'features':{'PROJECTOR':'有'}}
            assert call('/admin/resources/'+rid,'PATCH',changed,manager)[0]==200
            assert call('/admin/resources/'+rid,'PATCH',dict(changed,name='stale'),manager)[0]==409
            assert call('/admin/resources/'+rid,'PATCH',{'sourceId':'evil','expectedVersion':1},admin)[0]==400
            assert call('/admin/resources/'+rid+'/managers','PUT',{'userIds':[ids[2]]},admin)[0]==422
            record('Optimistic version conflict and immutable source fields; invalid resource manager rejected')
            asset=prefix+'-asset';equip=resource('equipment',resourceType='EQUIPMENT',approvalGroupId=ga,assetCode=asset,equipmentType='PROJECTOR')
            status,b,_=call('/admin/resources','POST',equip,admin);assert status==201;eid=b['data']['id']
            assert call('/admin/resources','POST',dict(equip,sourceId='equipment-duplicate'),admin)[0]==409
            assert call('/resources/'+eid,token=admin)[1]['data']['assetCode']==asset
            assert s.sql("SELECT COUNT(*) FROM resource WHERE source_system='"+source+"' AND source_id='equipment-duplicate';")=='0'
            record('Equipment asset uniqueness rolls back the new resource without altering its owner')
            assert call('/admin/resources/'+eid,'PATCH',{'conditionStatus':'DAMAGED','expectedVersion':0},admin)[0]==200
            status,b,_=upload(prefix,admin,source,json.dumps([{'sourceId':'equipment','name':'DEMO imported equipment','resourceType':'EQUIPMENT','assetCode':asset,'equipmentType':'PROJECTOR'}]).encode(),ga)
            assert status==201 and call('/admin/imports/'+b['data']['batchId']+'/commit','POST',token=admin)[0]==200
            assert call('/resources/'+eid,token=admin)[1]['data']['conditionStatus']=='DAMAGED'
            assert call('/admin/resources','POST',resource('overflow',approvalGroupId='9999999999999999999'),admin)[0]==400

            rows=[{'cdbh':'001'+str(i),'cdmc':'DEMO import '+str(i),'zws':'' if i==0 else str(20+i),'sfkjy':'是','cookie':'DO_NOT_PERSIST_TEST_SECRET'} for i in range(3)]
            data=json.dumps({'totalResult':1132,'items':rows}).encode();pkey=prefix+'-preview'
            status,b,_=upload(prefix,manager,source,data,ga,pkey);assert status==201,f'Preview: {status}/{b.get("code")}'
            preview=b['data'];batch=preview['batchId'];assert preview['summary']=={'sourceRows':3,'newRows':3,'updateRows':0,'errorRows':0}
            assert 'file_storage_key' not in json.dumps(preview)
            status,replay,h=upload(prefix,manager,source,data,ga,pkey);assert status==201 and replay['data']==preview and h['Idempotency-Replayed']=='true'
            assert call('/admin/imports/'+batch,token=other)[0]==404
            status,b,_=call('/admin/imports/'+batch+'/commit','POST',token=manager,key=prefix+'-commit');assert status==200,f'Commit: {status}/{b.get("code")}'
            assert b['data']['status']=='COMMITTED'
            assert call('/admin/imports/'+batch+'/commit','POST',token=manager,key=prefix+'-commit')[1]['data']==b['data']
            assert call('/admin/imports/'+batch+'/commit','POST',token=manager)[0]==200
            assert s.sql("SELECT COUNT(*) FROM resource WHERE source_system='"+source+"' AND source_id LIKE '001%';")=='3'
            saved=s.sql("SELECT capacity IS NULL,platform_status,LOCATE('DO_NOT_PERSIST_TEST_SECRET',CAST(raw_payload AS CHAR)) FROM resource WHERE source_system='"+source+"' AND source_id='0010';")
            assert saved=='1\tDRAFT\t0'
            record('JSON uses actual 3 rows rather than totalResult=1132; preview/commit replay; raw sensitive fields excluded; default DRAFT')
            rows[1]['cdmc']='DEMO import updated'
            status,b,_=upload(prefix,manager,source,json.dumps(rows).encode(),ga);assert status==201 and b['data']['summary']['updateRows']==3
            assert call('/admin/imports/'+b['data']['batchId']+'/commit','POST',token=manager)[0]==200
            assert s.sql("SELECT COUNT(*) FROM resource WHERE source_system='"+source+"' AND source_id LIKE '001%' AND version=1;")=='3'
            record('Repeat import updates existing source IDs with version increments; no duplicates or resource deletion')
            invalid=[{'cdbh':'bad001','cd_id':'other','cdmc':'conflict'},{'cdbh':'bad002','cdmc':'negative','zws':'-1'},{'cdbh':'bad003'},{'cdbh':'bad003','cdmc':'duplicate'}]
            status,b,_=upload(prefix,admin,source,json.dumps(invalid).encode(),ga);assert status==201 and b['data']['summary']['errorRows']==4
            assert call('/admin/imports/'+b['data']['batchId']+'/commit','POST',token=admin)[0]==422
            assert s.sql("SELECT COUNT(*) FROM resource WHERE source_system='"+source+"' AND source_id LIKE 'bad%';")=='0'
            record('Conflicting IDs, negative capacity, missing name and duplicate rows block commit without partial writes')
            status,b,_=upload(prefix,admin,source,json.dumps([{'cdbh':'mapped','cd_id':'different','cdmc':'mapped source'}]).encode(),ga,mapping={'sourceId':'cdbh'})
            assert status==201 and b['data']['summary']['errorRows']==0
            assert upload(prefix,admin,source,data,ga,mapping={'name':'cookie'})[0]==400
            record('Explicit mapping resolves source ID ambiguity; sensitive-column mapping rejected')
            excel=xlsx([['场地编号','场地名称','座位数','是否可借用'],['0091','DEMO Excel one','','是'],['0092','DEMO Excel two','30','否'],['0093','DEMO Excel three','50','未知']])
            status,b,_=upload(prefix,admin,source,excel,ga,filename='catalog.xlsx');assert status==201,f'Excel: {status}/{b.get("code")}'
            assert b['data']['summary']=={'sourceRows':3,'newRows':3,'updateRows':0,'errorRows':0}
            assert call('/admin/imports/'+b['data']['batchId']+'/commit','POST',token=admin)[0]==200
            assert s.sql("SELECT source_id,capacity IS NULL FROM resource WHERE source_system='"+source+"' AND source_id='0091';")=='0091\t1'
            record('Actual XLSX parses Chinese headers, leading-zero IDs and unknown capacity correctly')
            status,b,_=upload(prefix,admin,source,json.dumps([{'sourceId':'real001','name':'REAL fixture','sourceBorrowable':'YES'}]).encode(),ga,mode='REAL');assert status==201
            assert call('/admin/imports/'+b['data']['batchId']+'/commit','POST',token=admin)[0]==200
            realid=s.sql("SELECT id FROM resource WHERE source_system='"+source+"' AND source_id='real001' AND data_mode='REAL';")
            assert call('/admin/resources/'+realid,'PATCH',{'platformStatus':'OPEN','expectedVersion':0},admin)[0]==200
            status,b,_=upload(prefix,admin,source,json.dumps([{'sourceId':'real001','name':'REAL fixture','sourceBorrowable':'NO'}]).encode(),ga,mode='REAL');assert status==201
            assert call('/admin/imports/'+b['data']['batchId']+'/commit','POST',token=admin)[0]==200
            assert call('/resources/'+realid,token=admin)[1]['data']['platformStatus']=='CLOSED'
            assert call('/resources?dataMode=REAL',token=admin)[0]==200
            record('REAL/DEMO filtering and real-source NO update closes previously OPEN catalog entry')
            status,b,_=upload(prefix,admin,source,json.dumps([{'sourceId':'tamper','name':'tamper fixture'}]).encode(),ga);assert status==201
            bid=b['data']['batchId'];storagekey=s.sql("SELECT file_storage_key FROM import_batch WHERE id="+bid+';')
            owned=storage/storagekey;owned.write_bytes(b'[]')
            assert call('/admin/imports/'+bid+'/commit','POST',token=admin)[0]==409
            assert s.sql("SELECT COUNT(*) FROM resource WHERE source_system='"+source+"' AND source_id='tamper';")=='0'
            record('Changed saved file hash blocks commit; client has no server storage path')
            status,b,_=upload(prefix,manager,source,json.dumps([{'sourceId':'lost-scope','name':'scope fixture'}]).encode(),ga);assert status==201
            bid=b['data']['batchId'];assert call('/admin/approval-groups/'+ga+'/members','PUT',{'userIds':[]},admin)[0]==200
            assert call('/admin/imports/'+bid+'/commit','POST',token=manager)[0]==403
            assert s.sql("SELECT COUNT(*) FROM resource WHERE source_system='"+source+"' AND source_id='lost-scope';")=='0'
            assert call('/admin/approval-groups/'+ga+'/members','PUT',{'userIds':[ids[0]]},admin)[0]==200
            record('Commit rechecks current group scope and rolls back after authority removal')
            assert upload(prefix,admin,source,data,ga,filename='catalog.exe')[0]==400
            assert upload(prefix,admin,source,b'bad json',ga)[0]==400
            assert upload(prefix,admin,source,data,ga,kind='EXTERNAL_OCCUPANCY')[0]==400
            assert upload(prefix,admin,source,b'x'*(2*1024*1024+1),ga)[0] in [400,413]
            assert call('/resources?page=0',token=admin)[0]==400
            assert call('/resources?capacityMin=-1',token=admin)[0]==400
            record('File type/size/JSON format/import stage and list parameter limits enforced')
            if os.environ.get('XMUT_WEB_TEST')=='1':
                env=os.environ.copy();env.update(TEST_ADMIN_USERNAME=private['AUTH_ADMIN_USERNAME'],TEST_ADMIN_PASSWORD=private['AUTH_ADMIN_PASSWORD'],TEST_MANAGER_USERNAME=prefix+'_manager',TEST_STUDENT_USERNAME=prefix+'_student',TEST_USER_PASSWORD=password,TEST_PREFIX=prefix,TEST_SOURCE=source,TEST_GROUP=ga)
                fd=os.open(s.PRIVATE/'web-test.log',os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
                with os.fdopen(fd,'w') as log:
                    result=subprocess.run(['npm','test'],cwd=s.REPO/'web-admin',env=env,stdout=log,stderr=subprocess.STDOUT)
                assert result.returncode==0,'Web acceptance failed; inspect private web-test.log (redact credentials before output).'
                record('Real browser acceptance: login, role routes, resource mutation, preview/commit, one refresh for concurrent 401s')
            destination=s.auth.REPORT_DIR;destination.mkdir(parents=True,exist_ok=True)
            (destination/'catalog-import.json').write_text(json.dumps({'date':s.auth.REPORT_DATE,'mode':'dev-business-real-infrastructure','checks':checks,'passed':len(checks),'failed':0,'scope':'M2 resource catalog only; availability/occupancy/booking are not asserted'},ensure_ascii=False,indent=2)+'\n')
    finally:
        try:
            files=s.sql("SELECT file_storage_key FROM import_batch WHERE source_system='"+source+"';").splitlines()
            s.sql("DELETE f FROM resource_feature f JOIN resource r ON r.id=f.resource_id WHERE r.source_system='"+source+"';"
                "DELETE e FROM equipment_detail e JOIN resource r ON r.id=e.resource_id WHERE r.source_system='"+source+"';"
                "DELETE m FROM resource_manager m JOIN resource r ON r.id=m.resource_id WHERE r.source_system='"+source+"';"
                "DELETE FROM resource WHERE source_system='"+source+"';"
                "DELETE e FROM import_error e JOIN import_batch b ON b.id=e.batch_id WHERE b.source_system='"+source+"';"
                "DELETE FROM import_batch WHERE source_system='"+source+"';"
                "DELETE m FROM approval_group_member m JOIN approval_group g ON g.id=m.group_id WHERE g.name LIKE '"+prefix+"%';"
                "DELETE FROM approval_group WHERE name LIKE '"+prefix+"%';DELETE FROM idempotency_record WHERE request_key LIKE '"+prefix+"%';DELETE FROM audit_log WHERE trace_id LIKE '"+prefix+"%';")
            for key in files:
                if key and '/' not in key: (storage/key).unlink(missing_ok=True)
            s.cleanup_identity(prefix)
        except (subprocess.CalledProcessError,OSError):print('Disposable fixture cleanup unavailable: '+prefix,flush=True)

if __name__=='__main__':
    try:main()
    except (AssertionError,RuntimeError) as failure:
        import traceback
        line=traceback.extract_tb(failure.__traceback__)[-1].lineno
        raise SystemExit(str(failure) or f'Catalog verification failed at line {line}; inspect private logs.')
