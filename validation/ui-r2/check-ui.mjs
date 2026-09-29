import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {resolve} from 'node:path';
import vm from 'node:vm';

const root=resolve(import.meta.dirname,'../..');
const html=readFileSync(resolve(root,'server/src/main/resources/static/tree-1.9.3.html'),'utf8');
const js=readFileSync(resolve(root,'server/src/main/resources/static/tree-1.9.3.js'),'utf8');
const result=JSON.parse(readFileSync(resolve(root,'validation/objectives-1.9.3/supplied-objectives-report.json'),'utf8'));
result.application={buildId:'objectives-20260928-ui-r2'};

for(const removed of ['class="intro"','class="panel resultHead"','class="metrics"','class="panel tablePanel"','id="scenarioNote"','Офлайн · план и глубина']){
 assert.equal(html.includes(removed),false,`Removed UI is still visible: ${removed}`);
}
assert.match(html,/<header class="appHeader">/);
assert.match(html,/<section id="variantSection"[^>]*hidden>/);
assert.match(html,/<div id="downloads" class="mapDownloads" hidden>/);
assert.ok(html.indexOf('id="progress"')<html.indexOf('id="jobStatus"'));
assert.ok(html.indexOf('id="mapHint"')<html.indexOf('id="downloads"'));
assert.ok(html.indexOf('id="downloads"')<html.indexOf('id="depthPanel"'));

function extract(start,end){
 const from=js.indexOf(start),to=js.indexOf(end,from+start.length);
 assert.ok(from>=0&&to>from,`Function not found: ${start}`);
 return js.slice(from,to);
}
const dom=new Map();
const variantsElement={
 innerHTML:'',
 replaceChildren(){this.innerHTML='';this.cardSource=null;},
 querySelectorAll(selector){
  assert.equal(selector,'.variantCard');
  if(this.cardSource!==this.innerHTML){
   this.cardSource=this.innerHTML;
   this.cachedCards=[...this.innerHTML.matchAll(/data-variant="(\d+)"/g)].map(match=>({dataset:{variant:match[1]},onclick:null,
    classList:{toggle(){}},setAttribute(){}}));
  }
  return this.cachedCards;
 }
};
dom.set('variants',variantsElement);
const $=id=>{
 if(!dom.has(id))dom.set(id,{hidden:true,textContent:'',innerHTML:'',href:'',open:false});
 return dom.get(id);
};
const number=n=>new Intl.NumberFormat('ru-RU',{maximumFractionDigits:1}).format(n);
const money=n=>new Intl.NumberFormat('ru-RU',{minimumFractionDigits:2,maximumFractionDigits:2}).format(n/1e6)+' млн ₽';
const scoreText=n=>new Intl.NumberFormat('ru-RU',{minimumFractionDigits:3,maximumFractionDigits:6}).format(n);
const escapeHtml=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
let failView=false;
const ctx=vm.createContext({$,number,money,scoreText,escapeHtml,reportData:result,datasetReady:true,job:'example-job',
 EXPECTED_BUILD:'objectives-20260928-ui-r2',api:'/api/v1',viewRevision:0,selected:-1,outputFeatures:[],
 renderTreeProgress(){},renderLearningProgress(){},renderCostChecks(){},showDepth(){},fit(){},draw(){},error(){},
 async request(url){
  assert.match(url,/\/jobs\/example-job\/view\?variant=v[1-3]/);
  if(failView)throw new Error('view is unavailable');
  return {features:[{id:url}]};
 }});
vm.runInContext(extract('function variantCard(v,i){','async function selectVariant(i){')+
 extract('async function selectVariant(i){','const precise=n=>')+
 extract('async function showResults(){','function renderLearningProgress(report){'),ctx);

await vm.runInContext('showResults()',ctx);
assert.equal($('variantSection').hidden,false);
assert.equal($('downloads').hidden,false);
assert.equal($('variantEmpty').hidden,true);
assert.equal((variantsElement.innerHTML.match(/class="variantCard /g)||[]).length,3);
assert.equal(ctx.selected,0);
assert.match($('jobStatus').textContent,/Расчёт завершён · 266,6 с/);
assert.match(variantsElement.innerHTML,/409,46 млн ₽/);
assert.match(variantsElement.innerHTML,/391,42 млн ₽/);
assert.match(variantsElement.innerHTML,/Меньше земляных работ/);
assert.match(variantsElement.innerHTML,/Проще прокладка/);
const cards=variantsElement.querySelectorAll('.variantCard');
assert.equal(typeof cards[2].onclick,'function');
await cards[2].onclick();
assert.equal(ctx.selected,2);
assert.match(ctx.outputFeatures[0].id,/variant=v3/);

failView=true;
await assert.rejects(vm.runInContext('selectVariant(1)',ctx),/view is unavailable/);
assert.equal(ctx.selected,2,'A failed route switch keeps the previously selected route');
assert.match(ctx.outputFeatures[0].id,/variant=v3/);
failView=false;

$('variantSection').hidden=true;$('downloads').hidden=true;
failView=true;
await assert.rejects(vm.runInContext('showResults()',ctx),/view is unavailable/);
assert.equal($('variantSection').hidden,true,'Cards stay hidden when the calculated GeoJSON view fails');
assert.equal($('downloads').hidden,true);
failView=false;

$('variantSection').hidden=true;$('downloads').hidden=true;ctx.datasetReady=false;
await vm.runInContext('showResults()',ctx);
assert.equal($('variantSection').hidden,true,'Cards require a successfully loaded GeoJSON');
assert.equal($('downloads').hidden,true);

ctx.datasetReady=true;ctx.reportData={...result,variants:[]};
await vm.runInContext('showResults()',ctx);
assert.equal($('variantSection').hidden,true,'No complete route does not create empty cards');
assert.equal($('variantEmpty').hidden,false);
assert.equal(variantsElement.innerHTML,'');

ctx.reportData={...result,variants:result.variants.map((v,i)=>i===1?{...v,variant_name:'<img src=x onerror=alert(1)>'}:v)};
const escaped=vm.runInContext('variantCard(reportData.variants[1],1)',ctx);
assert.ok(escaped.includes('&lt;img'));
assert.ok(!escaped.includes('<img'));
console.log('UI r2: layout, route cards, loaded-GeoJSON gating, click and escaping passed');
