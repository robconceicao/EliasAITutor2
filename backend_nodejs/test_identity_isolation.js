import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import express from 'express';
import { runAsUser, currentUserId } from './services/userContext.js';
import { verifyAccessToken, createHttpAuth, createSocketAuth } from './services/operationalAuth.js';
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'elias-auth-test-'));
process.env.PROGRAM_STATE_DIR = dir;
process.env.APP_ENV = 'homologation';
process.env.TEST_LICENSE_BYPASS = 'true';
const store = await import('./services/programStore.js');
const { installSocketMetering } = await import('./services/socketMetering.js');
const { default: routes } = await import('./routes/programRoutes.js');
const verify = async token => { if (!['A','B'].includes(token)) throw Error('invalid'); return { id:token }; };
test.after(() => fs.rmSync(dir, { recursive:true, force:true }));
test('token validation requires Auth response even in homologation', async () => {
 const env = { AUTH_SUPABASE_URL:'https://auth.invalid', AUTH_SUPABASE_ANON_KEY:'public-test' };
 await assert.rejects(verifyAccessToken(null, { env }), /auth_required/);
 await assert.rejects(verifyAccessToken('forged', { env, fetchImpl:async()=>({ok:false}) }), /auth_invalid/);
 assert.deepEqual(await verifyAccessToken('valid', { env, fetchImpl:async(_u,options)=>{
   assert.equal(options.headers.Authorization,'Bearer valid');
   return {ok:true,json:async()=>({id:'A'})};
 }}), {id:'A'});
});
test('concurrent owners, sessions, legacy quarantine and restart', async () => {
 fs.writeFileSync(path.join(dir,'program_state.json'), '{"state":{"key":"default","daily_goal_minutes":99}}');
 await assert.rejects(store.getProgramState(), /authenticated_user_required/);
 await Promise.all([
  runAsUser('A', ()=>store.updateProgramState({daily_goal_minutes:12})),
  runAsUser('B', ()=>store.updateProgramState({daily_goal_minutes:47}))
 ]);
 assert.equal((await runAsUser('A',store.getProgramState)).daily_goal_minutes,12);
 assert.equal((await runAsUser('B',store.getProgramState)).daily_goal_minutes,47);
 const {id} = await runAsUser('A', ()=>store.createSession({week:1,type:'quick'}));
 assert.equal(await runAsUser('B',()=>store.getSession(id)),null);
 assert.equal(await runAsUser('B',()=>store.endSession(id,{duration_seconds:500})),null);
 assert.equal((await runAsUser('A',()=>store.getSession(id))).duration_seconds,0);
 assert.equal((await runAsUser('B',store.listSessions)).length,0);
 const src = "import {runAsUser} from './services/userContext.js'; import {getProgramState} from './services/programStore.js'; const s=await runAsUser('A',getProgramState); if(s.daily_goal_minutes!==12)process.exit(1);";
 execFileSync(process.execPath,['--input-type=module','-e',src],{cwd:process.cwd(),env:{...process.env,PROGRAM_STATE_DIR:dir}});
 assert.equal(JSON.parse(fs.readFileSync(path.join(dir,'program_state.json'))).state.daily_goal_minutes,99);
});
test('real HTTP routes reject unauthenticated and cross-owner session requests', async () => {
 const app=express(); app.use(express.json());
 app.use(['/program','/sessions','/progress'],createHttpAuth(verify)); app.use(routes);
 const server=app.listen(0,'127.0.0.1'); await new Promise(resolve=>server.once('listening',resolve));
 try {
  const base='http://127.0.0.1:'+server.address().port;
  assert.equal((await fetch(base+'/program/state')).status,401);
  const created=await fetch(base+'/sessions',{method:'POST',headers:{Authorization:'Bearer A','Content-Type':'application/json'},body:JSON.stringify({week:1,type:'quick',userId:'B'})});
  assert.equal(created.status,201); const {id}=await created.json();
  assert.equal((await fetch(base+'/sessions/'+id+'/feedback',{headers:{Authorization:'Bearer B'}})).status,404);
  const state=await fetch(base+'/program/state',{headers:{Authorization:'Bearer B'}});
  assert.equal((await state.json()).daily_goal_minutes,47);
 } finally { await new Promise(resolve=>server.close(resolve)); }
});
test('socket listeners are bound to verified user; revoked token is rejected', async () => {
 const listeners=new Map(), middleware=[];
 let active=true, disconnected=false;
 const socket={handshake:{auth:{accessToken:'A',userId:'B'}},data:{},use:fn=>middleware.push(fn),on:(event,fn)=>listeners.set(event,fn),emit:()=>{},disconnect:()=>{disconnected=true;}};
 await createSocketAuth(async token=>{ if(!active)throw Error('revoked'); return verify(token); })(socket,error=>assert.equal(error,undefined));
 assert.equal(socket.data.userId,'A');
 let seen; socket.on('test',()=>{seen=currentUserId();});
 listeners.get('test')({userId:'B'}); assert.equal(seen,'A');
 active=false; let called=false;
 await middleware[0](['test'],()=>{called=true;});
 assert.equal(called,false); assert.equal(disconnected,true);
});
test('voice gate checks before work, measures server frames and uses one key per minute', async () => {
 const guards=[], calls=[], emitted=[]; let disconnected=false;
 const socket={data:{accessToken:'A'},use:fn=>guards.push(fn),emit:(event)=>{emitted.push(event);return socket;},disconnect:()=>{disconnected=true;}};
 const usage=installSocketMetering(socket,{sessionId:'unit-session',check:async token=>{calls.push(token);},consume:async req=>{calls.push(req);}});
 let next=false; await guards[0](['speech_end',{durationMs:999999}],()=>{next=true;});
 assert.equal(next,true); assert.equal(usage.meter.totalSpeechMs,0);
 for(let n=0;n<6001;n++) socket.emit('audio_opus_frame',{frame:'fixture'});
 await usage.settled();
 assert.deepEqual(calls.filter(x=>typeof x==='object').map(x=>x.idempotencyKey),['voice:unit-session:minute:1','voice:unit-session:minute:2']);
 assert.equal(usage.meter.consumedMinutes,2); assert.equal(disconnected,false);
});
test('denied commercial quota stops event before handler', async () => {
 const guards=[]; let disconnected=false, handled=false;
 const socket={data:{accessToken:'A'},use:fn=>guards.push(fn),emit:()=>socket,disconnect:()=>{disconnected=true;}};
 installSocketMetering(socket,{check:async()=>{throw Error('denied');}});
 await guards[0](['mensagem_usuario'],()=>{handled=true;});
 assert.equal(handled,false); assert.equal(disconnected,true);
});
