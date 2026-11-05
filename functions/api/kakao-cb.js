// functions/api/kakao-cb.js — v336 💬 아침 브리핑을 카톡 '나에게 보내기'로 받기: 카카오 동의 화면에서 돌아오는 곳
// © 2026 coverfo All Rights Reserved
//
// GET /api/kakao-cb?code=…&state=…   (카카오 디벨로퍼스 → 카카오 로그인 → Redirect URI 에 https://coverfo.com/api/kakao-cb 등록)
//   1) state(서버 서명 · 10분)로 누구의 연결인지 확인 — functions/api/hub.js 의 kakao_link 가 만든 값
//   2) code → 카카오 키(액세스 · 리프레시 토큰)로 바꿈 (KAKAO_REST_KEY · 선택 KAKAO_CLIENT_SECRET)
//   3) 메시지 보내기 동의(talk_message)가 있는지 확인 → Supabase cf_kakao_memo 에 저장 (서버만 읽음 · sql/cf_kakao_memo.sql)
//   4) coverfo.com/brief?kakao=ok (또는 오류 코드)로 돌려보낸다. 키는 화면·주소에 남기지 않는다.

export async function onRequestGet(context) {
  const env = context.env || {};
  const u = new URL(context.request.url);
  const back = (k) => Response.redirect('https://coverfo.com/brief?kakao=' + k + '#kakao', 302);
  if (u.searchParams.get('error')) return back('cancel');
  const code = u.searchParams.get('code') || '';
  const uid = await checkState(env, u.searchParams.get('state') || '');
  if (!code || !uid) return back('expired');
  const key = String(env.KAKAO_REST_KEY || '').trim();
  const url = String(env.SUPABASE_URL || '').replace(/\/+$/, ''), svc = env.SUPABASE_SERVICE_ROLE_KEY || '';
  if (!key || !url || !svc) return back('setup');
  try {
    const f = new URLSearchParams({ grant_type: 'authorization_code', client_id: key, redirect_uri: String(env.KAKAO_MEMO_REDIRECT || 'https://coverfo.com/api/kakao-cb'), code });
    if (env.KAKAO_CLIENT_SECRET) f.set('client_secret', String(env.KAKAO_CLIENT_SECRET));
    const r = await fetch('https://kauth.kakao.com/oauth/token', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=utf-8' }, body: f });
    const j = await r.json().catch(() => ({}));
    if (!r.ok || !j.access_token || !j.refresh_token) return back('token');
    /* 동의 화면에서 '카카오톡 메시지 전송'을 빼고 동의했으면 보낼 수 없다 */
    if (j.scope && !String(j.scope).split(/[\s,]+/).includes('talk_message')) return back('noscope');
    const now = Date.now();
    const row = {
      user_id: uid, refresh_token: j.refresh_token, access_token: j.access_token,
      access_exp: new Date(now + (Number(j.expires_in) || 21599) * 1000).toISOString(),
      refresh_exp: j.refresh_token_expires_in ? new Date(now + Number(j.refresh_token_expires_in) * 1000).toISOString() : null,
      is_on: true, last_error: null, updated_at: new Date(now).toISOString(),
    };
    const s = await fetch(url + '/rest/v1/cf_kakao_memo?on_conflict=user_id', {
      method: 'POST',
      headers: { apikey: svc, Authorization: 'Bearer ' + svc, 'Content-Type': 'application/json', Prefer: 'resolution=merge-duplicates,return=minimal' },
      body: JSON.stringify(row),
    });
    if (!s.ok) return back('db');
    return back('ok');
  } catch {
    return back('token');
  }
}

/* hub.js kakaoLink 의 state: "kk1.<본문>.<서명>" · 본문 { u: 계정, e: 끝나는 때, n } */
async function checkState(env, state) {
  try {
    const p = String(state).split('.');
    if (p.length !== 3 || p[0] !== 'kk1') return '';
    const k = await crypto.subtle.importKey('raw', new TextEncoder().encode('coverfo-kakao-memo:' + (env.SUPABASE_SERVICE_ROLE_KEY || '')), { name: 'HMAC', hash: 'SHA-256' }, false, ['verify']);
    const ok = await crypto.subtle.verify('HMAC', k, fromB64u(p[2]), new TextEncoder().encode('kk1.' + p[1]));
    if (!ok) return '';
    const o = JSON.parse(new TextDecoder().decode(fromB64u(p[1])));
    if (!o || !o.u || !(o.e > Date.now())) return '';
    return /^[0-9a-f-]{36}$/i.test(String(o.u)) ? String(o.u) : '';
  } catch { return ''; }
}
function fromB64u(s) {
  s = String(s).replace(/-/g, '+').replace(/_/g, '/'); while (s.length % 4) s += '=';
  return Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
}
