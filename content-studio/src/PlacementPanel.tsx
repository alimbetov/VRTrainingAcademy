import {useEffect,useMemo,useState} from 'react';
import {createAdminApi,type Node} from './api';
import {getAccessToken} from './auth';

export function PlacementPanel({lessonId,onDone}:{lessonId:string;onDone:()=>void}){
 const api=useMemo(()=>createAdminApi(getAccessToken),[]);
 const[worlds,setWorlds]=useState<Node[]>([]);
 const[areas,setAreas]=useState<Node[]>([]);
 const[subjects,setSubjects]=useState<Node[]>([]);
 const[worldId,setWorldId]=useState('');
 const[areaId,setAreaId]=useState('');
 const[subjectId,setSubjectId]=useState('');
 const[error,setError]=useState('');
 const[notice,setNotice]=useState('');
 const[busy,setBusy]=useState(false);
 useEffect(()=>{void api.worlds().then(setWorlds).catch(e=>setError(String(e)))},[api]);
 const chooseWorld=async(id:string)=>{setWorldId(id);setAreaId('');setSubjectId('');setSubjects([]);try{setAreas(id?await api.areas(id):[])}catch(e){setError(String(e))}};
 const chooseArea=async(id:string)=>{setAreaId(id);setSubjectId('');try{setSubjects(id?await api.subjects(id):[])}catch(e){setError(String(e))}};
 return <section><h4>Дополнительное размещение урока</h4>
  <p className="muted">Урок не дублируется — добавляется только ссылка на другой Subject. Для обоих разделов нужны права CONTENT_ADMIN.</p>
  <div className="form">
   <label>World<select value={worldId} onChange={e=>void chooseWorld(e.target.value)}><option value="">Выберите World</option>{worlds.map(w=><option key={w.id} value={w.id}>{w.code}</option>)}</select></label>
   <label>Area<select value={areaId} onChange={e=>void chooseArea(e.target.value)}><option value="">Выберите Area</option>{areas.map(a=><option key={a.id} value={a.id}>{a.code}</option>)}</select></label>
   <label>Subject<select value={subjectId} onChange={e=>setSubjectId(e.target.value)}><option value="">Выберите Subject</option>{subjects.map(s=><option key={s.id} value={s.id}>{s.code}</option>)}</select></label>
   <button disabled={!subjectId||busy} onClick={async()=>{
     setBusy(true);setError('');setNotice('');
     try{await api.place(lessonId,subjectId);setNotice('Размещение добавлено');onDone()}
     catch(e){setError(e instanceof Error?e.message:String(e))}
     finally{setBusy(false)}
   }}>Добавить размещение</button>
  </div>
  {error&&<div role="alert" className="error">{error}</div>}
  {notice&&<div role="status" className="success">{notice}</div>}
 </section>;
}
