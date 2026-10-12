/**
 * 正式业务 API 的统一请求入口。
 * 附带访问令牌、追踪编号及可选幂等键；超时或网络失败交由页面提示，不无限重试。
 */
import { useSession } from '../stores/session'
// 保留 HTTP 状态、业务错误码和追踪号，供页面区分登录失效、权限拒绝与普通失败。
export class ApiError extends Error { constructor(message:string,public status:number,public code:string,public traceId:string){super(message)} }
// JSON 请求自动序列化；FormData 不设置 Content-Type，由浏览器生成 multipart boundary。
// 401 只刷新并重试一次，且复用原请求幂等键，避免重试造成重复写入。
export async function api<T>(path:string,method='GET',body?:unknown,key?:string):Promise<T> {
 const session=useSession(), sent=session.accessToken
 const headers:Record<string,string>={'X-Trace-Id':crypto.randomUUID(),Authorization:'Bearer '+sent}
 if(key) headers['Idempotency-Key']=key
 if(body!==undefined && !(body instanceof FormData)) headers['Content-Type']='application/json'
 const run=()=>fetch('/api/v1'+path,{method,headers,body:body instanceof FormData?body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(15000)})
 let response=await run()
 if(response.status===401) { await session.refresh(sent); headers.Authorization='Bearer '+session.accessToken; response=await run() }
 const data=await response.json()
 if(!response.ok) { if(response.status===401) session.clear(); throw new ApiError(data.message || '请求失败',response.status,data.code || 'ERROR',data.traceId || '') }
 return data.data as T
}
// 按操作范围和请求内容保留幂等键，结果未知时重复点击仍使用原键；只有明确成功才清理。
export function mutationKeys() { const keys=new Map<string,string>(); return { key:(scope:string,payload:unknown)=>{ const fingerprint=scope+JSON.stringify(payload); if(!keys.has(fingerprint)) keys.set(fingerprint,crypto.randomUUID()); return keys.get(fingerprint)! }, done:(scope:string,payload:unknown)=>keys.delete(scope+JSON.stringify(payload)) } }
// 对外提示附带追踪号，方便排查后端日志；不直接展示整个错误对象或请求凭据。
export function explain(e:unknown) { return e instanceof ApiError ? `${e.message}${e.traceId ? '（请求编号 '+e.traceId+'）' : ''}` : e instanceof Error ? e.message : '请求失败，请稍后重试' }
