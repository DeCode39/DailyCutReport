const test=require('node:test'),assert=require('node:assert/strict'),M=require('./model.js'),C=require('./cart.js');
const day='2026-10-04',today='2026-10-28';
const food={productId:'food',name:'Food',quantityMode:'SERVING_AND_WEIGHT',measurePerServing:40,purchaseUnitServings:1,calories:100};
function setup(){const state=M.empty(day);state.products.food=structuredClone(food);return state;}
function cart(){return{date:day,items:{food:{product:food,quantity:'2',enteredUnit:'GRAMS',enteredAmount:80}},label:'Shop',paid:'12',ignoreBudget:true};}

test('copy has an explicit destination independent of selected historical date and no copied payment',()=>{
  const state=setup(),log=M.addLog(state,day,food,2,0,false,null,null,'GRAMS',80);
  const result=C.copy(state,C.empty(),[log],today,'KEEP',today);
  assert.equal(result.date,today);assert.equal(result.items.food.quantity,'2');assert.equal(result.paid,'');
  assert.equal(state.logs.length,1);assert.equal(log.date,day);
});
test('retarget keeps checkout inputs and rejects future or malformed dates',()=>{
  assert.deepEqual(C.retarget(cart(),today,today),{...cart(),date:today});
  assert.throws(()=>C.retarget(cart(),'2026-10-29',today));assert.throws(()=>C.retarget(cart(),'2026-02-30',today));
});
test('copy conflicts only replace the old cart explicitly and list skipped foods',()=>{
  const state=setup(),logs=[{productId:'food',name:'Food',quantity:1,enteredUnit:'SERVINGS',enteredAmount:1},
    {productId:null,name:'Deleted food'},{productId:'food',name:'Old volume',enteredUnit:'MILLILITERS',enteredAmount:80}];
  const kept=C.copy(state,cart(),logs,today,'KEEP',today),replaced=C.copy(state,cart(),logs,today,'REPLACE',today);
  assert.equal(kept.date,day);assert.equal(kept.paid,'12');assert.equal(kept.items.food.quantity,'3');
  assert.equal(replaced.date,today);assert.equal(replaced.paid,'');assert.match(replaced.summary,/Deleted food: deleted/);assert.match(replaced.summary,/Old volume: incompatible/);
});
test('checkout and undo are immutable, exact, one-shot, and cannot overwrite another cart',()=>{
  const state=setup();M.addLog(state,day,food,1);
  const before=structuredClone(state),result=C.checkout(state,cart(),today);
  assert.deepEqual(state,before);assert.equal(result.state.logs.length,2);assert.equal(result.state.logs[1].actualPaidTotalMicros,12000000);
  const restored=C.undo(result.state,result.cart,result.receipt);
  assert.equal(restored.state.logs.length,1);assert.deepEqual(restored.cart,cart());
  assert.throws(()=>C.undo(restored.state,C.empty(),result.receipt));assert.throws(()=>C.undo(result.state,cart(),result.receipt));
  result.state.logs[1].quantity=5;assert.throws(()=>C.undo(result.state,C.empty(),result.receipt));
});
test('missing products invalid amounts and changed conversion reject checkout without partial writes',()=>{
  const state=setup(),before=structuredClone(state);
  assert.throws(()=>C.checkout(state,{...cart(),date:'2026-10-29'},today));
  const invalid=cart();invalid.items.food.enteredAmount=0;assert.throws(()=>C.checkout(state,invalid,today));
  state.products.food.measurePerServing=20;assert.throws(()=>C.checkout(state,cart(),today));
  delete state.products.food;assert.throws(()=>C.checkout(state,cart(),today));assert.deepEqual(state.logs,before.logs);
});
test('logs and recoverable cart use one atomic browser storage document',()=>{
  const state=setup(),result=C.checkout(state,cart(),today),values=new Map();
  const storage={setItem:(k,v)=>values.set(k,v),getItem:k=>values.get(k)||null};
  C.save(storage,result.state,result.cart);
  const saved=JSON.parse(values.get(M.KEY));assert.equal(saved.logs.length,1);
  assert.equal(Object.keys(C.restore(saved,C.encode(cart()),today).items).length,0);
  const original=structuredClone(state);
  assert.throws(()=>C.save({setItem(){throw Error('Quota');}},result.state,result.cart));assert.deepEqual(state,original);
});
test('corrected conversion preserves physical quantity when merging and rejects incompatible units',()=>{
  const corrected={...food,measurePerServing:20};
  const result=C.merge(cart(),[{product:corrected,quantity:1,enteredUnit:'SERVINGS'}],day,'KEEP',today);
  assert.equal(result.items.food.quantity,'5');assert.equal(result.items.food.enteredAmount,100);
  assert.throws(()=>C.merge(cart(),[{product:{...corrected,quantityMode:'VOLUME_ONLY'},quantity:1,enteredUnit:'MILLILITERS'}],day,'KEEP',today));
});
test('restart rebases compatible physical quantities but never infers density',()=>{
  const state=setup(),saved=C.encode(cart());state.products.food.measurePerServing=20;
  assert.equal(C.restore(state,saved,today).items.food.quantity,'4');
  state.products.food.quantityMode='VOLUME_ONLY';
  const restored=C.restore(state,saved,today);assert.equal(restored.items.food.quantity,'');
  assert.match(restored.summary,/previous entry 80 GRAMS/);assert.throws(()=>C.checkout(state,restored,today));
});
