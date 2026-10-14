#!/usr/bin/env python3
"""Prepare private Spring properties for the shared IDEA dev configurations."""
import importlib.util
import os
from pathlib import Path
import xml.etree.ElementTree as ET

REPO=Path(__file__).resolve().parents[2]
PRIVATE=REPO/'deployment/secrets/dev/idea'
spec=importlib.util.spec_from_file_location('runtime',Path(__file__).with_name('run-service.py'))
runtime=importlib.util.module_from_spec(spec);spec.loader.exec_module(runtime)
SERVICES=[('identity-service','identity-service','org.shen.identity.IdentityApplication','身份服务（dev）',8083),
          ('booking-service','GraduationProject','org.shen.graduationproject.GraduationProjectApplication','预约服务（dev）',8082),
          ('gateway','gateway','org.shen.gateway.GatewayApplication','网关（dev）',8081),
          ('notification-service','notification-service','org.shen.notification.NotificationApplication','通知服务（dev）',8085)]
COMMON=['JWT_PUBLIC_KEY_PATH','JWT_ISSUER','JWT_AUDIENCE','JWT_KEY_ID','IMPORT_STORAGE_DIR','NACOS_SERVER_ADDR','NACOS_USERNAME','NACOS_PASSWORD']
IDENTITY=['SERVICE_CALL_KEY_PATH','LOGIN_PEER_KEY_PATH','JWT_PRIVATE_KEY_PATH','AUTH_PASSWORD_FINGERPRINT_KEY_PATH','IDENTITY_DB_URL','IDENTITY_DB_USERNAME','IDENTITY_DB_PASSWORD','REDIS_HOST','REDIS_PORT','REDIS_PASSWORD','AUTH_ADMIN_USERNAME','AUTH_ADMIN_PASSWORD']
GATEWAY=['LOGIN_PEER_KEY_PATH','REDIS_HOST','REDIS_PORT','REDIS_PASSWORD']
NOTIFICATION=['NOTIFICATION_DB_URL','NOTIFICATION_DB_USERNAME','NOTIFICATION_DB_PASSWORD','RABBITMQ_HOST','RABBITMQ_PORT','RABBITMQ_USERNAME','RABBITMQ_PASSWORD','RABBITMQ_VHOST']
BOOKING=['SERVICE_CALL_KEY_PATH','DB_URL','DB_USERNAME','DB_PASSWORD','REDIS_HOST','REDIS_PORT','REDIS_PASSWORD','RABBITMQ_HOST','RABBITMQ_PORT','RABBITMQ_USERNAME','RABBITMQ_PASSWORD','RABBITMQ_VHOST']

# 按 Java properties 语法转义值，保留空格、分隔符及中文路径。
# 非 ASCII 字符按 UTF-16 编码，兼容 properties 文件的 Unicode 转义读取。
def escape(value):
    out=[]
    for c in value:
        if c=='\\':out.append('\\\\')
        elif c=='\n':out.append('\\n')
        elif c=='\r':out.append('\\r')
        elif c=='\t':out.append('\\t')
        elif c in '=:# !':out.append('\\'+c)
        elif ord(c)>126:
            encoded=c.encode('utf-16-be')
            out.extend('\\u'+encoded[i:i+2].hex() for i in range(0,len(encoded),2))
        else:out.append(c)
    return ''.join(out)

# IDEA 项目根可能是仓库，也可能是内层 GraduationProject，不能据此推算凭据路径。
# 四个 Maven 服务的内容根均直接位于仓库下，固定从模块目录回到 deployment。
# MODULE_WORKING_DIR 只用于 IDEA 支持的工作目录字段；JVM 参数使用普通相对文件路径。
# 运行配置只引用私密 properties 的路径，不把密码嵌入共享 XML。
def config(service,module,main,name):
    root=ET.Element('component',name='ProjectRunConfigurationManager')
    conf=ET.SubElement(root,'configuration',default='false',name=name,type='SpringBootApplicationConfigurationType',factoryName='Spring Boot')
    ET.SubElement(conf,'module',name=module)
    ET.SubElement(conf,'option',name='SPRING_BOOT_MAIN_CLASS',value=main)
    vm='-Dspring.config.additional-location="file:../deployment/secrets/dev/idea/'+service+'.properties"'
    ET.SubElement(conf,'option',name='VM_PARAMETERS',value=vm)
    # IDEA 保存时会为工作目录补 file://；直接使用其格式，避免保存前后出现不同表示。
    ET.SubElement(conf,'option',name='WORKING_DIRECTORY',value='file://$MODULE_WORKING_DIR$')
    method=ET.SubElement(conf,'method',v='2');ET.SubElement(method,'option',name='Make',enabled='true')
    return root

