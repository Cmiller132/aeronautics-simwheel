#!/usr/bin/env python3
"""Render the driving-sim CSV (from :engine:drivingDemo) into a self-contained
HTML trace report. Part of the headless feel harness (DESIGN.md §10.5).

Usage: render_sim_report.py [in.csv] [out.html]
"""
import csv
import json
import sys
from pathlib import Path

IN = Path(sys.argv[1] if len(sys.argv) > 1 else "engine/build/driving-sim.csv")
OUT = Path(sys.argv[2] if len(sys.argv) > 2 else "engine/build/driving-sim.html")

rows = []
with IN.open() as f:
    for r in csv.DictReader(f):
        rows.append({k: float(v) for k, v in r.items()})

def series(rows, key, step=1):
    return [round(r[key], 3) for r in rows[::step]]

main = {  # every 2nd row of the 250 Hz trace -> 125 Hz
    "t": series(rows, "t", 2),
    "rim": series(rows, "outNm", 2),
    "telem": series(rows, "telemetryNm", 2),
    "raw": series(rows, "rawNm", 2),
}
curb_rows = [r for r in rows if 10.30 <= r["t"] <= 11.10]
curb = {
    "t": series(curb_rows, "t"),
    "raw": series(curb_rows, "rawNm"),
    "telem": series(curb_rows, "telemetryNm"),
    "imp": series(curb_rows, "impulseNm"),
    "rim": series(curb_rows, "outNm"),
}
lock_rows = [r for r in rows if 24.0 <= r["t"]]
lock = {
    "t": series(lock_rows, "t"),
    "cmd": series(lock_rows, "steerCmdDeg"),
    "rim": series(lock_rows, "outNm"),
}

PHASES = [(0, 5, "A smooth weave"), (5, 10, "B bumpy blocks"), (10, 12, "C curb"),
          (12, 17, "D corner"), (17, 18, "E dropout"), (18, 19, "E'"),
          (19, 24, "F recovery"), (24, 26, "G soft lock")]

data = json.dumps({"main": main, "curb": curb, "lock": lock, "phases": PHASES},
                  separators=(",", ":"))

