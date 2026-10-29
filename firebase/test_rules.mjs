// Emulator-only regression tests using Node's built-in fetch; no project credentials.
import assert from 'node:assert/strict';
const project = 'demo-tracewall';
const auth = process.env.FIREBASE_AUTH_EMULATOR_HOST || '127.0.0.1:9099';
const dbHost = process.env.FIREBASE_DATABASE_EMULATOR_HOST || '127.0.0.1:9000';
const fsHost = process.env.FIRESTORE_EMULATOR_HOST || '127.0.0.1:8080';
const base = `http://${fsHost}/v1/projects/${project}/databases/(default)/documents`;
let checks = 0;
async function request(method, url, data, token, allowed = true) {
  const response = await fetch(url, {method, headers: {'Content-Type': 'application/json',
    ...(token ? {Authorization: `Bearer ${token}`} : {})},
    ...(data === undefined ? {} : {body: JSON.stringify(data)})});
  const body = await response.text();
  assert.equal(allowed ? response.ok : [401, 403].includes(response.status), true,
    `${method} ${url.split('?')[0]}: ${response.status} ${body}`);
  checks++;
  return body ? JSON.parse(body) : null;
}
async function user() {
  const result = await request('POST', `http://${auth}/identitytoolkit.googleapis.com/v1/accounts:signUp?key=fake`,
    {returnSecureToken: true});
  return [result.localId, result.idToken];
}
function db(method, path, token, data, allowed = true) {
  return request(method, `http://${dbHost}/${path}.json?ns=${project}-default-rtdb${token === 'owner' ? '' : `&auth=${token}`}`,
    data, token === 'owner' ? token : undefined, allowed);
}
function fields(data) {
  return Object.fromEntries(Object.entries(data).map(([key, value]) => [key,
    typeof value === 'string' ? {stringValue: value} : typeof value === 'boolean' ? {booleanValue: value} :
    Array.isArray(value) ? {arrayValue: {values: value.map(v => ({stringValue: v}))}} : {integerValue: String(value)}]));
}
function doc(method, path, token, data, allowed = true) {
  return request(method, `${base}/${path}`, data === undefined ? undefined : {fields: fields(data)}, token, allowed);
}
const [child, ct] = await user(), [parent, pt] = await user(), [other, ot] = await user();
const root = `devices/${child}`, code = 'ABCDEFGH';
const pairing = {code, expires_ms: Date.now() + 300000};
const claimDb = (uid, token, value = code, allowed = true) =>
  db('PATCH', root, token, {[`parentUid/${uid}`]: value, 'pairing/claimed_by': uid}, allowed);
