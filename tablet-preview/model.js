(function(root,factory){const api=factory();if(typeof module==='object'&&module.exports)module.exports=api;else root.DailyCutModel=api;})(this,function(){
  const KEY='dcr_v2';
  const number=value=>{const parsed=Number(String(value??'').replace(',','.'));return Number.isFinite(parsed)?parsed:0;};
  const optional=value=>String(value??'').trim()===''?null:number(value);
  const localDate=date=>{const y=date.getFullYear(),m=String(date.getMonth()+1).padStart(2,'0'),d=String(date.getDate()).padStart(2,'0');return `${y}-${m}-${d}`;};
  function empty(today){return{version:2,selectedDate:today,reports:{},products:{},logs:[],nextLogId:1};}
  function migrate(storage,now=new Date()){
    const today=localDate(now);const raw=storage.getItem(KEY);
    if(raw){try{const parsed=JSON.parse(raw);if(parsed.version===2)return parsed;}catch(_){}}
    const state=empty(today);const oldDate=storage.getItem('dcr_date')||today;
    const hasLegacy=['steps','burn','food','protein','sodium','notes'].some(k=>storage.getItem('dcr_'+k)!==null);
    if(hasLegacy){state.selectedDate=oldDate>today?today:oldDate;state.reports[state.selectedDate]={steps:number(storage.getItem('dcr_steps')),distance:0,burn:optional(storage.getItem('dcr_burn')),foodOverride:optional(storage.getItem('dcr_food')),proteinOverride:optional(storage.getItem('dcr_protein')),sodiumOverride:optional(storage.getItem('dcr_sodium')),notes:storage.getItem('dcr_notes')||''};}
    storage.setItem(KEY,JSON.stringify(state));return state;
  }
  function save(storage,state){storage.setItem(KEY,JSON.stringify(state));}
  function totals(state,date){return state.logs.filter(x=>x.date===date).reduce((t,x)=>{const q=number(x.quantity);t.entries++;for(const key of ['calories','protein','sodium','carbs','fat','sugar','fiber','saturated'])t[key]+=number(x[key])*q;return t;},{entries:0,calories:0,protein:0,sodium:0,carbs:0,fat:0,sugar:0,fiber:0,saturated:0});}
  function report(state,date){const stored=state.reports[date]||{},food=totals(state,date);const burn=stored.burn??0;const calories=stored.foodOverride??food.calories;const protein=stored.proteinOverride??food.protein;const sodium=stored.sodiumOverride??food.sodium;const deficit=burn-calories;const verdict=deficit>=300?'Cut day':deficit<=-200?'Surplus day':'Maintenance-ish';return{...stored,date,food,burn,calories,protein,sodium,deficit,verdict};}
  function upsertProduct(state,product){state.products[product.barcode]={...product,updatedAt:Date.now()};return state.products[product.barcode];}
  function addLog(state,date,product,quantity){const log={id:state.nextLogId++,date,quantity:number(quantity)||1,...product};state.logs.push(log);return log;}
  function updateLog(state,id,changes){const index=state.logs.findIndex(x=>x.id===id);if(index>=0)state.logs[index]={...state.logs[index],...changes};return state.logs[index]||null;}
  function deleteLog(state,id){state.logs=state.logs.filter(x=>x.id!==id);}
  return{KEY,number,optional,localDate,empty,migrate,save,totals,report,upsertProduct,addLog,updateLog,deleteLog};
});

