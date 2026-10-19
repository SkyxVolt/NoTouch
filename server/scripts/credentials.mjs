import { randomBytes, createHash } from 'node:crypto';
import { writeFileSync } from 'node:fs';
const token = randomBytes(32).toString('base64url');
writeFileSync('.device-token.txt', token + '\n', { flag: 'wx', mode: 0o600 });
writeFileSync('.token-hash.txt', createHash('sha256').update(token).digest('hex') + '\n', { flag: 'wx', mode: 0o600 });
console.log('Created .device-token.txt (app credential) and .token-hash.txt (server NOTOUCH_TOKEN_SHA256). Keep private; never commit.');
