// API_BASE is injected at runtime by nginx via /config.js (see public/config.js
// and the Dockerfile). Falls back to same-origin for `npm run dev`.
const RUNTIME = (typeof window !== 'undefined' && window.__VEGA_CONFIG__) || {};
export const API_BASE =
  RUNTIME.apiBase ||
  import.meta.env.VITE_API_URL ||
  'http://192.168.86.48:8030';

export async function fetchSessions() {
  const r = await fetch(`${API_BASE}/sessions?limit=500`);
  if (!r.ok) throw new Error(`sessions ${r.status}`);
  return r.json();
}

// Pull all rows for a session; the API pages at 1000/page so we walk it.
export async function fetchSessionRows(sessionId, { pageSize = 5000 } = {}) {
  let skip = 0;
  const out = [];
  for (;;) {
    const r = await fetch(
      `${API_BASE}/sessions/${encodeURIComponent(sessionId)}?limit=${pageSize}&skip=${skip}`
    );
    if (!r.ok) throw new Error(`session ${sessionId} ${r.status}`);
    const j = await r.json();
    if (!j.rows || j.rows.length === 0) break;
    for (const row of j.rows) out.push(row);
    if (j.rows.length < pageSize) break;
    skip += j.rows.length;
    if (skip > 200000) break; // safety
  }
  return out;
}
