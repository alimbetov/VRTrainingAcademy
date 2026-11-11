import {useState} from 'react';
import {apiBase,HttpError} from './api';
import {getAccessToken} from './auth';

type Props={lessonId:string;subjectId:string};
type Session={uploadId:string;lessonId:string;subjectId:string;state:string};
type Staged={uploadId:string;lessonId:string;state:string;sizeBytes:number;sha256Hex:string};
const UI_MAX_BYTES=64*1024*1024; // Browser hashing MVP; larger files need streamed S3 multipart.
async function check(response:Response){
 if(!response.ok)throw new HttpError(response.status,await response.text());
 return response.json();
}
export function LessonUploadPanel({lessonId,subjectId}:Props){
 const[file,setFile]=useState<File|null>(null);
 const[entryLevel,setEntryLevel]=useState('/Game/Lessons/Physics/L_Electricity');
 const[working,setWorking]=useState(false);
 const[receipt,setReceipt]=useState<Staged|null>(null);
 const[error,setError]=useState('');
 async function upload(){
   if(!file || file.size===0 || file.size>UI_MAX_BYTES)throw Error('MVP: файл должен быть от 1 байта до 64 МиБ; для больших пакетов требуется multipart API');
   if(!/^[A-Za-z0-9][A-Za-z0-9_.-]{0,119}\.pak$/.test(file.name)||file.name.includes('..'))
     throw Error('Поддерживается только безопасное имя файла .pak');
   const token=await getAccessToken();
   // The lessonId & subjectId are from the chosen server-authenticated catalog.
   // A client must NOT provide collectionId, organizationId or releaseId.
   const createResponse=await fetch(`${apiBase}/lessons/${lessonId}/uploads`,{
     method:'POST',headers:{Authorization:`Bearer ${token}`,'Content-Type':'application/json',
                            'Idempotency-Key':crypto.randomUUID()},
     body:JSON.stringify({subjectId})});
   const session:Session=await check(createResponse);
   if(session.lessonId!==lessonId || session.subjectId!==subjectId || session.state!=='CREATED')
     throw Error('Неверная или уже используемая сессия загрузки');
   const digest=await crypto.subtle.digest('SHA-256',await file.arrayBuffer());
   const sha256Hex=Array.from(new Uint8Array(digest),b=>b.toString(16).padStart(2,'0')).join('');
   const metadata={artifactName:file.name,sizeBytes:file.size,sha256Hex,
     runtimeBuildId:'0.1.0-foundation',entryLevel};
   const body=new FormData();
   body.append('metadata',new Blob([JSON.stringify(metadata)],{type:'application/json'}),'metadata.json');
   body.append('artifact',file,file.name);
   const uploadResponse=await fetch(`${apiBase}/uploads/${session.uploadId}/artifact`,{
     method:'POST',headers:{Authorization:`Bearer ${token}`},body});
   const staged:Staged=await check(uploadResponse);
   if(staged.uploadId!==session.uploadId || staged.lessonId!==lessonId || staged.state!=='STAGED')
     throw Error('Сервер не подтвердил STAGED');
   setReceipt(staged);
 }
 return <section className="panel" aria-label="Загрузка Quest урока">
   <h4>Загрузка Quest .pak</h4><p className="muted">Урок: {lessonId}. Раздел: {subjectId}. Только в RustFS Quarantine; публикация недоступна.</p>
   <div className="form">
    <label>Файл .pak<input type="file" accept=".pak" onChange={e=>setFile(e.target.files?.[0]||null)}/></label>
    <label>Стартовый уровень<input value={entryLevel} onChange={e=>setEntryLevel(e.target.value)} size={38}/></label>
    <button className="primary" disabled={!file||working} onClick={async()=>{
      setWorking(true);setError('');setReceipt(null);
      try{await upload()}catch(e){setError(e instanceof Error?e.message:String(e))}
      finally{setWorking(false)}
    }}>{working?'Проверка и загрузка…':'Загрузить в карантин'}</button>
   </div>{error&&<div role="alert" className="error">{error}</div>}
   {receipt&&<div role="status" className="success">STAGED · {receipt.sizeBytes} байт · SHA-256: {receipt.sha256Hex}<p>Это не означает CERTIFIED или доступность на Quest.</p></div>}
 </section>;
}
