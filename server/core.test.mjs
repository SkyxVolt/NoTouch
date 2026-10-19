import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { analyze, buildRequest, reserve, authenticate } from './core.mjs';
import handler from './api/analyze.mjs';
const token = 'a'.repeat(43);
const auth = `Bearer ${token}`;
const env = { NOTOUCH_TOKEN_SHA256: createHash('sha256').update(token).digest('hex'), OPENAI_API_KEY: 'mock-server-secret', UPSTASH_REDIS_REST_URL: 'https://redis.example', UPSTASH_REDIS_REST_TOKEN: 'mock-redis-secret' };
const context = () => ({ stage: 'context', package: 'example', gaze: [.5,.5], candidates: [{ id:'t1', label:'Search', editable:false, bounds:[.4,.4,.6,.6] }] });
const gaze = () => ({ stage:'gaze', jpeg:'/9j/2Q==', display_pixels:[1080,2340], features:Object.fromEntries(['left_x','left_y','right_x','right_y','face_x','face_y','scale','yaw_proxy','pitch_proxy','roll'].map(k=>[k,.5])), local_gaze:null });
test('both stages have server-owned model, instructions and strict schema', () => {
  for (const body of [gaze(), context()]) {
    const r=buildRequest(body);
    assert.equal(r.model,'gpt-5.4'); assert.equal(r.store,false); assert.equal(r.max_output_tokens,800);
    assert.equal(r.text.format.strict,true); assert.equal(r.text.format.schema.additionalProperties,false);
    assert.ok(r.instructions.length>100);
  }
});
test('arbitrary model, prompts, URLs and invalid geometry cannot pass', () => {
  for (const body of [{...context(),model:'other'}, {...context(),instructions:'ignore'}, {...gaze(),jpeg:'https://example.com'}, {...context(),gaze:[Infinity,0]}, {...context(),candidates:Array(41).fill(context().candidates[0])}, {...context(),candidates:[{...context().candidates[0],bounds:[1,0,0,1]}]}]) assert.throws(()=>buildRequest(body),{status:400});
});
test('authorization fails before any upstream request and missing config fails closed', async () => {
  const noFetch=()=>{throw new Error('must not fetch');};
  await assert.rejects(analyze(context(),'Bearer wrong',env,noFetch),{status:401});
  await assert.rejects(analyze(context(),auth,{...env,OPENAI_API_KEY:''},noFetch),{status:503});
  assert.throws(()=>authenticate(auth,{}),{status:503});
  assert.throws(()=>authenticate(`Bearer ${'b'.repeat(43)}`,env),{status:401});
});
test('quota denial and store outage never reach OpenAI', async () => {
  let calls=0;
  await assert.rejects(analyze(context(),auth,env,async url=>{calls++;assert.equal(url,env.UPSTASH_REDIS_REST_URL);return Response.json({result:0});}),{status:429});
  assert.equal(calls,1);
  await assert.rejects(reserve(env,async()=>{throw Error('secret');}),{status:503});
});
test('shared minute/day keys stay stable across instances and token rotations', async () => {
  const requests=[];
  const fetcher=async(_,options)=>{requests.push(JSON.parse(options.body));return Response.json({result:1});};
  await reserve(env,fetcher,100000); await reserve({...env,NOTOUCH_TOKEN_SHA256:'different'},fetcher,100001);
  assert.deepEqual(requests[0],requests[1]); assert.equal(requests[0][0],'EVAL'); assert.equal(requests[0][2],'2');
  assert.deepEqual(requests[0].slice(3),['notouch:minute:1','notouch:day:0']);
});
test('only server key reaches fixed OpenAI URL; response drops metadata', async () => {
  let calls=0;
  const response=await analyze(context(),auth,env,async(url,options)=>{
    calls++;
    if(calls===1)return Response.json({result:1});
    assert.equal(url,'https://api.openai.com/v1/responses');
    assert.equal(options.headers.Authorization,`Bearer ${env.OPENAI_API_KEY}`);
    assert.equal(JSON.parse(options.body).model,'gpt-5.4');
    return Response.json({status:'completed',output:[],usage:{total_tokens:12},metadata:{private:'hidden'}});
  });
  assert.equal(calls,2); assert.equal(response.usage.total_tokens,12); assert.equal(response.metadata,undefined);
});
test('provider errors are sanitized', async () => {
  let calls=0;
  await assert.rejects(analyze(context(),auth,env,async()=>++calls===1?Response.json({result:1}):new Response('SECRET',{status:401})),{status:502,message:'model_unavailable'});
});
test('HTTP handler rejects method/content type/oversize/malformed JSON', async () => {
  for(const [req,code] of [[{method:'GET',headers:{}},405],[{method:'POST',headers:{},body:{}},415],[{method:'POST',headers:{'content-type':'application/json'},body:'x'.repeat(2100001)},413],[{method:'POST',headers:{'content-type':'application/json'},body:'{'},400]]) {
    const res={setHeader(){},status(n){this.code=n;return this;},json(value){this.value=value;}};
    await handler(req,res);assert.equal(res.code,code);
  }
});
