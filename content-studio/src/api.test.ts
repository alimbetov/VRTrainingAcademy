import {afterEach,describe,expect,it,vi} from 'vitest';
import {createAdminApi,HttpError} from './api';
afterEach(()=>vi.unstubAllGlobals());
describe('R5.3 Content Studio API contracts',()=>{
 it('uses trusted bearer and selected subject URL, never client roles',async()=>{
   const request=vi.fn(async()=>new Response(JSON.stringify({id:'lesson-uuid',state:'DRAFT',version:0}),{status:201,headers:{'Content-Type':'application/json'}}));
   vi.stubGlobal('fetch',request);
   const api=createAdminApi(async()=> 'token-from-oidc','/api/v1/admin/content');
   const result=await api.createLesson('subject-uuid','PHY_001',10);
   expect(result.state).toBe('DRAFT');
   expect(request).toHaveBeenCalledOnce();
   const [url,init]=request.mock.calls[0];
   expect(url).toBe('/api/v1/admin/content/subjects/subject-uuid/lessons');
   expect(init!.headers.Authorization).toBe('Bearer token-from-oidc');
   expect(JSON.parse(init!.body)).toEqual({code:'PHY_001',sortOrder:10});
 });
 it('fails closed when OIDC is absent',async()=>{
   const request=vi.fn();vi.stubGlobal('fetch',request);
   await expect(createAdminApi(async()=>{throw Error('expired')}).worlds()).rejects.toThrow('expired');
   expect(request).not.toHaveBeenCalled();
 });
 it('preserves forbidden status and does not impersonate admin',async()=>{
   vi.stubGlobal('fetch',vi.fn(async()=>new Response('Forbidden',{status:403})));
   await expect(createAdminApi(async()=> 'valid').createWorld('NEW')).rejects.toMatchObject({status:403});
 });
 it('never calls a release publication endpoint',()=>{
   const api=createAdminApi(async()=> 'valid');
   expect('publishRelease' in api).toBe(false);
 });
});
