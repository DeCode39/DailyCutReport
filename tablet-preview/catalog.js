(function(root,factory){const value=factory();if(typeof module==='object'&&module.exports)module.exports=value;else root.DailyCutCatalog=value;})(this,function(){
  const raw=[
    ['CUSTOM-BASEU-NORMAL-300ML',null,'Base&U Protein Milk - Normal Version','Base&U','1 bottle (300 ml)',232.5,22.2,168,25.8,4.5,11.4,0,2.7],
    ['4711089912108','4711089912108','Base&U Protein Milk - Lower Calorie Version','Base&U','1 bottle (300 ml)',148.8,22.2,273,9.6,2.4,4.5,0,1.8],
    ['CUSTOM-FRIED-CHICKEN-BREAST-THIN-COATED-PIECE',null,'Thin-Coated Fried Chicken Breast Piece','Custom Estimate','1 piece',58,6.8,85,2,2.5,0,0,.5],
    ['CUSTOM-FRIED-CHICKEN-THIGH-THIN-COATED-PIECE',null,'Thin-Coated Fried Chicken Thigh Piece','Custom Estimate','1 piece',85,6.5,95,2.2,5.3,0,0,1.3],
    ['CUSTOM-FRIED-CHICKEN-SHOP-FRIES-SMALL-BASKET',null,'Fried Chicken Shop Fries - Small Basket','Custom Estimate','1 small basket',230,3,200,30,11,.5,3,1.5],
    ['MCD-TW-SPICY-CHICKEN-FILET-BURGER',null,'Spicy Chicken Filet Burger',"McDonald's Taiwan",'1 burger',536.52,23,977.1,49,28,6.4,0,5.8],
    ['MCD-TW-CHICKEN-MCNUGGETS-4PC',null,'Chicken McNuggets - 4 pc',"McDonald's Taiwan",'1 serving (4 pieces)',178.2,11,286.5,8.8,11,0,0,2.8],
    ['MCD-TW-CHICKEN-MCNUGGETS-6PC',null,'Chicken McNuggets - 6 pc',"McDonald's Taiwan",'1 serving (6 pieces)',266.42,16,429.7,13,17,0,0,4.2],
    ['MCD-TW-CHICKEN-MCNUGGETS-10PC',null,'Chicken McNuggets - 10 pc',"McDonald's Taiwan",'1 serving (10 pieces)',444,26,716.2,22,28,0,0,6.9],
    ['MCD-TW-FRIES-SMALL',null,'French Fries - Small',"McDonald's Taiwan",'1 small fries',240.94,3.7,171.1,31,11,0,0,1.2],
    ['MCD-TW-FRIES-MEDIUM',null,'French Fries - Medium',"McDonald's Taiwan",'1 medium fries',345.97,5.3,245.7,45,16,0,0,1.7],
    ['MCD-TW-FRIES-LARGE',null,'French Fries - Large',"McDonald's Taiwan",'1 large fries',472.57,7.4,344.5,63,23,0,0,2.4],
    ['MCD-TW-SPRITE-MEDIUM',null,'Sprite - Medium',"McDonald's Taiwan",'1 medium drink',170.8,0,54.6,43,0,43,0,0],
    ['MCD-TW-COKE-MEDIUM',null,'Coca-Cola - Medium',"McDonald's Taiwan",'1 medium drink',210.3,0,9.9,53,0,53,0,0],
    ['MCD-TW-COKE-ZERO-MEDIUM',null,'Coca-Cola Zero - Medium',"McDonald's Taiwan",'1 medium drink',0,0,52,0,0,0,0,0],
    ['MCD-TW-SWEET-SOUR-SAUCE',null,'Sweet and Sour Sauce',"McDonald's Taiwan",'1 sauce cup',44,0,157,11,0,9.8,0,0],
    ['MCD-TW-BBQ-SAUCE-ESTIMATE',null,'BBQ Sauce',"McDonald's Taiwan",'1 sauce cup',45,0,200,11,0,9,0,0]
  ];
  return raw.map(x=>({productId:x[0],barcode:x[1],name:x[2],brand:x[3],servingLabel:x[4],calories:x[5],protein:x[6],sodium:x[7],carbs:x[8],fat:x[9],sugar:x[10],fiber:x[11],saturated:x[12]}));
});
