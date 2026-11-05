import test from 'node:test';
import assert from 'node:assert/strict';
import { prepareVideo, VideoPreparationError } from '../dist/index.js';
import { readBody } from '../dist/body.js';
import { scopedLoader } from '../dist/loader.js';
const asset={status:'ready',generation:'12345678-1234-1234-1234-123456789abc',playlistUrl:'/videos/immutable/playlist.m3u8',durationSeconds:24,segmentCount:12};
test('prepare validates final commit, abort/408 remain unknown, rejects credential redirects',async()=>{
 let request;
 const result=await prepareVideo({prepareUrl:'https://files.test/prepare',sourceKey:'alice:clip',scope:'alice',headers:ctx=>{assert.equal(ctx.method,'POST');assert.equal(ctx.scope,'alice');return {'X-CSRF':'fixture'};},fetch:async(url,init)=>{request=init;return Response.json(asset,{status:201});}});
 assert.equal(result.playlistUrl,'https://files.test/videos/immutable/playlist.m3u8');assert.equal(request.redirect,'error');assert.deepEqual(JSON.parse(request.body),{sourceKey:'alice:clip'});
 await assert.rejects(prepareVideo({prepareUrl:'https://files.test/prepare',sourceKey:'alice:clip',fetch:async()=>new Response(null,{status:408})}),error=>error instanceof VideoPreparationError&&error.outcome==='unknown');
 await assert.rejects(prepareVideo({prepareUrl:'https://files.test/prepare',sourceKey:'alice:clip',fetch:async()=>Response.json({...asset,playlistUrl:'https://untrusted.test/token'},{status:201})}),error=>error.outcome==='unknown');
});
test('bounded body stops waiting for uncooperative cancellation and rejects oversize',async()=>{
 const controller=new AbortController();let cancelled=false;
 const pending=readBody(new Response(new ReadableStream({pull(){return new Promise(()=>{});},cancel(){cancelled=true;return new Promise(()=>{});}})),16,controller.signal);
 controller.abort(new Error('owner changed'));
 await assert.rejects(Promise.race([pending,new Promise((_,reject)=>setTimeout(()=>reject(Error('hung')),100))]),/owner changed/);assert(cancelled);
 await assert.rejects(readBody(new Response(new Uint8Array(17)),16,new AbortController().signal),/byte limit/);
});
test('disposed loader cannot publish a delayed previous-owner response',async()=>{
 let resolveHeaders; let fetchCalls=0;let completed=0;
 const lifetime=new AbortController();
 const Loader=scopedLoader({headers:()=>new Promise(resolve=>resolveHeaders=resolve),fetch:async()=>{fetchCalls++;return new Response('x');}},new URL('https://files.test/a'),new Set(['https://files.test']),lifetime.signal,16,'alice',()=>{});
 const loader=new Loader(); loader.load({url:'https://files.test/a',responseType:'arraybuffer',type:'media-fragment'},{loadPolicy:{maxLoadTimeMs:1000}}, {onSuccess:()=>completed++,onError:()=>completed++,onTimeout:()=>completed++});
 lifetime.abort();resolveHeaders({'Authorization':'synthetic'});await new Promise(resolve=>setTimeout(resolve,0));assert.equal(fetchCalls,0);assert.equal(completed,0);
});
test('preparation deadline also interrupts hanging headers and custom fetch',async()=>{
 let fetchCalls=0;
 await assert.rejects(prepareVideo({prepareUrl:'https://files.test/prepare',sourceKey:'alice:clip',timeoutMs:10,headers:()=>new Promise(()=>{}),fetch:async()=>{fetchCalls++;return Response.json(asset,{status:201});}}),error=>error.outcome==='unknown');assert.equal(fetchCalls,0);
 await assert.rejects(prepareVideo({prepareUrl:'https://files.test/prepare',sourceKey:'alice:clip',timeoutMs:10,fetch:()=>new Promise(()=>{})}),error=>error.outcome==='unknown');
});
test('hls zero range defaults request a whole segment without a malformed Range header',async()=>{
 const Loader=scopedLoader({fetch:async(_url,init)=>{assert.equal(init.headers.get('range'),null);return new Response(new Uint8Array([1,2,3]));}},new URL('https://files.test/a'),new Set(['https://files.test']),new AbortController().signal,16,'alice',()=>{});
 await new Promise((resolve,reject)=>new Loader().load({url:'https://files.test/init.mp4',responseType:'arraybuffer',type:'media-fragment',rangeStart:0,rangeEnd:0},{loadPolicy:{maxLoadTimeMs:1000}},{onSuccess:response=>{assert.equal(response.data.byteLength,3);resolve();},onError:reject,onTimeout:()=>reject(Error('timeout'))}));
});
