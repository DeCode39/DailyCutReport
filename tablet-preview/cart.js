(function(root, factory) {
  const model = typeof module === 'object' && module.exports ? require('./model.js') : root.DailyCutModel;
  const api = factory(model);
  if (typeof module === 'object' && module.exports) module.exports = api; else root.DailyCutCart = api;
})(this, function(M) {
  const clone = value => JSON.parse(JSON.stringify(value));
  const empty = () => ({date:null, items:{}, label:'', paid:'', ignoreBudget:false});
  function validDate(date, today=M.localDate(new Date())) {
    return typeof date === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(date) && date <= today &&
      M.localDate(new Date(date+'T12:00:00')) === date;
  }
  function encode(cart) {
    return {...cart, items:Object.values(cart.items).map(({product,...item}) => ({...item,productId:product.productId}))};
  }
  function restore(state, legacy=null, today=M.localDate(new Date())) {
    const saved = Object.hasOwn(state,'cartDraft') ? state.cartDraft : legacy;
    if (!saved || !Array.isArray(saved.items)) return empty();
    const items={};
    const notes=[];
    for (const item of saved.items) {
      const product=state.products[item.productId];
      if (!product) continue;
      const spec=M.quantitySpec(product),unit=item.enteredUnit||'SERVINGS';
      const supported=unit==='SERVINGS'?spec.servingsAvailable:unit===spec.unit&&spec.measureAvailable;
      let input={...item,product};
      if (!supported) {
        notes.push(`${product.name}: quantity settings changed; previous entry ${item.enteredAmount} ${unit}. Re-enter the amount.`);
        input={...input,quantity:'',enteredAmount:null,enteredUnit:spec.servingsAvailable?'SERVINGS':spec.unit};
      } else if (unit!=='SERVINGS' && M.parseNumber(item.enteredAmount)>0) {
        input.quantity=String(M.convertQuantity(product,item.enteredAmount,unit));
      }
      items[item.productId]=input;
    }
    return {...empty(), ...saved, items, summary:[saved.summary,...notes].filter(Boolean).join('\n'), date:Object.keys(items).length ? (validDate(saved.date,today)?saved.date:today) : null};
  }
  function retarget(cart,date,today=M.localDate(new Date())) {
    if (!validDate(date,today) || !Object.keys(cart.items).length) throw Error('Choose a date no later than today.');
    return {...clone(cart),date};
  }
  function merge(cart, additions, date, resolution='KEEP', today=M.localDate(new Date())) {
    if (!validDate(date,today)) throw Error('Choose a date no later than today.');
    const result=resolution==='REPLACE'?empty():clone(cart);
    result.date=result.date||date;
    for (const addition of additions) {
      const id=addition.product.productId, previous=result.items[id];
      let quantity=M.number(addition.quantity),unit=addition.enteredUnit;
      if (previous) {
        unit=previous.enteredUnit;
        const old=M.convertQuantity(addition.product,previous.enteredAmount,unit);
        if (!(old>0)) throw Error(`Review the cart quantity for ${addition.product.name}.`);
        quantity+=old;
      }
      const spec=M.quantitySpec(addition.product);
      result.items[id]={product:addition.product,quantity:String(quantity),enteredUnit:unit,
        enteredAmount:unit==='SERVINGS'?quantity:quantity*spec.measure};
    }
    return result;
  }
  function copy(state,cart,logs,date,resolution='KEEP',today=M.localDate(new Date())) {
    const additions=[],skipped=[];
    for (const log of logs) {
      const product=state.products[log.productId],unit=log.enteredUnit||'SERVINGS';
      if (!product) {skipped.push(`${log.name||'Food'}: deleted or detached`);continue;}
      let quantity;
      try {quantity=M.convertQuantity(product,log.enteredAmount??log.quantity,unit);} catch {quantity=null;}
      if (!(quantity>0)) {skipped.push(`${log.name||product.name}: incompatible quantity unit`);continue;}
      additions.push({product,quantity,enteredUnit:unit});
    }
    if (!additions.length) throw Error('No foods available to copy. '+skipped.join('; '));
    const result=merge(cart,additions,date,resolution,today);
    result.summary=`Copied ${additions.length} of ${logs.length} entries. Old actual-paid totals were not copied.`+
      (skipped.length?' Skipped: '+skipped.join('; '):'');
    return result;
  }
  function checkout(state,cart,today=M.localDate(new Date())) {
    if (!validDate(cart.date,today)) throw Error('Choose a date no later than today.');
    const items=Object.values(cart.items).map(item => {
      const product=state.products[item.product.productId];
      if (!product) throw Error('A selected food was deleted. Review the cart.');
      const quantity=M.number(item.quantity),converted=M.convertQuantity(product,item.enteredAmount,item.enteredUnit);
      if (!(quantity>0) || !converted || Math.abs(quantity-converted)>Math.max(1,quantity)*1e-8) throw Error('Check quantity and units for '+product.name);
      return {...item,product,quantity};
    });
    if (!items.length) throw Error('Choose at least one food.');
    const paid=String(cart.paid).trim()===''?null:Math.round(M.number(cart.paid)*1e6);
    if (paid!=null && (!Number.isSafeInteger(paid)||paid<0)) throw Error('Enter a valid checkout total.');
    const next=clone(state);
    const logs=items.length===1?[M.addLog(next,cart.date,items[0].product,items[0].quantity,paid,cart.ignoreBudget,
      null,null,items[0].enteredUnit,items[0].enteredAmount)]:M.addBulkPurchase(next,cart.date,cart.label,items,paid,cart.ignoreBudget);
    return {state:next,cart:empty(),receipt:{draft:clone(cart),logs:clone(logs)}};
  }
  function undo(state,cart,receipt) {
    if (Object.keys(cart.items).length) throw Error('Keep or finish the new cart before undoing checkout.');
    if (!receipt?.logs?.length || !receipt.logs.every(log =>
      JSON.stringify(state.logs.find(current=>current.id===log.id))===JSON.stringify(log))) throw Error('Checkout entries changed; review them in Today instead.');
    const next=clone(state),ids=new Set(receipt.logs.map(log=>log.id));
    next.logs=next.logs.filter(log=>!ids.has(log.id));
    return {state:next,cart:clone(receipt.draft)};
  }
  // One localStorage write contains both logs and cart; backups deliberately omit cartDraft.
  function save(storage,state,cart) {const next={...state,cartDraft:encode(cart)};M.save(storage,next);return next;}
  return {empty,encode,restore,retarget,merge,copy,checkout,undo,save,validDate};
});
