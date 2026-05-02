import React, { useEffect, useMemo, useRef, useState } from 'react';
import { MapContainer, TileLayer, Polyline, CircleMarker, Tooltip, useMap } from 'react-leaflet';
import L from 'leaflet';
import { fetchSessions, fetchSessionRows } from './api.js';
import { doseColor, sessionColor, fmtTs, fmtDose } from './colors.js';

// ---- helpers ---------------------------------------------------------------

function bboxFromPoints(points) {
  if (!points.length) return null;
  let minLat = points[0].lat, maxLat = points[0].lat;
  let minLng = points[0].lng, maxLng = points[0].lng;
  for (const p of points) {
    if (p.lat < minLat) minLat = p.lat;
    if (p.lat > maxLat) maxLat = p.lat;
    if (p.lng < minLng) minLng = p.lng;
    if (p.lng > maxLng) maxLng = p.lng;
  }
  return L.latLngBounds([minLat, minLng], [maxLat, maxLng]);
}

function FitBoundsOnce({ bounds, dep }) {
  const map = useMap();
  useEffect(() => {
    if (bounds && bounds.isValid()) {
      map.fitBounds(bounds.pad(0.1), { animate: true });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dep]);
  return null;
}

// Build a stable, low-precision key from a LatLngBounds so we only re-fit
// when the visible footprint actually changes (not on every render).
function boundsKey(b) {
  if (!b || !b.isValid()) return '';
  const sw = b.getSouthWest();
  const ne = b.getNorthEast();
  return `${sw.lat.toFixed(4)},${sw.lng.toFixed(4)},${ne.lat.toFixed(4)},${ne.lng.toFixed(4)}`;
}

// Anything older than 2020-01-01 UTC is treated as garbage (e.g. a sample
// written before the device acquired UTC time, where ts collapses to
// `millis()` since boot -- a few hundred ms). One such row in a session is
// enough to make the session span ~56 years and collapse the time slider.
const MIN_VALID_TS_MS = 1577836800000;

function compactRows(raw) {
  return raw
    .filter(r => r.timestampMs != null && r.timestampMs >= MIN_VALID_TS_MS)
    .map(r => ({
      ts:  r.timestampMs,
      lat: r.latitude,
      lng: r.longitude,
      uSv: r.uSvPerHour,
      cps: r.cps,
    }));
}

// ---- main app --------------------------------------------------------------

export default function App() {
  const [sessions, setSessions]   = useState([]);          // session list metadata
  const [loading, setLoading]     = useState(true);
  const [error, setError]         = useState(null);
  const [rowsBySession, setRows]  = useState({});           // id -> [{ts, lat, lng, uSv, cps}]
  const [selected, setSelected]   = useState(new Set());
  const [doseMin, setDoseMin]     = useState(0);            // uSv/h
  const [doseMax, setDoseMax]     = useState(1.0);          // uSv/h
  // Set true once the user manually edits min/max so we stop auto-fitting.
  const [doseScaleManual, setDoseScaleManual] = useState(false);
  const [showPoints, setShowPoints] = useState(true);
  const [colorByDose, setColorByDose] = useState(true);
  const [nanoMode, setNanoMode]     = useState(false);
  const [timeFrac, setTimeFrac]     = useState(1.0);        // 0..1 cumulative cursor
  const [windowFrac, setWindowFrac] = useState(1.0);        // 0..1 trailing window size
  const [playing, setPlaying]       = useState(false);
  const [fitTrigger, setFitTrigger] = useState(0);
  const playRef = useRef();

  // ---- load session list once
  useEffect(() => {
    fetchSessions()
      .then(s => { setSessions(s); setLoading(false); })
      .catch(e => { setError(String(e)); setLoading(false); });
  }, []);

  // ---- when the user toggles a session, lazy-fetch its rows
  async function toggleSession(id) {
    const next = new Set(selected);
    if (next.has(id)) {
      next.delete(id);
    } else {
      next.add(id);
      if (!rowsBySession[id]) {
        try {
          const raw = await fetchSessionRows(id);
          setRows(prev => ({ ...prev, [id]: compactRows(raw) }));
        } catch (e) {
          setError(String(e));
        }
      }
    }
    setSelected(next);
    setFitTrigger(t => t + 1);
  }

  function selectAll() {
    const next = new Set(sessions.map(s => s.sessionId));
    setSelected(next);
    // Lazy-fetch any rows we haven't loaded yet.
    // NOTE: do NOT bump fitTrigger here -- the map auto-refits via the
    // boundsKey effect below as soon as rows actually land.
    for (const id of next) {
      if (!rowsBySession[id]) {
        fetchSessionRows(id).then(raw => {
          setRows(prev => ({ ...prev, [id]: compactRows(raw) }));
        }).catch(e => setError(String(e)));
      }
    }
  }
  function selectNone() { setSelected(new Set()); }

  // ---- precompute trace data for selected sessions
  const traces = useMemo(() => {
    const out = [];
    let idx = 0;
    for (const s of sessions) {
      if (!selected.has(s.sessionId)) { idx++; continue; }
      const rows = rowsBySession[s.sessionId];
      if (!rows) { out.push({ id: s.sessionId, color: sessionColor(idx), points: null, loading: true }); idx++; continue; }
      const points = rows
        .filter(r => r.lat != null && r.lng != null && !(r.lat === 0 && r.lng === 0))
        .map(r => ({ lat: r.lat, lng: r.lng, ts: r.ts, uSv: r.uSv, cps: r.cps }));
      out.push({
        id: s.sessionId,
        color: sessionColor(idx),
        points,
        rows,
        meta: s,
      });
      idx++;
    }
    return out;
  }, [sessions, selected, rowsBySession]);

  // ---- global time bounds across selection
  const tBounds = useMemo(() => {
    let lo = Infinity, hi = -Infinity;
    for (const t of traces) {
      if (!t.rows) continue;
      for (const r of t.rows) {
        if (r.ts < lo) lo = r.ts;
        if (r.ts > hi) hi = r.ts;
      }
    }
    if (!isFinite(lo)) return null;
    return { lo, hi, span: Math.max(1, hi - lo) };
  }, [traces]);

  // ---- bounds for fit
  const fitBounds = useMemo(() => {
    const all = [];
    for (const t of traces) if (t.points) for (const p of t.points) all.push(p);
    return bboxFromPoints(all);
  }, [traces]);

  // Auto-refit the map whenever the bbox actually changes (e.g. a session's
  // rows finish loading, or the selection changes). This is what makes the
  // viewer "draw all the way out" -- previously the map was zoomed before
  // rows arrived, so the polyline rendered outside the viewport and looked
  // truncated.
  const fitKey = boundsKey(fitBounds);
  const lastFitKeyRef = useRef('');
  useEffect(() => {
    if (!fitKey) return;
    if (fitKey === lastFitKeyRef.current) return;
    lastFitKeyRef.current = fitKey;
    setFitTrigger(t => t + 1);
  }, [fitKey]);

  // Auto-fit the dose color scale to the loaded data the first time rows
  // arrive (and on every subsequent selection change) so the gradient maps
  // usefully across the actual range. 5th/95th percentile clamps outliers.
  // Stops once the user manually adjusts the scale.
  useEffect(() => {
    if (doseScaleManual) return;
    const vals = [];
    for (const id of selected) {
      const rows = rowsBySession[id];
      if (!rows) continue;
      for (const r of rows) {
        if (typeof r.uSv === 'number' && isFinite(r.uSv)) vals.push(r.uSv);
      }
    }
    if (vals.length < 2) return;
    vals.sort((a, b) => a - b);
    const lo = vals[Math.floor(vals.length * 0.05)];
    const hi = vals[Math.floor(vals.length * 0.95)];
    if (!(hi > lo)) return;
    setDoseMin(parseFloat(lo.toFixed(3)));
    setDoseMax(parseFloat(hi.toFixed(3)));
  }, [rowsBySession, selected, doseScaleManual]);

  // ---- play/pause
  useEffect(() => {
    if (!playing) { clearInterval(playRef.current); return; }
    playRef.current = setInterval(() => {
      setTimeFrac(prev => {
        const next = prev + 0.005;
        if (next >= 1) { return 1; }
        return next;
      });
    }, 60);
    return () => clearInterval(playRef.current);
  }, [playing]);
  useEffect(() => { if (playing && timeFrac >= 1) setPlaying(false); }, [timeFrac, playing]);

  // ---- compute filtered points (windowed cursor)
  const filteredTraces = useMemo(() => {
    if (!tBounds) return traces.map(t => ({ ...t, filtered: t.points || [] }));
    const cursor = tBounds.lo + tBounds.span * timeFrac;
    const winSpan = tBounds.span * windowFrac;
    const windowLo = cursor - winSpan;
    return traces.map(t => {
      if (!t.points) return { ...t, filtered: [] };
      const filtered = t.points.filter(p => p.ts >= windowLo && p.ts <= cursor);
      return { ...t, filtered, cursorTs: cursor };
    });
  }, [traces, tBounds, timeFrac, windowFrac]);

  // ---- aggregate stats over filtered points
  const stats = useMemo(() => {
    let n = 0, sum = 0, max = -Infinity, min = Infinity;
    for (const t of filteredTraces) {
      for (const p of t.filtered) {
        if (p.uSv == null) continue;
        n++; sum += p.uSv;
        if (p.uSv > max) max = p.uSv;
        if (p.uSv < min) min = p.uSv;
      }
    }
    return {
      count: n,
      avg: n ? sum / n : null,
      max: n ? max : null,
      min: n ? min : null,
    };
  }, [filteredTraces]);

  // ---- ui --------------------------------------------------------------
  return (
    <div className="app">
      <aside className="sidebar">
        <header>
          <h1>Vega Tracker</h1>
          <div className="muted">{sessions.length} session{sessions.length === 1 ? '' : 's'}</div>
        </header>

        <div className="row">
          <button onClick={selectAll}>Select all</button>
          <button onClick={selectNone}>Clear</button>
          <button onClick={() => setFitTrigger(x => x + 1)}>Fit</button>
        </div>

        {error && <div className="error">{error}</div>}
        {loading && <div className="muted">loading...</div>}

        <ul className="sessions">
          {sessions.map((s, i) => {
            const isSel = selected.has(s.sessionId);
            const c = sessionColor(i);
            // Guard against firstTsMs poisoned by pre-2020 millis()-since-boot
            // timestamps from old firmware.  Show "(date unknown)" instead of 1970.
            const firstOk = s.firstTsMs && s.firstTsMs >= MIN_VALID_TS_MS;
            const dt = firstOk ? new Date(s.firstTsMs) : null;
            const lastOk  = s.lastTsMs  && s.lastTsMs  >= MIN_VALID_TS_MS;
            const dur = (firstOk && lastOk)
              ? Math.round((s.lastTsMs - s.firstTsMs) / 1000) : null;
            return (
              <li key={s.sessionId} className={isSel ? 'sel' : ''}>
                <label>
                  <input type="checkbox" checked={isSel} onChange={() => toggleSession(s.sessionId)} />
                  <span className="swatch" style={{ background: c }} />
                  <span className="sid">{s.sessionId}</span>
                </label>
                <div className="meta">
                  {dt ? dt.toLocaleString() : '-'}
                  &nbsp;|&nbsp;{s.samples ?? 0} pts
                  {dur != null && <>&nbsp;|&nbsp;{dur}s</>}
                  {s.trackerId && <>&nbsp;|&nbsp;{s.trackerId.slice(-8)}</>}
                </div>
              </li>
            );
          })}
        </ul>

        <section className="controls">
          <h3>Display</h3>
          <label className="check"><input type="checkbox" checked={showPoints} onChange={e => setShowPoints(e.target.checked)} /> Show sample points</label>
          <label className="check"><input type="checkbox" checked={colorByDose} onChange={e => setColorByDose(e.target.checked)} /> Color line by dose</label>
          <label className="check"><input type="checkbox" checked={nanoMode} onChange={e => setNanoMode(e.target.checked)} /> Display nSv/h</label>

          <h3>Dose color scale ({nanoMode ? 'nSv/h' : '\u00B5Sv/h'})</h3>
          <div className="row">
            <label>min<input type="number" step="0.01" value={doseMin} onChange={e => { setDoseScaleManual(true); setDoseMin(parseFloat(e.target.value) || 0); }} /></label>
            <label>max<input type="number" step="0.01" value={doseMax} onChange={e => { setDoseScaleManual(true); setDoseMax(parseFloat(e.target.value) || 0.001); }} /></label>
          </div>
          <div className="legend">
            <span style={{ background: doseColor(doseMin, doseMin, doseMax) }} />
            <span style={{ background: doseColor(doseMin + (doseMax - doseMin) * 0.25, doseMin, doseMax) }} />
            <span style={{ background: doseColor(doseMin + (doseMax - doseMin) * 0.5, doseMin, doseMax) }} />
            <span style={{ background: doseColor(doseMin + (doseMax - doseMin) * 0.75, doseMin, doseMax) }} />
            <span style={{ background: doseColor(doseMax, doseMin, doseMax) }} />
          </div>

          <h3>Stats (in window)</h3>
          <div className="stats">
            <div>Points: <b>{stats.count}</b></div>
            <div>Avg: <b>{fmtDose(stats.avg, nanoMode)}</b></div>
            <div>Max: <b>{fmtDose(stats.max, nanoMode)}</b></div>
            <div>Min: <b>{fmtDose(stats.min, nanoMode)}</b></div>
          </div>
        </section>
      </aside>

      <main className="map-pane">
        <MapContainer center={[39.5, -98.35]} zoom={4} style={{ width: '100%', height: '100%' }}>
          <TileLayer
            attribution='&copy; OpenStreetMap'
            url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
          />
          {fitBounds && <FitBoundsOnce bounds={fitBounds} dep={fitTrigger} />}

          {filteredTraces.map(t => {
            if (!t.filtered || t.filtered.length === 0) return null;
            // Build polyline segments. If colorByDose, use a per-segment color.
            if (colorByDose) {
              const segs = [];
              for (let i = 1; i < t.filtered.length; i++) {
                const a = t.filtered[i - 1], b = t.filtered[i];
                const u = b.uSv ?? a.uSv ?? 0;
                segs.push(
                  <Polyline key={`${t.id}-${i}`}
                    positions={[[a.lat, a.lng], [b.lat, b.lng]]}
                    pathOptions={{ color: doseColor(u, doseMin, doseMax), weight: 4, opacity: 0.85 }}
                  />
                );
              }
              return <React.Fragment key={t.id}>{segs}</React.Fragment>;
            }
            const positions = t.filtered.map(p => [p.lat, p.lng]);
            return (
              <Polyline key={t.id} positions={positions}
                pathOptions={{ color: t.color, weight: 4, opacity: 0.85 }} />
            );
          })}

          {showPoints && filteredTraces.map(t => (
            <React.Fragment key={`${t.id}-pts`}>
              {t.filtered.map((p, i) => (
                <CircleMarker key={i} center={[p.lat, p.lng]} radius={4}
                  pathOptions={{
                    color: colorByDose ? doseColor(p.uSv, doseMin, doseMax) : t.color,
                    fillColor: colorByDose ? doseColor(p.uSv, doseMin, doseMax) : t.color,
                    fillOpacity: 0.95, weight: 1,
                  }}>
                  <Tooltip direction="top" offset={[0, -4]} opacity={0.9}>
                    <div style={{ fontSize: 12 }}>
                      <div><b>{t.id}</b></div>
                      <div>{fmtTs(p.ts)}</div>
                      <div>{fmtDose(p.uSv, nanoMode)}</div>
                      <div>{p.cps?.toFixed?.(2) ?? p.cps} cps</div>
                    </div>
                  </Tooltip>
                </CircleMarker>
              ))}
              {t.filtered.length > 0 && (
                <CircleMarker center={[t.filtered[t.filtered.length - 1].lat, t.filtered[t.filtered.length - 1].lng]}
                  radius={6} pathOptions={{ color: '#fff', fillColor: t.color, fillOpacity: 1, weight: 2 }}>
                  <Tooltip direction="top" permanent>{t.id}</Tooltip>
                </CircleMarker>
              )}
            </React.Fragment>
          ))}
        </MapContainer>

        <div className="scrubber">
          <button onClick={() => setPlaying(p => !p)}>{playing ? 'Pause' : 'Play'}</button>
          <button onClick={() => { setTimeFrac(0); setPlaying(false); }}>Rewind</button>
          <div className="t-row">
            <label>Cursor</label>
            <input type="range" min="0" max="1" step="0.001"
              value={timeFrac} onChange={e => setTimeFrac(parseFloat(e.target.value))} />
            <span className="t-val">
              {tBounds ? fmtTs(tBounds.lo + tBounds.span * timeFrac) : '--'}
            </span>
          </div>
          <div className="t-row">
            <label>Window</label>
            <input type="range" min="0.005" max="1" step="0.005"
              value={windowFrac} onChange={e => setWindowFrac(parseFloat(e.target.value))} />
            <span className="t-val">
              {tBounds
                ? `${(tBounds.span * windowFrac / 1000).toFixed(0)}s`
                : '--'}
            </span>
          </div>
        </div>
      </main>
    </div>
  );
}
