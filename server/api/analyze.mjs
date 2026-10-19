import { analyze, HttpError } from '../core.mjs';
export default async function handler(req, res) {
  res.setHeader('Cache-Control', 'no-store');
  res.setHeader('X-Content-Type-Options', 'nosniff');
  if (req.method !== 'POST') { res.setHeader('Allow', 'POST'); return res.status(405).json({ error: 'method_not_allowed' }); }
  try {
    if (!(req.headers['content-type'] || '').toLowerCase().startsWith('application/json')) throw new HttpError(415, 'json_required');
    let body = req.body;
    if (Buffer.byteLength(typeof body === 'string' ? body : JSON.stringify(body) || '') > 2_100_000) throw new HttpError(413, 'payload_too_large');
    if (typeof body === 'string') { try { body = JSON.parse(body); } catch { throw new HttpError(400, 'invalid_json'); } }
    return res.status(200).json(await analyze(body, req.headers.authorization));
  } catch (e) {
    return res.status(e instanceof HttpError ? e.status : 500).json({ error: e instanceof HttpError ? e.message : 'internal_error' });
  }
}