function claimFs(uid, token, value = code, allowed = true) {
  const prefix = `projects/${project}/databases/(default)/documents/${root}`;
  return request('POST', base + ':commit', {writes: [
    {update: {name: `${prefix}/parents/${uid}`, fields: fields({code: value})}},
    {update: {name: `${prefix}/pairing/current`, fields: fields({claimed_by: uid})},
      updateMask: {fieldPaths: ['claimed_by']}}
  ]}, token, allowed);
}
// UID knowledge alone grants nothing; no broad child or parent grants.
await db('PUT', `${root}/parentUid/${parent}`, pt, code, false);
await doc('PATCH', `${root}/parents/${parent}`, pt, {code}, false);
for (const token of [ct, ot]) {
  await db('PUT', `${root}/rules/allow`, token, ['a.test'], false);
  await doc('PATCH', `${root}/rules/current`, token, {allow: ['a.test']}, false);
}
await db('GET', `${root}/rules`, ot, undefined, false);
await db('PUT', `${root}/pairing`, ct, pairing);
await doc('PATCH', `${root}/pairing/current`, ct, pairing);
await db('GET', `${root}/pairing`, ot, undefined, false);
await doc('GET', `${root}/pairing/current`, ot, undefined, false);
await db('PUT', `${root}/parentUid/${parent}`, pt, code, false);
await doc('PATCH', `${root}/parents/${parent}`, pt, {code}, false);
await claimDb(parent, pt, 'BCDEFGHJ', false);
await claimFs(parent, pt, 'BCDEFGHJ', false);
await claimDb(parent, pt);
// A partial claim is readable by its owner and must not be blindly rewritten on retry.
assert.equal(await db('GET', `${root}/parentUid/${parent}`, pt), code);
await claimDb(parent, pt, code, false);
await claimFs(parent, pt);
const existingMembership = await doc('GET', `${root}/parents/${parent}`, pt);
assert.equal(existingMembership.fields.code.stringValue, code);
await claimDb(other, ot, code, false);
await claimFs(other, ot, code, false);
await db('PUT', `${root}/parentUid/${parent}`, pt, 'BCDEFGHJ', false);
await doc('PATCH', `${root}/parents/${parent}`, pt, {code: 'BCDEFGHJ'}, false);
await db('PATCH', `${root}/rules`, pt, {allow: ['a.test', 'b.test'], blocked_uids: [12345]});
await doc('PATCH', `${root}/rules/current`, pt, {allow: ['a.test']});
for (const token of [ct, pt]) {
  await db('GET', `${root}/rules`, token);
  await doc('GET', `${root}/rules/current`, token);
}
// Removing the final list entries removes the rules node; the child must apply null as empty.
await db('PATCH', `${root}/rules`, pt, {allow: null, blocked_uids: null});
assert.equal(await db('GET', `${root}/rules`, ct), null);
await db('PATCH', `${root}/rules`, pt, {allow: ['a.test', 'b.test'], blocked_uids: [12345]});
// Optimistic conflicts reject stale writes; retrying against the latest state preserves both intents.
const allowUrl = `http://${dbHost}/${root}/rules/allow.json?ns=${project}-default-rtdb&auth=${pt}`;
async function readAllowVersion() {
  const response = await fetch(allowUrl, {headers: {'X-Firebase-ETag': 'true'}});
  assert.equal(response.ok, true); checks++;
  return {etag: response.headers.get('etag'), value: await response.json()};
}
async function writeAllowVersion(etag, value, status) {
  const response = await fetch(allowUrl, {method: 'PUT',
    headers: {'Content-Type': 'application/json', 'If-Match': etag}, body: JSON.stringify(value)});
  assert.equal(response.status, status); checks++;
}
const sharedVersion = await readAllowVersion();
await writeAllowVersion(sharedVersion.etag, [...sharedVersion.value, 'c.test'], 200);
await writeAllowVersion(sharedVersion.etag, [...sharedVersion.value, 'd.test'], 412);
const rebasedVersion = await readAllowVersion();
await writeAllowVersion(rebasedVersion.etag, [...rebasedVersion.value, 'd.test'], 200);
assert.deepEqual(await db('GET', `${root}/rules/allow`, ct), ['a.test', 'b.test', 'c.test', 'd.test']);
assert.deepEqual(await db('GET', `${root}/rules/blocked_uids`, ct), [12345]);
await db('PUT', `${root}/history/h`, ct, {host: 'a.test'});
await db('PUT', `${root}/events/e`, ct, {type: 'protection_off'});
for (const path of ['history', 'events']) {
  await db('GET', `${root}/${path}`, pt);
  await db('GET', `${root}/${path}`, ot, undefined, false);
  await db('DELETE', `${root}/${path}`, pt, undefined, false);
}
await db('DELETE', root, pt, undefined, false);
await doc('PATCH', `${root}/history/h`, ct, {host: 'a.test', time_ms: 1});
await doc('PATCH', `${root}/events/e`, ct, {type: 'protection_off', reason: 'vpn_revoked', time_ms: 1});
await doc('PATCH', `${root}/events/install`, ct,
  {type: 'new_app', label: 'Example', package: 'example.app', time_ms: 2});
await doc('PATCH', `${root}/events/bad-install`, ct,
  {type: 'new_app', label: 'x'.repeat(201), package: 'example.app', time_ms: 2}, false);
await doc('PATCH', `${root}/events/unknown`, ct, {type: 'unknown', time_ms: 2}, false);
for (const path of ['history/h', 'events/e', 'events/install']) {
  await doc('GET', `${root}/${path}`, pt);
  await doc('GET', `${root}/${path}`, ot, undefined, false);
  await doc('DELETE', `${root}/${path}`, pt, undefined, false);
  await doc('PATCH', `${root}/${path}`, pt, {host: 'evil.test'}, false);
}
await db('DELETE', `${root}/pairing`, ct);
await doc('DELETE', `${root}/pairing/current`, ct);
await claimDb(other, ot, code, false);
await claimFs(other, ot, code, false);
for (const token of [ot, ct]) {
  await db('DELETE', `${root}/parentUid/${parent}`, token, undefined, token === ct);
  await doc('DELETE', `${root}/parents/${parent}`, token, undefined, token === ct);
}
await db('GET', `${root}/rules`, pt, undefined, false);
await doc('GET', `${root}/history/h`, pt, undefined, false);
// Admin seeds only expired fixtures; claims use ordinary authenticated tokens.
await db('PUT', `${root}/pairing`, 'owner', {code, expires_ms: 1});
await doc('PATCH', `${root}/pairing/current`, 'owner', {code, expires_ms: 1});
await claimDb(other, ot, code, false);
await claimFs(other, ot, code, false);
console.log(`PASS: ${checks} emulator REST checks (including auth and fixtures)`);