html = """<!doctype html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>SimWheel FFB — race car over block terrain</title>
<style>
.viz-root {
  color-scheme: light;
  --surface-1:#fcfcfb; --page:#f9f9f7;
  --ink-1:#0b0b0b; --ink-2:#52514e; --muted:#898781;
  --grid:#e1e0d9; --axis:#c3c2b7; --ring:rgba(11,11,11,.10);
  --s-rim:#2a78d6; --s-raw:#eb6834; --s-telem:#1baf7a;
  --s-cmd:#eda100; --s-virt:#e87ba4;
}
@media (prefers-color-scheme: dark) {
  :root:where(:not([data-theme="light"])) .viz-root {
    color-scheme: dark;
    --surface-1:#1a1a19; --page:#0d0d0d;
    --ink-1:#ffffff; --ink-2:#c3c2b7; --muted:#898781;
    --grid:#2c2c2a; --axis:#383835; --ring:rgba(255,255,255,.10);
    --s-rim:#3987e5; --s-raw:#d95926; --s-telem:#199e70;
    --s-cmd:#c98500; --s-virt:#d55181;
  }
}
:root[data-theme="dark"] .viz-root {
  color-scheme: dark;
  --surface-1:#1a1a19; --page:#0d0d0d;
  --ink-1:#ffffff; --ink-2:#c3c2b7; --muted:#898781;
  --grid:#2c2c2a; --axis:#383835; --ring:rgba(255,255,255,.10);
  --s-rim:#3987e5; --s-raw:#d95926; --s-telem:#199e70;
  --s-cmd:#c98500; --s-virt:#d55181;
}
body{margin:0}
.viz-root{background:var(--page); color:var(--ink-1);
  font:14px/1.45 system-ui,-apple-system,"Segoe UI",sans-serif;
  padding:24px; min-height:100vh; box-sizing:border-box}
.wrap{max-width:960px;margin:0 auto}
h1{font-size:19px;margin:0 0 4px}
p.sub{color:var(--ink-2);margin:0 0 20px;font-size:13px}
.card{background:var(--surface-1);border:1px solid var(--ring);border-radius:10px;
  padding:16px 16px 8px;margin:0 0 16px}
.card h2{font-size:14px;margin:0 0 2px}
.card p{color:var(--ink-2);font-size:12.5px;margin:0 0 8px}
.legend{display:flex;gap:14px;flex-wrap:wrap;font-size:12px;color:var(--ink-2);margin:2px 0 6px}
.legend span{display:inline-flex;align-items:center;gap:5px}
.chip{width:12px;height:3px;border-radius:2px;display:inline-block}
svg{display:block;width:100%;height:auto}
.tip{position:fixed;pointer-events:none;background:var(--surface-1);
  border:1px solid var(--ring);border-radius:6px;padding:6px 9px;font-size:12px;
  box-shadow:0 2px 8px rgba(0,0,0,.15);display:none;z-index:9}
.tip b{font-variant-numeric:tabular-nums}
</style></head>
<body><div class="viz-root"><div class="wrap">
<h1>How the FFB actually behaves — race car, 15&thinsp;m/s over block terrain</h1>
<p class="sub">Headless run of the SHIPPING composition (FfbPipeline: TelemetryBuffer + soft lock + damper/friction
+ event impulses → soft-knee mixer → safety chain) fed by a quarter-car stand-in at 60&thinsp;Hz substeps,
20&thinsp;Hz packets + immediate strike events, 250&thinsp;Hz client loop. Direct steering authority — no predictor,
no sync-spring. Clamp 2.5&thinsp;Nm. Phases: smooth weave · bumpy blocks · curb strike · steady corner ·
telemetry dropout · recovery · into the soft lock.</p>
<div id="charts"></div>
</div></div>
<div class="tip" id="tip"></div>
<script>
const D = __DATA__;
const css = v => getComputedStyle(document.querySelector('.viz-root')).getPropertyValue(v).trim();
const tip = document.getElementById('tip');

function chart(cfg){
  const W=920,H=cfg.h||230,L=46,R=14,T=18,B=24,pw=W-L-R,ph=H-T-B;
  const t=cfg.t, x=v=>L+(v-t[0])/(t[t.length-1]-t[0])*pw;
  const [y0,y1]=cfg.dom, y=v=>T+(1-(Math.min(Math.max(v,y0),y1)-y0)/(y1-y0))*ph;
  const NS='http://www.w3.org/2000/svg';
  const svg=document.createElementNS(NS,'svg');
  svg.setAttribute('viewBox',`0 0 ${W} ${H}`);
  const add=(tag,at,parent)=>{const e=document.createElementNS(NS,tag);
    for(const k in at)e.setAttribute(k,at[k]);(parent||svg).appendChild(e);return e};
  // phase bands
  if(cfg.phases)for(const[f,to,name]of D.phases){
    if(to<=t[0]||f>=t[t.length-1])continue;
    if(name.includes('dropout')||name.includes('curb'))
      add('rect',{x:x(Math.max(f,t[0])),y:T,width:x(Math.min(to,t[t.length-1]))-x(Math.max(f,t[0])),
        height:ph,fill:css('--grid'),opacity:.45});
    if(!name.endsWith("'"))add('text',{x:x(Math.max(f,t[0]))+4,y:T+11,fill:css('--muted'),
      'font-size':10}, svg).textContent=name;
  }
  // gridlines + y ticks
  const ticks=cfg.ticks;
  for(const v of ticks){
    add('line',{x1:L,x2:W-R,y1:y(v),y2:y(v),stroke:css('--grid'),'stroke-width':1});
    add('text',{x:L-6,y:y(v)+3.5,fill:css('--muted'),'font-size':10,'text-anchor':'end',
      style:'font-variant-numeric:tabular-nums'},svg).textContent=v;
  }
  add('line',{x1:L,x2:W-R,y1:y(0),y2:y(0),stroke:css('--axis'),'stroke-width':1});
  // clamp guides
  if(cfg.clamp)for(const v of[cfg.clamp,-cfg.clamp]){
    add('line',{x1:L,x2:W-R,y1:y(v),y2:y(v),stroke:css('--muted'),'stroke-width':1,
      'stroke-dasharray':'4 4',opacity:.7});
  }
  // x ticks (seconds)
  const xstep=(t[t.length-1]-t[0])>4?2:0.2;
  for(let v=Math.ceil(t[0]/xstep)*xstep;v<=t[t.length-1];v+=xstep){
    add('text',{x:x(v),y:H-8,fill:css('--muted'),'font-size':10,'text-anchor':'middle',
      style:'font-variant-numeric:tabular-nums'},svg).textContent=v.toFixed(xstep<1?1:0);
  }
  // series
  for(const s of cfg.series){
    const pts=s.v.map((v,i)=>`${x(t[i]).toFixed(1)},${y(v).toFixed(1)}`).join(' ');
    add('polyline',{points:pts,fill:'none',stroke:css(s.c),'stroke-width':2,
      'stroke-linejoin':'round','stroke-linecap':'round'});
  }
  // annotations
  if(cfg.marks)for(const m of cfg.marks){
    add('line',{x1:x(m.t),x2:x(m.t),y1:T,y2:T+ph,stroke:css(m.c),'stroke-width':1,
      'stroke-dasharray':'2 3'});
    add('text',{x:x(m.t)+3,y:T+ph-6,fill:css(m.c),'font-size':10},svg).textContent=m.label;
  }
  // crosshair + tooltip
  const cross=add('line',{y1:T,y2:T+ph,stroke:css('--muted'),'stroke-width':1,opacity:0});
  svg.addEventListener('pointermove',ev=>{
    const r=svg.getBoundingClientRect(), fx=(ev.clientX-r.left)/r.width*W;
    if(fx<L||fx>W-R){cross.setAttribute('opacity',0);tip.style.display='none';return}
    const tv=t[0]+(fx-L)/pw*(t[t.length-1]-t[0]);
    let i=t.findIndex(v=>v>=tv); if(i<0)i=t.length-1;
    cross.setAttribute('x1',x(t[i]));cross.setAttribute('x2',x(t[i]));
    cross.setAttribute('opacity',.8);
    tip.style.display='block';
    tip.style.left=Math.min(ev.clientX+14,innerWidth-170)+'px';
    tip.style.top=(ev.clientY+12)+'px';
    tip.innerHTML=`t = <b>${t[i].toFixed(2)} s</b><br>`+cfg.series.map(s=>
      `<span style="color:${css(s.c)}">●</span> ${s.n}: <b>${s.v[i].toFixed(2)}</b> ${cfg.unit}`).join('<br>');
  });
  svg.addEventListener('pointerleave',()=>{cross.setAttribute('opacity',0);tip.style.display='none'});
  return svg;
}

function card(title,desc,legend,svg){
  const c=document.createElement('div');c.className='card';
  c.innerHTML=`<h2>${title}</h2><p>${desc}</p>
  <div class="legend">${legend.map(l=>
    `<span><i class="chip" style="background:${css(l.c)}"></i>${l.n}</span>`).join('')}</div>`;
  c.appendChild(svg);document.getElementById('charts').appendChild(c);
}

card('Torque at the rim (what your hands feel)',
 'Reconstructed telemetry + sync-spring + damper + friction, soft-knee mixed, safety-chained. Dashed lines: ±2.5 Nm clamp. Shaded: curb window and telemetry dropout.',
 [{n:'rim output',c:'--s-rim'},{n:'telemetry (reconstructed)',c:'--s-telem'}],
 chart({t:D.main.t,dom:[-3,3],ticks:[-2.5,0,2.5],clamp:2.5,phases:1,unit:'Nm',
   series:[{n:'telemetry',v:D.main.telem,c:'--s-telem'},{n:'rim output',v:D.main.rim,c:'--s-rim'}]}));

card('Raw hinge torque at the axle (server-side truth, own scale)',
 'What the quarter-car model generates at 60 Hz — bumpy blocks and the curb spike to ~49 Nm. The pipeline compresses this into the rim trace above.',
 [{n:'raw hinge torque',c:'--s-raw'}],
 chart({t:D.main.t,dom:[-30,50],ticks:[-25,0,25,50],phases:1,unit:'Nm',h:180,
   series:[{n:'raw',v:D.main.raw,c:'--s-raw'}]}));

card('Curb strike, zoomed (10.3–11.1 s)',
 'Two paths race to the rim: the strike fires an immediate event impulse (ms), while the sustained torque rides the 20 Hz batch through the 75 ms interpolation delay — then soft knee + clamp + slew.',
 [{n:'raw',c:'--s-raw'},{n:'telemetry',c:'--s-telem'},{n:'event impulse',c:'--s-cmd'},{n:'rim output',c:'--s-rim'}],
 chart({t:D.curb.t,dom:[-6,50],ticks:[0,25,50],unit:'Nm',
   series:[{n:'raw',v:D.curb.raw,c:'--s-raw'},{n:'telemetry',v:D.curb.telem,c:'--s-telem'},
           {n:'impulse',v:D.curb.imp,c:'--s-cmd'},{n:'rim output',v:D.curb.rim,c:'--s-rim'}]}));

card('Into the soft lock (24–26 s)',
 'The wheel is physically shoved 40° past the block\\'s ±450° range. The stop torque saturates the safety clamp — a wall at whatever ceiling the user allows, exactly the standard sim-racing behavior.',
 [{n:'hardware angle (°/100)',c:'--s-cmd'},{n:'rim output (Nm)',c:'--s-rim'}],
 chart({t:D.lock.t,dom:[-5,5],ticks:[-2.5,0,2.5],clamp:2.5,unit:'',
   series:[{n:'angle °/100',v:D.lock.cmd.map(v=>v/100),c:'--s-cmd'},
           {n:'rim Nm',v:D.lock.rim,c:'--s-rim'}]}));
</script></body></html>
"""

OUT.write_text(html.replace("__DATA__", data))
print(f"wrote {OUT} ({OUT.stat().st_size/1024:.0f} KB)")
