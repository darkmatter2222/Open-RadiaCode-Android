// Color helpers: HSL gradient from low (green) -> mid (amber) -> high (red).
// Domain is configurable from the UI; we map dose into [0,1] then to a hue.
export function doseColor(uSv, lo, hi) {
  if (uSv == null || isNaN(uSv)) return '#666';
  const t = Math.max(0, Math.min(1, (uSv - lo) / Math.max(1e-9, hi - lo)));
  // hue: 130 (green) -> 50 (amber) -> 0 (red)
  const hue = 130 - 130 * t;
  const sat = 85;
  const light = 50;
  return `hsl(${hue.toFixed(0)}, ${sat}%, ${light}%)`;
}

// Distinct hue per session, rotating around the wheel.
export function sessionColor(idx) {
  const hue = (idx * 47) % 360;
  return `hsl(${hue}, 70%, 55%)`;
}

export function fmtTs(ms) {
  if (!ms) return '';
  const d = new Date(ms);
  return d.toLocaleString();
}

export function fmtDose(uSv, nano) {
  if (uSv == null) return '-';
  if (nano) return `${(uSv * 1000).toFixed(1)} nSv/h`;
  return `${uSv.toFixed(3)} \u00B5Sv/h`;
}
