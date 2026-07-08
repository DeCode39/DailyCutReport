(function(root,factory){const catalog=typeof module==='object'&&module.exports?require('./catalog.js'):root.DailyCutCatalog;const api=factory(catalog||[]);if(typeof module==='object'&&module.exports)module.exports=api;else root.DailyCutModel=api;})(this,function(catalog){
  const KEY='dcr_v3',OLD_KEY='dcr_v2';
  const number=value=>{const parsed=Number(String(value??'').replace(',','.'));return Number.isFinite(parsed)?parsed:0;};
  const optional=value=>String(value??'').trim()===''?null:number(value);
  const localDate=date=>{const y=date.getFullYear(),m=String(date.getMonth()+1).padStart(2,'0'),d=String(date.getDate()).padStart(2,'0');return `${y}-${m}-${d}`;};
  const uuid=()=>globalThis.crypto?.randomUUID?.()||`local-${Date.now()}-${Math.random().toString(16).slice(2)}`;
  function empty(today){return{version:3,selectedDate:today,reports:{},products:{},logs:[],nextLogId:1};}
  function seed(state){for(const product of catalog)if(!state.products[product.productId])state.products[product.productId]={...product,updatedAt:Date.now()};return state;}
  function migrate(storage,now=new Date()){
    const today=localDate(now),raw=storage.getItem(KEY);
    if(raw){try{const parsed=JSON.parse(raw);if(parsed.version===3)return seed(parsed);}catch(_){}}
    const oldRaw=storage.getItem(OLD_KEY);if(oldRaw){try{const old=JSON.parse(oldRaw);if(old.version===2){const state=empty((old.selectedDate||today)>today?today:(old.selectedDate||today));state.reports=old.reports||{};for(const [key,p] of Object.entries(old.products||{})){const productId=p.productId||key;const barcode=/^\d{8,14}$/.test(p.barcode||'')?p.barcode:null;state.products[productId]={...p,productId,barcode};}state.logs=(old.logs||[]).map(log=>{const productId=log.productId||log.barcode||uuid();return{...log,productId,barcode:/^\d{8,14}$/.test(log.barcode||'')?log.barcode:null};});state.nextLogId=old.nextLogId||Math.max(0,...state.logs.map(x=>x.id))+1;seed(state);storage.setItem(KEY,JSON.stringify(state));return state;}}catch(_){}}
    const state=empty(today),oldDate=storage.getItem('dcr_date')||today;const hasLegacy=['steps','burn','food','protein','sodium','notes'].some(k=>storage.getItem('dcr_'+k)!==null);
    if(hasLegacy){state.selectedDate=oldDate>today?today:oldDate;state.reports[state.selectedDate]={steps:number(storage.getItem('dcr_steps')),distance:0,burn:optional(storage.getItem('dcr_burn')),foodOverride:optional(storage.getItem('dcr_food')),proteinOverride:optional(storage.getItem('dcr_protein')),sodiumOverride:optional(storage.getItem('dcr_sodium')),notes:storage.getItem('dcr_notes')||''};}
    seed(state);storage.setItem(KEY,JSON.stringify(state));return state;
  }
  function save(storage,state){storage.setItem(KEY,JSON.stringify(state));}
  function totals(state,date){return state.logs.filter(x=>x.date===date).reduce((t,x)=>{const q=number(x.quantity);t.entries++;for(const key of ['calories','protein','sodium','carbs','fat','sugar','fiber','saturated'])t[key]+=number(x[key])*q;return t;},{entries:0,calories:0,protein:0,sodium:0,carbs:0,fat:0,sugar:0,fiber:0,saturated:0});}
  function report(state,date){const stored=state.reports[date]||{},food=totals(state,date),burn=stored.burn??0,calories=stored.foodOverride??food.calories,protein=stored.proteinOverride??food.protein,sodium=stored.sodiumOverride??food.sodium,deficit=burn-calories;return{...stored,date,food,burn,calories,protein,sodium,deficit,verdict:deficit>=300?'Cut day':deficit<=-200?'Surplus day':'Maintenance-ish'};}
  function upsertProduct(state,product){const productId=product.productId||uuid();const barcode=String(product.barcode||'').trim()||null;if(barcode&&Object.values(state.products).some(p=>p.productId!==productId&&p.barcode===barcode))throw new Error('Barcode is already assigned.');state.products[productId]={...product,productId,barcode,updatedAt:Date.now()};return state.products[productId];}
  function findBarcode(state,barcode){return Object.values(state.products).find(p=>p.barcode===String(barcode).trim())||null;}
  function addLog(state,date,product,quantity){const log={id:state.nextLogId++,date,quantity:number(quantity)||1,...product,productId:product.productId||null,barcode:product.barcode||null};state.logs.push(log);return log;}
  function updateLog(state,id,changes){const index=state.logs.findIndex(x=>x.id===id);if(index>=0)state.logs[index]={...state.logs[index],...changes};return state.logs[index]||null;}
  function deleteLog(state,id){state.logs=state.logs.filter(x=>x.id!==id);}
  return{KEY,OLD_KEY,number,optional,localDate,empty,migrate,save,seed,totals,report,upsertProduct,findBarcode,addLog,updateLog,deleteLog,uuid,catalog};
});
