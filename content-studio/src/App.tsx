import {useCallback,useEffect,useMemo,useState} from 'react';
import {configured,getAccessToken,userManager} from './auth';
import {createAdminApi,type Kind,type Lesson,type Node} from './api';

const locales=['kk-KZ','ru-RU','en-US'] as const;
type Selection={kind:Kind;id:string;code:string;state:string;version:number;sortOrder:number};
export function App(){
 const api=useMemo(()=>createAdminApi(getAccessToken),[]);
 const [signedIn,setSignedIn]=useState(false);
 const [worlds,setWorlds]=useState<Node[]>([]);
 const [areas,setAreas]=useState<Node[]>([]);
 const [subjects,setSubjects]=useState<Node[]>([]);
 const [lessons,setLessons]=useState<Lesson[]>([]);
 const [world,setWorld]=useState<Node|null>(null);
 const [area,setArea]=useState<Node|null>(null);
 const [selected,setSelected]=useState<Selection|null>(null);
 const [code,setCode]=useState('');
 const [locale,setLocale]=useState<(typeof locales)[number]>('ru-RU');
 const [title,setTitle]=useState('');
 const [order,setOrder]=useState('10');
 const [newKind,setNewKind]=useState<'WORLD'|'AREA'|'SUBJECT'|'LESSON'>('WORLD');
 const [loading,setLoading]=useState(false);
 const [error,setError]=useState('');
 const [notice,setNotice]=useState('');
 const fail=(cause:unknown)=>{setError(cause instanceof Error?cause.message:String(cause));setNotice('')};
 const refresh=useCallback(async()=>{
   if(!signedIn)return;
   try {
    const next=await api.worlds();setWorlds(next);
    if(world){const a=await api.areas(world.id);setAreas(a);}
    if(area){const s=await api.subjects(area.id);setSubjects(s);}
    if(selected?.kind==='SUBJECT'){setLessons(await api.lessons(selected.id));}
   }catch(e){fail(e)}
 },[api,signedIn,world,area,selected]);
 useEffect(()=>{if(!configured)return; (async()=>{
  try{
   if(location.pathname==='/auth/callback'){await userManager!.signinRedirectCallback();history.replaceState(null,'','/');}
   const u=await userManager!.getUser();setSignedIn(Boolean(u&&!u.expired));
  }catch(e){fail(e)}
 })()},[]);
 useEffect(()=>{void refresh()},[refresh]);
 const pickWorld=async(w:Node)=>{try{setError('');setWorld(w);setArea(null);setSubjects([]);setLessons([]);setSelected({...w,kind:'WORLD'});setNewKind('AREA');setAreas(await api.areas(w.id))}catch(e){fail(e)}};
 const pickArea=async(a:Node)=>{try{setError('');setArea(a);setLessons([]);setSelected({...a,kind:'AREA'});setNewKind('SUBJECT');setSubjects(await api.subjects(a.id))}catch(e){fail(e)}};
 const pickSubject=async(s:Node)=>{try{setError('');setSelected({...s,kind:'SUBJECT'});setNewKind('LESSON');setLessons(await api.lessons(s.id))}catch(e){fail(e)}};
 const execute=async(action:()=>Promise<unknown>,message:string)=>{setLoading(true);setError('');setNotice('');try{await action();setNotice(message);await refresh()}catch(e){fail(e)}finally{setLoading(false)}};
 const create=()=>execute(async()=>{
  if(!code.trim())throw new Error('Введите код');
  if(newKind==='WORLD')await api.createWorld(code.trim(),Number(order));
  else if(newKind==='AREA'&&world)await api.createArea(world.id,code.trim(),Number(order));
  else if(newKind==='SUBJECT'&&area)await api.createSubject(area.id,code.trim(),Number(order));
  else if(newKind==='LESSON'&&selected?.kind==='SUBJECT')await api.createLesson(selected.id,code.trim(),Number(order));
  else throw new Error('Сначала выберите родительский раздел');
  setCode('');
 },'Черновик создан');
 const translate=()=>{if(!selected)return;void execute(()=>api.translate(selected.kind,selected.id,locale,title),'Перевод сохранён')};
 const transition=(state:string)=>{if(!selected)return;void execute(async()=>{
  const response=await api.transition(selected.kind,selected.id,state,selected.version);
  setSelected({...selected,state:response.state,version:response.version});
 },'Статус обновлён')};
 const sort=()=>{if(!selected||selected.kind==='LESSON')return;void execute(async()=>{
  const response=await api.order(selected.kind,selected.id,Number(order),selected.version);
  setSelected({...selected,sortOrder:Number(order),version:response.version});
 },'Порядок изменён')};
 return <><header><strong>VRTrainingAcademy · Content Studio</strong><div>{signedIn?<button onClick={()=>void userManager?.signoutRedirect()}>Выйти</button>:<button onClick={()=>void userManager?.signinRedirect()} disabled={!configured}>Войти через OIDC</button>}</div></header>
 <main>{!configured&&<div className="error">Настройте VITE_OIDC_AUTHORITY и VITE_OIDC_CLIENT_ID. Без OIDC административные функции недоступны.</div>}
 {!signedIn&&<section className="panel">Для управления каталогом требуется действующая OIDC-сессия с ролями, разрешёнными сервером.</section>}
 {signedIn&&<><div className="toolbar"><h2>Учебный каталог</h2><button onClick={()=>void refresh()} disabled={loading}>Обновить</button><span className="muted">World → Area → Subject → Lesson</span></div>
 {error&&<div role="alert" className="error">{error}</div>}{notice&&<div role="status" className="success">{notice}</div>}
 <div className="columns"><section className="panel"><h3>Разделы</h3><div className="tree">
 {worlds.map(w=><div key={w.id}><button className={world?.id===w.id?'active':''} onClick={()=>void pickWorld(w)}>▾ {w.code} <small>{w.state}</small></button>
 {world?.id===w.id&&<div className="inset">{areas.map(a=><div key={a.id}><button className={area?.id===a.id?'active':''} onClick={()=>void pickArea(a)}>▾ {a.code}</button>
 {area?.id===a.id&&<div className="inset">{subjects.map(s=><button className={selected?.id===s.id?'active':''} key={s.id} onClick={()=>void pickSubject(s)}>◦ {s.code}</button>)}</div>}
 </div>)}</div>}</div>)}</div></section>
 <section className="panel"><h3>{selected?selected.code:'Создание каталога'}</h3>
 <p className="muted">{selected?selected.kind+' · '+selected.state+' · v'+selected.version:'Для начала создайте World'}</p>
 <section><h4>Новый объект</h4><div className="form"><label>Тип<select value={newKind} onChange={e=>setNewKind(e.target.value as typeof newKind)}><option>WORLD</option><option disabled={!world}>AREA</option><option disabled={!area}>SUBJECT</option><option disabled={selected?.kind!=='SUBJECT'}>LESSON</option></select></label><label>Код<input value={code} onChange={e=>setCode(e.target.value)} placeholder="PHY_001"/></label><label>Порядок<input type="number" value={order} onChange={e=>setOrder(e.target.value)}/></label><button className="primary" disabled={loading} onClick={create}>Создать DRAFT</button></div></section>
 {selected&&<><section><h4>Локализация</h4><div className="form"><label>Язык<select value={locale} onChange={e=>setLocale(e.target.value as typeof locale)}>{locales.map(l=><option key={l}>{l}</option>)}</select></label><label>Название<input value={title} onChange={e=>setTitle(e.target.value)} maxLength={160}/></label><button disabled={loading||!title.trim()} onClick={translate}>Сохранить перевод</button></div></section>
 <section><h4>Действия и статусы</h4><div className="toolbar">{selected.kind!=='LESSON'&&<button disabled={loading} onClick={sort}>Изменить порядок</button>}{selected.state==='DRAFT'&&<button disabled={loading} onClick={()=>transition(selected.kind==='LESSON'?'READY_FOR_REVIEW':'PUBLISHED')}>{selected.kind==='LESSON'?'Отправить на проверку':'Опубликовать раздел'}</button>}
 {selected.kind!=='LESSON'&&selected.state==='PUBLISHED'&&<button disabled={loading} onClick={()=>transition('ARCHIVED')}>Архивировать раздел</button>}</div><p className="muted">Публикация VR-пакета недоступна без независимой сертификации.</p></section></>}
 {selected?.kind==='SUBJECT'&&<section><h4>Уроки выбранного раздела</h4>{lessons.length===0&&<p className="muted">Пока нет уроков.</p>}{lessons.map(l=><div className="entry" key={l.id}><button onClick={()=>setSelected({...l,kind:'LESSON'})}>{l.code}</button><span className="muted">{l.state}</span></div>)}</section>}
 {selected?.kind==='LESSON'&&<section><h4>VR Releases</h4><p className="muted">Загрузка будет подключена через защищённый UploadSession R5.4. Произвольный выбор lessonId/collectionId запрещён.</p></section>}
 </section></div></>}</main></>;
}
