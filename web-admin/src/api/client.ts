import { useSession } from '../stores/session'
export class ApiError extends Error { constructor(message:string,public status:number,public code:string,public traceId:string){super(message)} }
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
// Retain the key while a request outcome is unknown; repeated submissions can safely replay.
export function mutationKeys() { const keys=new Map<string,string>(); return { key:(scope:string,payload:unknown)=>{ const fingerprint=scope+JSON.stringify(payload); if(!keys.has(fingerprint)) keys.set(fingerprint,crypto.randomUUID()); return keys.get(fingerprint)! }, done:(scope:string,payload:unknown)=>keys.delete(scope+JSON.stringify(payload)) } }
export function explain(e:unknown) { return e instanceof ApiError ? `${e.message}${e.traceId ? '（请求编号 '+e.traceId+'）' : ''}` : e instanceof Error ? e.message : '请求失败，请稍后重试' }