# 组合启动身份、预约、通知和网关四个后端服务，数据库等基础设施由 Docker 单独管理。
def compound():
    root=ET.Element('component',name='ProjectRunConfigurationManager')
    conf=ET.SubElement(root,'configuration',default='false',name='后端总启动（dev）',type='CompoundRunConfigurationType')
    for _,_,_,name,_ in SERVICES:ET.SubElement(conf,'toRun',name=name,type='SpringBootApplicationConfigurationType')
    ET.SubElement(conf,'method',v='2')
    return root

def write_xml(path,root):
    ET.indent(root,space='  ');path.write_text(ET.tostring(root,encoding='unicode')+'\n')

# IDEA 可在保存运行配置时按中文显示名重命名 .run.xml。
# 按配置名称复用现有文件，不能重新生成另一份 Campus_* 同名配置，让内存中的旧配置继续被选中。
def shared_configuration_path(directory,name):
    matches=[]
    for path in directory.glob('*.run.xml'):
        configuration=ET.parse(path).getroot().find('configuration')
        if configuration is not None and configuration.get('name')==name:matches.append(path)
    if len(matches)>1:raise RuntimeError('Duplicate shared IDEA configuration name: '+name)
    return matches[0] if matches else directory/(name+'.run.xml')

# 通过各服务的键白名单生成 dev 配置，限制签名私钥及数据库密码的分发范围。
# 拒绝符号链接目标，文件写入权限保持为 600。
def main():
    PRIVATE.mkdir(mode=0o700,parents=True,exist_ok=True);os.chmod(PRIVATE,0o700)
    for service,module,main_class,name,port in SERVICES:
        env=runtime.service_env('dev',service,service=='identity-service')
        keys=COMMON+(IDENTITY if service=='identity-service' else BOOKING if service=='booking-service' else NOTIFICATION if service=='notification-service' else GATEWAY)
        values={k:env[k] for k in keys if k in env}
        for required in ['JWT_PUBLIC_KEY_PATH','JWT_ISSUER','JWT_AUDIENCE','NACOS_USERNAME','NACOS_PASSWORD']:
            if not values.get(required):raise RuntimeError('Missing private dev configuration: '+required)
        values.update({'spring.profiles.active':env['SPRING_PROFILES_ACTIVE'],'server.port':str(port),'server.address':'127.0.0.1',
            'spring.cloud.nacos.discovery.group':'XMUT_DEV_BUSINESS','spring.cloud.nacos.discovery.ip':'127.0.0.1',
            'spring.cloud.nacos.discovery.graceful-shutdown-wait-time':'0','management.endpoint.health.probes.enabled':'true'})
        path=PRIVATE/(service+'.properties')
        if path.is_symlink():raise RuntimeError('Refusing symlinked IDEA private configuration')
        fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600);os.fchmod(fd,0o600)
        with os.fdopen(fd,'w') as stream:
            stream.write('# PRIVATE: generated from dev env files; never commit.\n')
            for key,value in values.items():stream.write(key+'='+escape(value)+'\n')
    shared=REPO/'.run';shared.mkdir(exist_ok=True)
    # 全部服务共用同一生成逻辑，防止只有新增服务修正而旧身份/预约配置仍保留错误路径。
    for service,module,main_class,name,_ in SERVICES:
        write_xml(shared_configuration_path(shared,name),config(service,module,main_class,name))
    write_xml(shared_configuration_path(shared,'后端总启动（dev）'),compound())
    # The user's existing IDEA project lives in the inner directory. Keep its local configs ignored.
    inner=REPO/'GraduationProject/.idea'
    if (inner/'workspace.xml').exists():
        directory=inner/'runConfigurations';directory.mkdir(exist_ok=True)
        for service,module,main_class,name,_ in SERVICES:
            write_xml(directory/('Campus_'+service.replace('-','_')+'.xml'),config(service,module,main_class,name))
        write_xml(directory/'Campus_Backend_dev.xml',compound())
    print('Prepared dev IDEA configuration: private files mode 600, no credentials printed. Select 后端总启动（dev） in IDEA.')

if __name__=='__main__':main()
