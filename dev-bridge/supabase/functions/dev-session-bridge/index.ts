// dev-session-bridge — SEPARATE Edge Function for this development session.
// Same protocol/tables as test-device-pairing, different slug so work from
// this session never touches the original bridge.
// Deploy: supabase functions deploy dev-session-bridge --project-ref lwmjhmmpsoxukynbfksw

const headers = { 'content-type': 'application/json', 'cache-control': 'no-store' };
const respond = (data: unknown, status = 200) =>
  new Response(JSON.stringify(data), { status, headers });

const hex = async (s: string) =>
  Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(s))))
    .map((v) => v.toString(16).padStart(2, '0')).join('');

const base = Deno.env.get('SUPABASE_URL');
const key = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY');

async function db(path: string, method = 'GET', body?: unknown) {
  const r = await fetch(base + '/rest/v1/' + path, {
    method,
    headers: {
      apikey: key!,
      authorization: 'Bearer ' + key,
      'content-type': 'application/json',
      prefer: 'return=representation',
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!r.ok) throw new Error('database');
  return await r.json();
}

Deno.serve(async (req) => {
  try {
    if (req.method !== 'POST') return respond({ ok: false }, 405);
    const text = await req.text();
    if (text.length > 24000) return respond({ ok: false }, 413);
    const b = JSON.parse(text);
    const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
    if (!uuid.test(b.id || '') || !/^[0-9a-f]{64}$/.test(b.token || '')) {
      return respond({ ok: false, error: 'invalid' }, 400);
    }
    const token = await hex(b.token);

    if (b.op === 'pair') {
      if (!/^[0-9a-f]{32}$/.test(b.code || '')) {
        return respond({ ok: false, error: 'invalid' }, 400);
      }
      const r = await db('rpc/bridge_consume_pair', 'POST', {
        p_code_hash: await hex(b.code),
        p_token_hash: token,
        p_device_id: b.id,
      });
      return r === true
        ? respond({ ok: true })
        : respond({ ok: false, error: 'pair code invalid or expired' }, 403);
    }

    if (!['heartbeat', 'poll', 'result'].includes(b.op)) {
      return respond({ ok: false, error: 'unknown operation' }, 400);
    }
    const d = await db(
      'bridge_devices?id=eq.' + b.id + '&token_hash=eq.' + token + '&revoked_at=is.null&select=id',
      'PATCH',
      { last_seen: new Date().toISOString() },
    );
    if (!Array.isArray(d) || d.length !== 1) {
      return respond({ ok: false, error: 'unauthorized' }, 403);
    }

    if (b.op === 'heartbeat') return respond({ ok: true, phase: 'paired' });

    if (b.op === 'poll') {
      const rows = await db('rpc/bridge_claim_command', 'POST', { p_device_id: b.id });
      return respond({
        ok: true,
        command: rows.length ? { id: rows[0].id, action: rows[0].action, args: rows[0].args } : null,
      });
    }

    // op === 'result'
    if (!uuid.test(b.command_id || '') || typeof b.message !== 'string' || b.message.length > 12000) {
      return respond({ ok: false, error: 'invalid result' }, 400);
    }
    const rows = await db(
      'bridge_commands?id=eq.' + b.command_id + '&device_id=eq.' + b.id +
        '&status=eq.claimed&expires_at=gt.' + encodeURIComponent(new Date().toISOString()) + '&select=id',
      'PATCH',
      { status: b.success === false ? 'failed' : 'completed', result: { message: b.message } },
    );
    return rows.length === 1
      ? respond({ ok: true })
      : respond({ ok: false, error: 'command not active' }, 409);
  } catch (e) {
    return respond({ ok: false, error: 'request failed' }, 400);
  }
});
