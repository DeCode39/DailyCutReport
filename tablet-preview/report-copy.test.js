const test=require('node:test');
const assert=require('node:assert/strict');
const M=require('./model.js');
const R=require('./report-copy.js');

test('weekly report includes seven ascending local dates across year and leap boundaries',()=>{
  const state=M.empty('2026-01-03');
  const json=JSON.parse(R.weeklyReportJson(M,state,'2026-01-03','2026-01-04T00:00:00Z'));
  assert.equal(json.startDate,'2025-12-28');assert.equal(json.endDate,'2026-01-03');
  assert.equal(json.schemaVersion,1);assert.equal(json.dailyReportSchemaVersion,3);
  assert.deepEqual(json.days.map(d=>d.date),['2025-12-28','2025-12-29','2025-12-30','2025-12-31','2026-01-01','2026-01-02','2026-01-03']);
  assert.ok(json.days.every(d=>d.schemaVersion===3&&d.generatedAt===json.generatedAt&&d.foodGroups.length===0&&d.energy.projectedFinalBurnKcal===null));
  assert.equal(JSON.parse(R.weeklyReportJson(M,state,'2024-03-06')).startDate,'2024-02-29');
  assert.throws(()=>R.weeklyReportJson(M,state,'2026-02-30'),/Invalid/);
});

test('weekly export keeps logs and spending on their original dates and never exports barcodes',()=>{
  const state=M.empty('2026-01-03'),product=M.upsertProduct(state,{productId:'a',name:'Food',barcode:'secret-code',calories:97.99999999996,purchasePriceMicros:1000000});
  M.addLog(state,'2025-12-31',product,1,500000);
  const before=JSON.stringify(state),json=R.weeklyReportJson(M,state,'2026-01-03');
  const days=JSON.parse(json).days;
  assert.equal(days[3].nutrition.caloriesKcal,98);
  assert.equal(days[3].spending.knownTotalMicros,500000);
  assert.equal(days[6].nutrition.entries,0);
  assert.ok(!json.includes('secret-code'));
  assert.equal(JSON.stringify(state),before);
});

function controls(){
  const button=new EventTarget(),counts={day:0,week:0};let callback=null;
  const timers={setTimeout:fn=>{callback=fn;return 1;},clearTimeout:()=>{callback=null;}};
  R.bindCopyButton(button,()=>counts.day++,()=>counts.week++,timers);
  return{counts,fire:()=>{const fn=callback;callback=null;fn?.();},send:(name,props={})=>button.dispatchEvent(Object.assign(new Event(name,{cancelable:true}),{button:0,pointerId:1,clientX:0,clientY:0,...props}))};
}
test('tap copies only day, hold copies only week and suppresses trailing click',()=>{
  const c=controls();c.send('pointerdown');c.send('pointerup');c.send('click');c.fire();
  assert.deepEqual(c.counts,{day:1,week:0});
  c.send('pointerdown');c.fire();c.send('pointerup');c.send('click');
  assert.deepEqual(c.counts,{day:1,week:1});
});
test('scrolling or cancelling aborts copy and Shift+Enter copies week once',()=>{
  const c=controls();c.send('pointerdown');c.send('pointermove',{clientY:30});c.fire();c.send('pointerup');c.send('click');
  c.send('pointerdown');c.send('pointercancel');c.fire();c.send('click');
  assert.deepEqual(c.counts,{day:0,week:0});
  c.send('keydown',{key:'Enter',shiftKey:true,repeat:false});
  c.send('keydown',{key:'Enter',shiftKey:true,repeat:true});
  assert.deepEqual(c.counts,{day:0,week:1});
});
