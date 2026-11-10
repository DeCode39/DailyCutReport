(function(root,factory){const api=factory();if(typeof module==='object'&&module.exports)module.exports=api;else root.DailyCutReportCopy=api;})(this,function(){
  function weeklyReportJson(model,state,endDate,generatedAt=new Date().toISOString()){
    const end=new Date(`${endDate}T12:00:00`);
    if(!/^\d{4}-\d{2}-\d{2}$/.test(endDate)||!Number.isFinite(end.getTime())||model.localDate(end)!==endDate)throw new Error('Invalid report date.');
    const days=[];
    for(let offset=6;offset>=0;offset--){
      const date=new Date(end);date.setDate(date.getDate()-offset);
      const day=JSON.parse(model.dailyReportJson(state,model.localDate(date)));
      day.generatedAt=generatedAt;days.push(day);
    }
    return JSON.stringify({schemaVersion:1,dailyReportSchemaVersion:3,app:'DailyCutReport',period:'week',startDate:days[0].date,endDate,generatedAt,days},null,2);
  }

  // A hold must never also trigger the browser's trailing click. Drag/scroll/cancel aborts it.
  function bindCopyButton(button,copyDay,copyWeek,timers=globalThis){
    let timer=null,start=null,held=false,blocked=false;
    const clear=()=>{if(timer!==null)timers.clearTimeout(timer);timer=null;};
    button.addEventListener('pointerdown',event=>{
      if(event.isPrimary===false||event.button!==0)return;
      clear();held=false;blocked=false;start={id:event.pointerId,x:event.clientX,y:event.clientY};
      button.setPointerCapture?.(event.pointerId);
      timer=timers.setTimeout(()=>{timer=null;held=true;copyWeek();},550);
    });
    button.addEventListener('pointermove',event=>{
      if(start&&event.pointerId===start.id&&Math.hypot(event.clientX-start.x,event.clientY-start.y)>12){clear();blocked=true;}
    });
    button.addEventListener('pointerup',()=>{clear();start=null;});
    for(const name of ['pointercancel','lostpointercapture'])button.addEventListener(name,()=>{clear();if(start)blocked=true;start=null;});
    button.addEventListener('contextmenu',event=>event.preventDefault());
    button.addEventListener('click',event=>{if(held||blocked){event.preventDefault();held=false;blocked=false;return;}copyDay();});
    button.addEventListener('keydown',event=>{
      if(event.key==='Enter'&&event.shiftKey){event.preventDefault();if(!event.repeat)copyWeek();}
    });
  }
  return{weeklyReportJson,bindCopyButton};
});
