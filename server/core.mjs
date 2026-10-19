import { createHash, timingSafeEqual } from 'node:crypto';
import { readFileSync } from 'node:fs';
const prompts = JSON.parse(readFileSync(new URL('./prompts.json', import.meta.url), 'utf8'));
export class HttpError extends Error { constructor(status, message) { super(message); this.status = status; } }
const check = (ok) => { if (!ok) throw new HttpError(400, 'invalid_request'); };
const finite = (x, lo, hi) => typeof x === 'number' && Number.isFinite(x) && x >= lo && x <= hi;
const point = p => Array.isArray(p) && p.length === 2 && p.every(x => finite(x, -.25, 1.25));
const keys = (o, expected) => { check(o && typeof o === 'object' && !Array.isArray(o)); check(Object.keys(o).length === expected.length && expected.every(k => Object.hasOwn(o, k))); };
const str = (s, max) => typeof s === 'string' && s.length <= max;
const num = nullable => ({ type: nullable ? ['number', 'null'] : 'number' });
const string = nullable => ({ type: nullable ? ['string', 'null'] : 'string' });
export function buildRequest(body) {
  check(body && typeof body === 'object');
  let data, content, properties, name;
  if (body.stage === 'gaze') {
    keys(body, ['stage', 'jpeg', 'display_pixels', 'features', 'local_gaze']);
    check(str(body.jpeg, 2_000_000) && body.jpeg.length > 0 && body.jpeg.length % 4 === 0 && /^[A-Za-z0-9+/]+={0,2}$/.test(body.jpeg));
    const image = Buffer.from(body.jpeg, 'base64');
    check(image.length >= 4 && image[0] === 255 && image[1] === 216 && image.at(-2) === 255 && image.at(-1) === 217);
    check(Array.isArray(body.display_pixels) && body.display_pixels.length === 2 && body.display_pixels.every(x => Number.isInteger(x) && x > 0 && x <= 10000));
    keys(body.features, ['left_x','left_y','right_x','right_y','face_x','face_y','scale','yaw_proxy','pitch_proxy','roll']);
    check(Object.values(body.features).every(x => finite(x, -10, 10)) && body.features.scale > 0);
    check(body.local_gaze === null || point(body.local_gaze));
    data = { display_pixels: body.display_pixels, features: body.features, local_gaze: body.local_gaze };
    content = [{ type: 'input_text', text: JSON.stringify(data) }, { type: 'input_image', image_url: `data:image/jpeg;base64,${body.jpeg}`, detail: 'high' }];
    properties = { x: num(true), y: num(true), confidence: num(false), head_yaw_deg: num(true), head_pitch_deg: num(true), iris_yaw_deg: num(true), iris_pitch_deg: num(true), reason: string(false) };
    name = 'gaze_pose';
  } else {
    check(body.stage === 'context');
    keys(body, ['stage','package','gaze','candidates']);
    check(str(body.package, 256) && point(body.gaze) && Array.isArray(body.candidates) && body.candidates.length > 0 && body.candidates.length <= 40);
    const ids = new Set();
    for (const c of body.candidates) {
      keys(c, ['id','label','editable','bounds']);
      check(str(c.id, 80) && c.id.length > 0 && !ids.has(c.id) && str(c.label, 240) && typeof c.editable === 'boolean');
      ids.add(c.id);
      check(Array.isArray(c.bounds) && c.bounds.length === 4 && c.bounds.every(x => finite(x, 0, 1)) && c.bounds[0] <= c.bounds[2] && c.bounds[1] <= c.bounds[3]);
    }
    data = { package: body.package, gaze: body.gaze, candidates: body.candidates };
    content = [{ type: 'input_text', text: JSON.stringify(data) }];
    properties = { target_id: string(true), confidence: num(false), reason: string(false) };
    name = 'target_choice';
  }
  return { model: 'gpt-5.4', store: false, max_output_tokens: 800, reasoning: { effort: 'none' }, instructions: prompts[body.stage], input: [{ role: 'user', content }],
    text: { format: { type: 'json_schema', name, strict: true, schema: { type: 'object', properties, required: Object.keys(properties), additionalProperties: false } } } };
}
export function authenticate(header, env) {
  if (!/^[a-f0-9]{64}$/.test(env.NOTOUCH_TOKEN_SHA256 || '')) throw new HttpError(503, 'server_not_configured');
  if (!/^Bearer [A-Za-z0-9_-]{43}$/.test(header || '')) throw new HttpError(401, 'unauthorized');
  const actual = createHash('sha256').update(header.slice(7)).digest();
  if (!timingSafeEqual(actual, Buffer.from(env.NOTOUCH_TOKEN_SHA256, 'hex'))) throw new HttpError(401, 'unauthorized');
}
// Atomic shared limits: not per function instance, device, or application process.
export const quotaScript = `
local minute = tonumber(redis.call('GET', KEYS[1]) or '0')
local day = tonumber(redis.call('GET', KEYS[2]) or '0')
if minute >= 12 or day >= 200 then return 0 end
redis.call('INCR', KEYS[1]); redis.call('EXPIRE', KEYS[1], 120)
redis.call('INCR', KEYS[2]); redis.call('EXPIRE', KEYS[2], 172800)
return 1`;
export async function reserve(env, fetcher = fetch, now = Date.now()) {
  env = { ...env, UPSTASH_REDIS_REST_URL: env.UPSTASH_REDIS_REST_URL || env.KV_REST_API_URL, UPSTASH_REDIS_REST_TOKEN: env.UPSTASH_REDIS_REST_TOKEN || env.KV_REST_API_TOKEN };
  if (!/^https:\/\/[^/]+\/?$/.test(env.UPSTASH_REDIS_REST_URL || '') || !env.UPSTASH_REDIS_REST_TOKEN) throw new HttpError(503, 'server_not_configured');
  try {
    const r = await fetcher(env.UPSTASH_REDIS_REST_URL, { method: 'POST', redirect: 'error', signal: AbortSignal.timeout(4000), headers: { Authorization: `Bearer ${env.UPSTASH_REDIS_REST_TOKEN}`, 'Content-Type': 'application/json' },
      body: JSON.stringify(['EVAL', quotaScript, '2', `notouch:minute:${Math.floor(now / 60000)}`, `notouch:day:${Math.floor(now / 86400000)}`]) });
    if (!r.ok) throw new Error();
    const data = await r.json();
    if (data.error || ![0, 1].includes(data.result)) throw new Error();
    if (data.result === 0) throw new HttpError(429, 'call_limit_reached');
  } catch (e) { if (e instanceof HttpError) throw e; throw new HttpError(503, 'quota_store_unavailable'); }
}
export async function analyze(body, authorization, env = process.env, fetcher = fetch) {
  authenticate(authorization, env);
  const request = buildRequest(body);
  if (!env.OPENAI_API_KEY) throw new HttpError(503, 'server_not_configured');
  await reserve(env, fetcher);
  try {
    const r = await fetcher('https://api.openai.com/v1/responses', { method: 'POST', redirect: 'error', signal: AbortSignal.timeout(18000), headers: { Authorization: `Bearer ${env.OPENAI_API_KEY}`, 'Content-Type': 'application/json' }, body: JSON.stringify(request) });
    if (!r.ok) throw new Error();
    const value = await r.json();
    // Return only fields consumed by the app, never upstream error bodies/headers.
    if (value.status !== 'completed' || !Array.isArray(value.output)) throw new Error();
    return { status: value.status, output: value.output, usage: { total_tokens: value.usage?.total_tokens || 0 } };
  } catch { throw new HttpError(502, 'model_unavailable'); }
}
