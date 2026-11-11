export type Node = { id:string; parentId:string|null; code:string; state:string; sortOrder:number; version:number };
export type Lesson = { id:string; code:string; state:string; sortOrder:number; version:number };
export type Created = {id:string; state:string; version:number};
export type Kind = 'WORLD'|'AREA'|'SUBJECT'|'LESSON';
export const apiBase = import.meta.env.VITE_API_BASE || '/api/v1/admin/content';
export class HttpError extends Error {constructor(public status:number, public payload:string){super(`HTTP ${status}: ${payload.slice(0,250)}`);}}
export type AccessTokenProvider = () => Promise<string>;
export function createAdminApi(token:AccessTokenProvider, base=apiBase){
  async function request<T>(path:string, method='GET', payload?:unknown):Promise<T> {
    const bearer=await token();
    if(!bearer) throw new Error('Нет действующей сессии OIDC');
    const response=await fetch(base+path, {method,headers:{Authorization:`Bearer ${bearer}`, ...(payload===undefined?{}:{'Content-Type':'application/json'})},body:payload===undefined?undefined:JSON.stringify(payload),cache:'no-store'});
    if(!response.ok) throw new HttpError(response.status,await response.text());
    return response.status===204?undefined as T:await response.json() as T;
  }
  return {
    worlds:()=>request<Node[]>('/worlds'),areas:(w:string)=>request<Node[]>(`/worlds/${w}/areas`),subjects:(a:string)=>request<Node[]>(`/areas/${a}/subjects`),lessons:(s:string)=>request<Lesson[]>(`/subjects/${s}/lessons`),
    createWorld:(code:string,sortOrder=10)=>request<Created>('/worlds','POST',{code,sortOrder}),
    createArea:(w:string,code:string,sortOrder=10)=>request<Created>(`/worlds/${w}/areas`,'POST',{code,sortOrder}),
    createSubject:(a:string,code:string,sortOrder=10)=>request<Created>(`/areas/${a}/subjects`,'POST',{code,sortOrder}),
    createLesson:(s:string,code:string,sortOrder=10)=>request<Created>(`/subjects/${s}/lessons`,'POST',{code,sortOrder}),
    translate:(kind:Kind,id:string,locale:string,title:string)=>request<void>(`/${kind}/${id}/translations/${locale}`,'PUT',{locale,title}),
    transition:(kind:Kind,id:string,state:string,expectedVersion:number)=>request<Created>(`/${kind}/${id}/transitions`,'POST',{state,expectedVersion}),
    order:(kind:Kind,id:string,sortOrder:number,expectedVersion:number)=>request<Created>(`/${kind}/${id}/order`,'PATCH',{sortOrder,expectedVersion}),
    place:(lessonId:string,subjectId:string,sortOrder=10)=>request<Created>(`/lessons/${lessonId}/placements/${subjectId}`,'POST',{sortOrder,expectedVersion:0}),
  };
}
