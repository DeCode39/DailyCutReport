const test=require('node:test'),assert=require('node:assert/strict'),M=require('./model.js');
test('grouped numeric input is strict and locale-aware',()=>{
  assert.equal(M.parseNumber('1,140'),1140);assert.equal(M.parseNumber('1,140.25','zh-TW'),1140.25);
  assert.equal(M.parseNumber('1.140,25','de-DE'),1140.25);assert.equal(M.parseNumber('1\u202f140,25','fr-FR'),1140.25);
  for(const text of ['1,14','1,14,0','1.2.3','NaN','12 kcal','1.'])assert.equal(M.parseNumber(text),null);
  assert.throws(()=>M.number('1,14'));assert.equal(M.parseNumber('0.123456789'),.123456789);
});
test('catalog deletion keeps all historical snapshot and cost fields and prevents reseeding',()=>{
  const state=M.seed(M.empty('2026-10-04')),product=Object.values(state.products)[0];
  const log=M.addLog(state,state.selectedDate,product,2,0,true,'order','Order');
  const before=structuredClone(log),usage=M.deleteProduct(state,product.productId);
  assert.deepEqual(usage,{entries:1,dates:1});assert.deepEqual(state.logs[0],{...before,productId:null});
  M.seed(state);assert.equal(state.products[product.productId],undefined);
  const restored=JSON.parse(JSON.stringify(state));M.seed(restored);assert.equal(restored.products[product.productId],undefined);
});
test('nutrition verification separates warnings from hard errors and never mutates',()=>{
  const product={name:'Food',calories:100,carbs:4,sugar:30,fat:2,saturated:9};
  const before=structuredClone(product),review=M.nutritionReview(product);
  assert.equal(review.errors.length,0);assert.ok(review.warnings.length>=2);assert.deepEqual(product,before);
  assert.throws(()=>M.upsertProduct(M.empty('2026-10-04'),{name:'Food',calories:NaN}));
  assert.ok(M.nutritionReview({name:'Food',quantityMode:'WEIGHT_ONLY'}).errors.length);
});
