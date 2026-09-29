'use strict';
const $=id=>document.getElementById(id), api='/api/v1', number=n=>new Intl.NumberFormat('ru-RU',{maximumFractionDigits:1}).format(n), money=n=>new Intl.NumberFormat('ru-RU',{minimumFractionDigits:2,maximumFractionDigits:2}).format(n/1e6)+' млн ₽';
let dataset=null,datasetReady=false,job=null,reportData=null,inputFeatures=[],outputFeatures=[],selected=0,pollToken=0,viewRevision=0;
const escapeHtml=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
function error(message){$('error').textContent=message;$('error').hidden=false;}
async function request(url,options={}){const r=await fetch(api+url,{...options,cache:'no-store'});if(!r.ok){let body;try{body=await r.json();}catch{body={error:r.statusText};}throw new Error(body.error||body.message||'Ошибка '+r.status);}return r.json();}
// Fixed UI defaults: practical uploads use automatic preparation, 70/30 ranking and the maximum grid step.
const EXPECTED_BUILD='ai-contest-20260928-r1';
async function verifyServerBuild(){
 const label=$('serverBuild');
 try{
  const actual=await request('/health?ui='+encodeURIComponent(EXPECTED_BUILD));
  if(actual.buildId!==EXPECTED_BUILD)throw new Error('Страница AI 1.9.4 подключена к серверу '+(actual.version||'неизвестной версии')+'. Запустите сборку 1.9.3 из новой папки и обновите страницу.');
  label.hidden=true;label.classList.remove('error');return actual;
 }catch(e){label.hidden=false;label.textContent=e.message;label.classList.add('error');throw e;}
}
const CALCULATION_DEFAULTS=Object.freeze({dataMode:'scenario',rankingProfile:'contest',gridM:20});
$('routingStrategy').onchange=()=>{$('rlSettings').hidden=$('routingStrategy').value!=='reinforcement';$('treeSettings').hidden=$('routingStrategy').value!=='tree';};
const pause=()=>new Promise(resolve=>setTimeout(resolve,800));
$('mode').onchange=()=>{$('depthSettings').hidden=$('mode').value!=='depth';};
$('file').onchange=()=>{if($('file').files[0])upload($('file').files[0],$('file').files[0].name);};
$('example').onclick=async()=>{try{const r=await fetch('/complete.geojson');if(!r.ok)throw new Error('Не удалось загрузить пример');await upload(await r.blob(),'complete.geojson');}catch(e){error(e.message);}};
$('depthExample').onclick=async()=>{try{const r=await fetch('/depth-demo.geojson');if(!r.ok)throw new Error('Не удалось загрузить пример');$('mode').value='depth';$('mode').onchange();$('candidates').value='3';await upload(await r.blob(),'depth-demo.geojson');}catch(e){error(e.message);}};
async function upload(file,name){
 const token=++pollToken;
 try{
  dataset=null;datasetReady=false;job=null;reportData=null;inputFeatures=[];outputFeatures=[];
  $('variantSection').hidden=true;$('variants').replaceChildren();$('variantComparison').textContent='';$('variantEmpty').hidden=true;$('downloads').hidden=true;
  $('algorithmResult').hidden=true;$('learningProgress').hidden=true;$('treeProgress').hidden=true;viewRevision++;showDepth(null);$('featureDetails').hidden=true;
  $('progress').hidden=true;$('diagnosticBox').hidden=true;$('costs').textContent='Появятся после расчёта';$('checks').textContent='';$('diameters').textContent='';
  $('diameterDetails').hidden=true;$('diameterDetails').open=false;$('reasons').textContent='Нет результатов';$('selection').textContent='Нажмите на участок или узел, чтобы увидеть атрибуты.';draw();
  $('error').hidden=true;$('run').disabled=true;$('example').disabled=true;$('depthExample').disabled=true;$('datasetStatus').textContent='Загрузка и потоковый импорт…';
  const value=await request('/datasets?name='+encodeURIComponent(name),{method:'POST',headers:{'Content-Type':'application/geo+json'},body:file});if(token!==pollToken)return;dataset=value.id;
  while(token===pollToken){const state=await request('/datasets/'+dataset);if(state.status==='FAILED')throw new Error(state.error);if(state.status==='READY'){
   $('datasetStatus').textContent=`${name} · ${state.feature_count} объектов · ${state.oks_count} точек подключения`;
   const findings=state.inputPreparation.diagnostics||[];$('diagnosticBox').hidden=false;
   $('diagnostics').innerHTML=findings.length?findings.map(d=>`<p><b>${escapeHtml(d.object_id)}</b> · ${escapeHtml(d.message)}</p>`).join(''):'Обязательные данные заполнены. Топология и ограничения будут проверены при расчёте.';
   $('diagnosticBox').open=findings.length>0;const view=await request('/datasets/'+dataset+'/view');if(token!==pollToken)return;inputFeatures=view.features;outputFeatures=[];if(view.truncated)$('datasetStatus').textContent+=' · на схеме первые 5000 объектов';fit();datasetReady=true;$('run').disabled=false;$('jobStatus').textContent='Готов к расчёту';return;
  }$('datasetStatus').textContent=`Импорт: ${state.feature_count||0} объектов…`;await pause();}
 }catch(e){if(token===pollToken){datasetReady=false;error(e.message);$('datasetStatus').textContent='Не удалось подготовить набор';}}finally{if(token===pollToken){$('example').disabled=false;$('depthExample').disabled=false;}}
}
$('run').onclick=async()=>{
 try{
  if(!datasetReady||!dataset)throw new Error('Сначала загрузите и подготовьте GeoJSON.');
  $('error').hidden=true;$('run').disabled=true;$('variantSection').hidden=true;$('variantEmpty').hidden=true;$('downloads').hidden=true;
  await verifyServerBuild();const options={...CALCULATION_DEFAULTS,rankingProfile:$('rankingProfile').value,routingStrategy:$('routingStrategy').value,rlRemember:$('rlRemember').checked,rlLearning:$('routingStrategy').value==='reinforcement'&&$('rlLearning').checked,treeLearning:$('routingStrategy').value==='tree'&&$('treeLearning').checked,treeLearningRounds:Number($('treeLearningRounds').value),treeGroupRepair:$('treeGroupRepair').checked,treeJunctionRepair:$('treeJunctionRepair').checked,treeGeometricSearch:$('treeGeometricSearch').checked,treeResumeFromBest:$('treeResumeFromBest').checked,treeSingleRootTrial:$('treeSingleRootTrial').checked,treeSingleRootRequired:$('treeSingleRootRequired').checked,treeBeamWidth:Number($('treeBeamWidth').value),treeExpansion:Number($('treeExpansion').value),treeRepairPasses:Number($('treeRepairPasses').value),treeRepairCandidates:Number($('treeRepairCandidates').value),rlBatchSize:Number($('rlBatchSize').value),rlLearningRate:Number($('rlLearningRate').value),rlEpisodes:Number($('rlEpisodes').value),rlTemperature:Number($('rlTemperature').value),rlSeed:Number($('rlSeed').value),candidateLimit:Number($('candidates').value),mode:$('mode').value,depthRuleProfile:$('depthRules').value,minDepthM:Number($('minDepth').value),maxDepthM:Number($('maxDepth').value),existingLoadPercent:Number($('load').value)};
  const start=await request('/datasets/'+dataset+'/jobs',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(options)});job=start.id;$('cancel').hidden=false;$('progress').hidden=false;$('progress').value=0;$('file').disabled=true;$('example').disabled=true;$('depthExample').disabled=true;
  if(reportData)$('learningScope').textContent='Новый расчёт выполняется. Ниже — показатели предыдущего завершённого запуска.';
  for(;;){const state=await request('/jobs/'+job);$('jobStatus').textContent=state.message||state.status;$('progress').value=state.progress||0;
   if(state.status==='FAILED')throw new Error(state.message);if(state.status==='CANCELLED')break;
   if(state.status==='DONE'){$('progress').value=100;reportData=state.summary;await showResults();try{await refreshExperience();}catch(e){error('Не удалось обновить сведения об опыте: '+e.message);}break;}await pause();
  }
 }catch(e){error(e.message);$('jobStatus').textContent='Расчёт не выполнен';}finally{if(reportData)$('learningScope').textContent='Показан последний завершённый расчёт. Score сравнивается на том же файле при тех же инженерных настройках; изменение весов не гарантирует улучшения маршрута.';$('run').disabled=!datasetReady;$('cancel').hidden=true;$('file').disabled=false;$('example').disabled=false;$('depthExample').disabled=false;}
};
$('cancel').onclick=async()=>{try{await request('/jobs/'+job+'/cancel',{method:'POST'});}catch(e){error(e.message);}};
const scoreText=value=>Number.isFinite(value)?new Intl.NumberFormat('ru-RU',{minimumFractionDigits:3,maximumFractionDigits:6}).format(value):'—';
async function showResults(){
 if(reportData.application?.buildId!==EXPECTED_BUILD)throw new Error('Получен отчёт другой сборки. Обновите страницу AI 1.9.4 и запустите новый расчёт.');
 renderTreeProgress(reportData);const rl=reportData.search?.reinforcement;$('algorithmResult').hidden=false;$('algorithmResult').textContent=rl?`RL · модель ${rl.modelId} · эпизодов ${rl.completedEpisodes}/${rl.requestedEpisodes} · seed ${rl.seed} · отклонено подключений ${rl.rejectedActions}`:reportData.search?.treeSearch?(reportData.options.treeSingleRootRequired?'Единое дерево · общие стволы · 1.9.4 AI':'Поиск деревьев · 1.9.4 AI'):'Классический поиск';
 $('geojson').href=api+'/jobs/'+job+'/result';$('report').href=api+'/jobs/'+job+'/report';
 $('jobStatus').textContent='Расчёт завершён'+(Number.isFinite(reportData.elapsedMs)?` · ${number(reportData.elapsedMs/1000)} с`:'');
 const memory=reportData.search?.experience;
 if(rl?.trainingDuringCalculation) $('algorithmResult').textContent+=` · обновлений весов: ${rl.updatesThisRun} (всего ${rl.totalUpdates}) · эпизодов в незавершённом пакете: ${rl.pendingBatchEpisodes}/${rl.batchSize}`;
 if(memory?.enabled) $('algorithmResult').textContent+=` · ${memory.resumedTraining?(rl?.trainingDuringCalculation?'обучение продолжено':'загружен накопленный опыт'):'новый опыт участка'} · восстановлено сетей: ${reportData.search.restoredNetworks||0}`;
 else $('algorithmResult').textContent+=' · сохранение опыта выключено';
 if(memory?.notes?.length) $('algorithmResult').textContent+=' · '+memory.notes.join('; ');
 renderLearningProgress(reportData);
 if(!reportData.variants.length){
  outputFeatures=[];viewRevision++;showDepth(null);$('featureDetails').hidden=true;
  $('variantSection').hidden=true;$('variants').replaceChildren();$('variantEmpty').hidden=false;$('downloads').hidden=false;
  $('costs').textContent='Стоимость готового варианта не определена';$('checks').textContent='';$('diameters').textContent='';$('diameterDetails').hidden=true;
  $('reasons').innerHTML=Object.entries(reportData.diagnostics||{}).map(([id,note])=>`<p><b>${escapeHtml(id)}</b> · ${escapeHtml(note)}</p>`).join('')||'Не найдено допустимого полного решения. Подробности поиска — в отчёте.';
  draw();return;
 }
 $('variants').innerHTML=reportData.variants.map(variantCard).join('');
 $('variantComparison').textContent=reportData.variants.length<3 ? (reportData.search?.variantSelection?.installation_indexNotFound||reportData.search?.variantSelection?.earthwork_indexNotFound||'Для этой территории не найдены три заметно разные трассы с улучшением назначенного показателя.') : reportData.search?.variantSelection?.tradeoffNote||'Общая оценка сопоставима для всех маршрутов: чем меньше score, тем лучше.';
 for(const card of $('variants').querySelectorAll('.variantCard'))card.onclick=()=>selectVariant(Number(card.dataset.variant)).catch(e=>error(e.message));
 await selectVariant(0);
 if(datasetReady){$('variantSection').hidden=false;$('downloads').hidden=false;}
}
function renderLearningProgress(report){
 const rl=report.search?.reinforcement,q=report.search?.qualityProgress,memory=report.search?.experience;
 $('learningProgress').hidden=!rl;
 if(!rl)return;
 const attempts=report.search?.attempts||[];
 $('learningScope').textContent='Завершённый запуск. Score можно сравнивать на том же файле при тех же инженерных настройках. Изменение весов само по себе не доказывает улучшение маршрута.';
 let state;
 if(!rl.trainingDuringCalculation)state='Обучение выключено';
 else if(rl.weightsChanged===true)state='Веса изменились';
 else if(rl.updatesThisRun===0)state='Пакет для обновления ещё не набран';
 else if(rl.weightsChanged===false)state='Обновления выполнены, веса не изменились';
 else state='Нет сведений об изменении весов';
 $('learningState').textContent=state;
 $('learningUpdateCount').textContent=rl.trainingDuringCalculation?`Обновлений: ${rl.updatesThisRun??'—'} за запуск · ${rl.totalUpdates??'—'} всего`:'Обновлений: 0 за запуск';
 $('learningWeightChange').textContent=Number.isFinite(rl.weightDeltaL2)
  ? `Изменилось ${rl.changedWeightCount} из ${rl.weightCount} параметров; величина изменения L2: ${rl.weightDeltaL2.toExponential(3)}.`
  : 'Для старого отчёта величина изменения весов неизвестна.';
 $('learningEpisodes').textContent=`Завершено попыток: ${rl.completedEpisodes}/${rl.requestedEpisodes}. Обучающих: ${rl.trainingEpisodesThisRun??0} за запуск${Number.isFinite(rl.totalTrainingEpisodes)?`, ${rl.totalTrainingEpisodes} всего`:''}. `+(rl.trainingDuringCalculation?`В незавершённом пакете: ${rl.pendingBatchEpisodes}/${rl.batchSize}.`:'');
 $('learningMemory').textContent=memory?.enabled
  ? (memory.resumedTraining?'Сохранённая модель загружена.':'Сохранённой модели для этого файла и настроек не было: использована начальная или общая модель.')+` Восстановлено полных сетей: ${report.search.restoredNetworks||0}.`
  : 'Сохранение опыта выключено: следующий запуск не продолжит обучение этого запуска.';
 $('learningBaselineLabel').textContent=q?.baselineSource==='saved_network'?'Лучший score до запуска':'Score первой полной попытки';
 $('learningBefore').textContent=scoreText(q?.baselineScore);
 $('learningAfter').textContent=scoreText(q?.bestAfterRun);
 $('learningFresh').textContent=scoreText(q?.bestFreshScore);
 const source=report.variants?.[0]?.source==='saved_best_revalidated'?'Выдан сохранённый лучший вариант.':'Выдан вариант текущего расчёта.';
 let quality;
 if(!q)quality='В этом отчёте нет сравнения качества. Новый расчёт добавит его.';
 else if(!Number.isFinite(q.bestAfterRun))quality='Полный маршрут не найден.';
 else if(!Number.isFinite(q.baselineScore))quality='Нет исходной оценки для сравнения. '+source;
 else if(q.improved)quality=`Лучший маршрут улучшился${Number.isFinite(q.improvementPercent)?` на ${scoreText(q.improvementPercent)}%`:''}. `+source;
 else quality='Лучший маршрут не улучшился. '+source;
 $('learningQuality').textContent=quality;
 $('learningQuality').classList.toggle('improved',q?.improved===true);
 $('learningQualityDetails').textContent=q?`Полных сетей в новых попытках: ${q.completeEpisodes}/${q.completedEpisodes}. `+(Number.isFinite(q.episodesSinceLastImprovement)?`Эпизодов после последнего улучшения: ${q.episodesSinceLastImprovement}. `:'')+'Сохранённые варианты не входят в число новых попыток.':'';
 $('learningChart').innerHTML=learningChart(q,attempts);
 $('learningAttempts').innerHTML=attempts.slice(-20).map(a=>`<tr><td>${a.attempt}</td><td>${a.connected}/${a.required}</td><td>${scoreText(a.scoreAfterSmoothing)}</td><td>${scoreText(a.bestScoreSoFar)}</td><td>${Number.isFinite(a.trainingUpdates)?a.trainingUpdates:'—'}</td></tr>`).join('');
 $('learningAttemptNote').textContent=`Последние ${Math.min(20,attempts.length)} из ${attempts.length} попыток. Частичные сети не получают score готового маршрута. Полная история — в скачиваемом отчёте.`;
}
function learningChart(quality,attempts){
 const points=[];
 if(Number.isFinite(quality?.bestBeforeRun))points.push([0,quality.bestBeforeRun]);
 for(const a of attempts)if(Number.isFinite(a.bestScoreSoFar))points.push([a.attempt,a.bestScoreSoFar]);
 if(!points.length)return '<p class="status">График появится после первой полной сети.</p>';
 const values=points.map(p=>p[1]),low=Math.min(...values),high=Math.max(...values);
 const pad=Math.max((high-low)*.12,Math.abs(high)*.002,1e-6),min=Math.max(0,low-pad),max=high+pad;
 const end=Math.max(1,attempts.length),x=n=>112+530*n/end,y=n=>154-126*(n-min)/(max-min);
 const path=points.map(([episode,score],i)=>`${i?'L':'M'}${x(episode).toFixed(2)},${y(score).toFixed(2)}`).join(' ');
 const last=points[points.length-1];
 return `<svg viewBox="0 0 680 204" role="img" aria-labelledby="learningChartTitle"><title id="learningChartTitle">Лучший score по завершённым эпизодам: ${scoreText(points[0][1])} → ${scoreText(last[1])}. Меньше — лучше.</title><text x="12" y="16">Score ↓</text><line x1="112" y1="28" x2="642" y2="28" class="chartGrid"/><line x1="112" y1="154" x2="642" y2="154" class="chartGrid"/><text x="102" y="33" text-anchor="end">${scoreText(max)}</text><text x="102" y="159" text-anchor="end">${scoreText(min)}</text><path d="${path}" class="learningLine"/><circle cx="${x(last[0])}" cy="${y(last[1])}" r="3" class="learningDot"/><text x="112" y="177">0</text><text x="642" y="177" text-anchor="end">${end}</text><text x="377" y="199" text-anchor="middle">Завершённые эпизоды текущего запуска</text></svg>`;
}
function variantCard(v,i){
 const base=reportData.variants[0];
 const type=['balanced','earthworks','installation'][i]||'alternative';
 const role=['Лучший по общей оценке','Меньше земляных работ','Проще в прокладке'][i]||'Альтернативная трасса';
 const value=Number.isFinite(i===1?v.earthwork_index:i===2?v.installation_index:v.score)
  ? i===0?scoreText(v.score):number(i===1?v.earthwork_index:v.installation_index):'—';
 const baseline=i===1?base.earthwork_index:i===2?base.installation_index:null;
 const current=i===1?v.earthwork_index:i===2?v.installation_index:null;
 const gain=Number.isFinite(baseline)&&baseline>0&&Number.isFinite(current)&&current<baseline
  ? `${number((baseline-current)/baseline*100)}% меньше, чем в варианте 1` : 'Меньше — проще на тех же исходных данных';
 const detail=n=>Number.isFinite(n)?number(n):'—';
 const price=Number.isFinite(v.calculated_cost)?money(v.calculated_cost-(v.unconnected_penalty||0)):'—';
 const focus=i===0?'Оценка по выбранному критерию · S':i===1?'Индекс земляных работ':'Индекс прокладки';
 const explanation=i===0?'Наименьший индекс среди проверенных сетей':gain;
 const connections=Number.isFinite(v.required_connection_count)?`${detail(v.connected_connection_count)} из ${detail(v.required_connection_count)} вводов`:'';
 const depth=Number.isFinite(v.maximum_route_depth_m)?detail(v.maximum_route_depth_m)+' м':'нет профиля';
 return `<button type="button" class="variantCard variantCard--${type}" data-variant="${i}" aria-pressed="${i===0?'true':'false'}" aria-label="Вариант ${i+1}: ${escapeHtml(v.variant_name||role)}, стоимость ${escapeHtml(price)}, score ${scoreText(v.score)}. Показать на карте">
  <span class="variantCard__top"><span class="variantCard__index">Вариант ${String(i+1).padStart(2,'0')}</span><span class="variantCard__role">${escapeHtml(role)}</span></span>
  <span class="variantCard__title">${escapeHtml(v.variant_name||'Полная трасса')}</span>
  <span class="variantCard__price"><span>Полная предварительная смета</span><strong>${escapeHtml(price)}</strong><small>Для индекса новой сети: ${escapeHtml(Number.isFinite(v.contest_cost)?money(v.contest_cost):'—')}</small>${v.facade_adapter_count?'<small>Без индивидуальных угловых узлов</small>':''}</span>
  <span class="variantCard__focus"><span>${focus}</span><strong>${value}</strong><small>${explanation}</small></span>
  <span class="variantCard__stats"><span>Новая трасса <strong>${detail(v.new_network_length)} м</strong></span><span>Повороты <strong>${detail(v.bend_count)}</strong></span><span>Макс. глубина <strong>${depth}</strong></span><span>Смены глубины <strong>${detail(v.depth_change_count)}</strong></span></span>
  <span class="variantCard__bottom"><span class="variantCard__facts">${connections?escapeHtml(connections)+'<br>':''}score ${scoreText(v.score)}</span><span class="variantCard__action">Смотреть на карте <span aria-hidden="true">↗</span></span></span>
 </button>`;
}
async function selectVariant(i){const revision=++viewRevision,viewJob=job;
 let view;try{view=await request('/jobs/'+viewJob+'/view?variant=v'+(i+1));}catch(e){if(revision!==viewRevision||viewJob!==job)return;throw e;}
 if(revision!==viewRevision||viewJob!==job)return;
 selected=i;outputFeatures=view.features;
 for(const card of $('variants').querySelectorAll('.variantCard')){const active=Number(card.dataset.variant)===i;card.classList.toggle('selected',active);card.setAttribute('aria-pressed',String(active));}
 const v=reportData.variants[i],checks=reportData.checks?.[i]||{};
 renderCostChecks(v,checks);showDepth(checks.depth);$('featureDetails').hidden=true;
 const reasons=Object.entries(reportData.diagnostics||{}).filter(([k])=>k.startsWith('v'+(i+1)+':'));$('reasons').innerHTML=reasons.length?reasons.map(([k,val])=>`<p><b>${escapeHtml(k.split(':').slice(1).join(':'))}</b> · ${escapeHtml(val)}</p>`).join(''):'Все заданные точки подключены.';
 if(view.truncated)$('mapHint').textContent+=' На схеме первые 10000 объектов; полный результат доступен в GeoJSON.';fit();
}
const precise=n=>new Intl.NumberFormat('ru-RU',{maximumFractionDigits:3}).format(n);
const rubles=new Intl.NumberFormat('ru-RU',{minimumFractionDigits:2,maximumFractionDigits:2});
const measured=n=>Number.isFinite(n)?precise(n):'—';
const withinLimit=(value,limit)=>Number.isFinite(value)&&Number.isFinite(limit)?value<=limit+1e-6:null;
function combinedCheck(values){return values.includes(false)?false:values.length&&values.every(v=>v===true)?true:null;}
function checkBadge(valid,neutral='Нет данных'){
 const state=valid===true?'passed':valid===false?'failed':'unknown';
 return `<span class="checkBadge ${state}">${valid===true?'Соблюдено':valid===false?'Нарушено':escapeHtml(neutral)}</span>`;
}
function reportTable(caption,headers,rows){
 return `<table><caption>${escapeHtml(caption)}</caption><thead><tr>${headers.map(h=>`<th scope="col">${escapeHtml(h)}</th>`).join('')}</tr></thead><tbody>${rows.join('')}</tbody></table>`;
}
function renderCostChecks(variant,checks){
 const depth=checks.depth||{},hasDepth=checks.depthChecked===true;
 const workItems=[['construction_cost','Новые участки сети'],['chamber_construction_cost','Новые камеры'],['tie_in_cost','Врезки'],['reconstruction_cost','Реконструкция сети'],['chamber_reconstruction_cost','Реконструкция камер']];
 const costRow=(key,label,value,kind='')=>`<tr data-cost="${key}" class="${kind}"><th scope="row">${escapeHtml(label)}</th><td class="amount">${Number.isFinite(value)?rubles.format(value):'—'}</td></tr>`;
 const costRows=[];
 for(const [key,label] of workItems){
  costRows.push(costRow(key,label,variant[key]));
  if(key==='construction_cost'&&hasDepth&&Number.isFinite(depth.additionalDepthCost))costRows.push(costRow('depth_included','В том числе доплата за глубину (уже включена)',depth.additionalDepthCost,'includedCost'));
 }
 const workTotal=workItems.every(([key])=>Number.isFinite(variant[key]))?workItems.reduce((sum,[key])=>sum+variant[key],0):null;
 costRows.push(costRow('works_total','Итого за работы',workTotal,'totalCost'));
 if(Number.isFinite(variant.contest_cost))costRows.push(costRow('contest_cost','Для сравнения вариантов: только новая сеть',variant.contest_cost,'includedCost'));
 if(variant.facade_adapter_count)costRows.push('<tr><th scope="row">Индивидуальные угловые узлы</th><td>Цена не задана; требуется инженерная проверка</td></tr>');
 if(variant.unconnected_penalty>0)costRows.push(costRow('unconnected_penalty','Штраф в старом расчёте',variant.unconnected_penalty),costRow('calculated_cost','Исторический итог',variant.calculated_cost,'subtotalCost'));
 $('costs').innerHTML=reportTable('Состав стоимости',['Статья','Стоимость, ₽'],costRows);

 const groups=Array.isArray(checks.diameters)?checks.diameters:null;
 const noGroups=groups?.length===0?'Нет новых участков':'Нет данных';
 const flowChecks=(groups||[]).map(g=>withinLimit(g.maximumFlowTph,g.capacityTph));
 const lengthChecks=(groups||[]).map(g=>withinLimit(g.componentLengthM,g.limitM));
 const groupNote=groups?`Групп одного диаметра: ${groups.length}. Подробности — в таблице ниже.`:'Сведения о диаметрах отсутствуют.';
 const checkRow=(label,result,note='')=>`<tr><th scope="row">${escapeHtml(label)}</th><td>${result}${note?`<small>${escapeHtml(note)}</small>`:''}</td></tr>`;
 const checkRows=[
  checkRow('Подключение всех точек',checkBadge(checks.allConnectionsConnected),Number.isFinite(variant.required_connection_count)?`${variant.connected_connection_count} из ${variant.required_connection_count}. Частичные сети не включаются в новые варианты.`:'Старый отчёт: полнота по новой политике не проверена.'),
  checkRow('Материал новых труб',`${measured(variant.new_pipe_material_length)} м`,'Суммарная длина подающей и обратной труб: две трубы на каждый метр новой трассы.'),
  checkRow('Сложность строительства',measured(variant.construction_complexity_factor),'Средний коэффициент спецпроходов и глубины. Свойства грунта во входных данных не заданы.'),
  checkRow('Условный объём траншеи',`${measured(variant.indicative_trench_volume_m3)} усл. м³`,'Единая сравнительная ширина и профиль глубины. Реальные свойства грунта не заданы.'),
  checkRow('Индекс земляных работ',measured(variant.earthwork_index),'Дополнительная глубина и специальные проходы увеличивают показатель. Меньше — проще на одинаковых данных.'),
  checkRow('Индекс прокладки',measured(variant.installation_index),'Включает повороты, изменения глубины, максимальную глубину и индивидуальные сопряжения. Меньше — проще.'),
  checkRow('Изменения глубины',`${measured(variant.depth_change_count)}; суммарно ${measured(variant.total_vertical_change_m)} м`,'Начала и смены направления уклонов на участках; в режиме только плана глубины ещё неизвестны.'),
  checkRow('Число врезок',checkBadge(checks.rootPolicyValid),checks.singleRootRequired?'Требуется одна общая врезка; все ветви принадлежат одному дереву.':'Допускается несколько врезок.'),
  checkRow('Топология сети',checkBadge(checks.topologyValid),'Результат проверки соединений и пересечений новых ветвей.'),
  checkRow('Пространственные ограничения',checkBadge(checks.spatialRulesValid),'Отступы и допустимость трассы в плане.'),
  checkRow('Проходы через здания',checkBadge(checks.buildingIntersectionsValid),'К каждому зданию допускается только конечный прямой ввод через выбранную наружную стену; транзит через здания запрещён.'),
  checkRow('Исходные точки подключения',checkBadge(checks.originalEndpointsPreserved),'Исходная точка внутри здания остаётся адресом потребителя; место пересечения стены выбирается вместе с трассой.'),
  checkRow('Разрешённые входы в здания',Array.isArray(checks.permittedBuildingEntries)?measured(checks.permittedBuildingEntries.length):'Нет данных','Разрешение индивидуально для каждой точки. Внутренний участок включён в расчёт.'),
  checkRow('Труба внутри зданий',`${measured(variant.indoor_connection_length)} м`,'Длина трассы от разрешённой стены до исходной точки. Уже включена в общую длину и стоимость.'),
  checkRow('Пропускная способность новых труб',checkBadge(combinedCheck(flowChecks),noGroups),groupNote),
  checkRow('Предельные длины по диаметрам',checkBadge(combinedCheck(lengthChecks),noGroups),groupNote),
  checkRow('Число участков в камере',checkBadge(withinLimit(checks.maximumChamberDegree,checks.chamberDegreeLimit)),`Максимум: ${measured(checks.maximumChamberDegree)}; допустимо: ${measured(checks.chamberDegreeLimit)}.`),
  checkRow('Число поворотов',measured(checks.bendCount),'Внутренние повороты новых участков.'),
  checkRow('Повороты 90° / 45°',`${measured(variant.turn_90_count)} / ${measured(variant.turn_45_count)}`,'Включая углы присоединений. Прямая без поворота предпочтительнее.'),
  checkRow('Штраф сложности маршрута',`${measured(variant.turn_penalty_m)} усл. м`,'90° — 25, 45° — 50, 135° — 75 условных метров. Влияет на выбор и оценку; в длину трубы и стоимость работ не добавляется.'),
  checkRow('Параллельность зданиям',`${measured(variant.facade_alignment_penalty_m||0)} усл. м`,'Поперечное отклонение длинных участков от осей ближайших зданий учитывается при сравнении вариантов.'),
  checkRow('Стена со стороны сети',`${measured(variant.network_facing_wall_penalty_m||0)} усл. м`,'Сравниваются доступные наружные стены относительно точки присоединения к сети. Если ближайшая непроходима, допустимый обход остаётся возможным. Штраф влияет на рейтинг, не на длину трубы и смету.'),
  checkRow('Индивидуальные сопряжения',variant.facade_adapter_count?`<span class="checkBadge unknown">${variant.facade_adapter_count} · проверить</span>`:'Нет',variant.facade_adapter_count?'Углы до 4° от типовых. Их применимость и стоимость необходимо проверить при проектировании; стоимость узлов в смету не включена.':'Нестандартных угловых узлов нет.'),
  checkRow('Расчёт глубины',`<span class="checkBadge unknown">${hasDepth?'Выполнен':checks.depthChecked===false?'Не выполнялся':'Нет данных'}</span>`,checks.depthChecked===false?'Выбран расчёт только в плане. Глубины и вертикальные зазоры не проверялись.':'')
 ];
 if(hasDepth){
  const rangeValid=combinedCheck([withinLimit(depth.allowedMinimumM,depth.minimumDepthM),withinLimit(depth.maximumDepthM,depth.allowedMaximumM)]);
  checkRows.push(
   checkRow('Диапазон глубин',checkBadge(rangeValid),`Факт: ${measured(depth.minimumDepthM)}–${measured(depth.maximumDepthM)} м; допустимо: ${measured(depth.allowedMinimumM)}–${measured(depth.allowedMaximumM)} м.`),
   checkRow('Максимальный уклон',checkBadge(withinLimit(depth.maximumSlope,depth.slopeLimit)),`Факт: ${measured(depth.maximumSlope)}; предел: ${measured(depth.slopeLimit)}.`),
   checkRow('Согласование глубин в узлах',checkBadge(depth.nodeContinuityValid),'Требуется совпадение глубин сходящихся участков в общем узле.'),
   checkRow('Площадки пересечений',checkBadge(depth.plateausValid),'Постоянная глубина по всей длине площадки пересечения.')
  );
  const crossings=Array.isArray(depth.crossings)?depth.crossings:null;
  checkRows.push(checkRow('Вертикальные зазоры',checkBadge(combinedCheck((crossings||[]).map(c=>withinLimit(c.requiredClearanceM,c.actualClearanceM))),crossings?.length===0?'Нет пересечений':'Нет данных'),crossings?.length?'Требование: зазор не меньше минимального. Значения — в блоке «Продольный разрез».':''));
 }
 $('checks').innerHTML=reportTable('Проверки выбранного варианта',['Проверка','Результат'],checkRows);
 if(checks.routeExplanations?.length)$('checks').innerHTML+='<details><summary>Почему трасса поворачивает</summary>'+reportTable('Геометрия участков',['Участок','Длина / напрямую, м','Повороты','Причины обхода'],checks.routeExplanations.map(r=>`<tr><th scope="row">${escapeHtml(r.edgeId)}</th><td>${measured(r.lengthM)} / ${measured(r.straightDistanceM)}</td><td>${r.bendCount}</td><td>${escapeHtml(r.directRouteConstraints.join('; ')||'Прямой участок')}</td></tr>`))+'</details>';

 const diameterRows=(groups||[]).map((g,index)=>{
  const valid=combinedCheck([flowChecks[index],lengthChecks[index]]);
  return `<tr><th scope="row">${index+1}</th><td>${measured(g.diameter)}${g.raisedForLength===true?'<small>Увеличен по длине</small>':''}</td><td>${measured(g.maximumFlowTph)} / ${measured(g.capacityTph)}</td><td>${measured(g.componentLengthM)} / ${measured(g.limitM)}</td><td>${checkBadge(valid)}</td></tr>`;
 });
 $('diameterDetails').hidden=!diameterRows.length;
 $('diameters').innerHTML=diameterRows.length?reportTable('Новые участки: фактические значения и пределы',['Группа','DN, мм','Расход / предел, т/ч','Длина / предел, м','Результат'],diameterRows):'';
}
const SELECTED_ROUTE_COLOR='#20a84a';
const crossingNames={gas_pipeline:'Газопровод',power_cable:'Кабель',heat_network:'Тепловая сеть',road:'Дорога',railway:'Железная дорога',water:'Водный объект'};
let activeDepth=null,selectedRouteId=null,profileByEdge=new Map(),profileWidth=0;
function defaultMapHint(){
 if(!reportData)return 'После расчёта нажмите на новый участок сети, чтобы увидеть его разрез.';
 if(profileByEdge.size)return 'Нажмите на оранжевую линию: участок станет зелёным, а его разрез появится ниже. Можно выбирать стрелками ← →, когда схема в фокусе.';
 if(reportData.checks?.[selected]?.depthChecked===false)return 'Выполнен расчёт только в плане. Для разреза выберите этап «План и глубина» и запустите расчёт.';
 return 'В этом варианте нет рассчитанных профилей новых участков.';
}
function clearProfile(redraw=true){
 selectedRouteId=null;$('depthPanel').hidden=true;$('profileEdge').value='';
 for(const id of ['profileChart','depthSummary','profileReadout','profileCaption','routeExplanation','depthCrossings'])$(id).textContent='';
 $('mapHint').textContent=defaultMapHint();if(redraw)draw();
}
function showDepth(depth){
 activeDepth=depth||null;profileByEdge=new Map((depth?.profiles||[]).map(p=>[p.edgeId,p]));
 $('profileEdge').innerHTML='<option value="" disabled>Выберите участок на схеме</option>'+[...profileByEdge.values()].map((p,i)=>`<option value="${escapeHtml(p.edgeId)}">Участок ${i+1} · DN ${measured(p.diameter)} · ${number(p.lengthM)} м</option>`).join('');
 $('profileEdge').disabled=!profileByEdge.size;clearProfile(false);
}
// Output IDs have the form tt:<variant>:<logical edge>:<section index>.
// All exported sections of one logical edge share a single calculated profile.
function routeIdOf(feature){
 const p=feature.properties||{},id=p.id,prefix='tt:v'+(selected+1)+':';
 if(p.object_type!=='heat_network'||feature.geometry?.type!=='LineString'||typeof id!=='string'||!id.startsWith(prefix))return null;
 const end=id.lastIndexOf(':');return /^\d+$/.test(id.slice(end+1))?id.slice(prefix.length,end):null;
}
function selectRoute(edgeId,scroll=false){
 selectedRouteId=edgeId;const p=profileByEdge.get(edgeId);
 $('depthPanel').hidden=!p;$('profileEdge').value=p?edgeId:'';
 const explanation=reportData.checks?.[selected]?.routeExplanations?.find(r=>r.edgeId===edgeId);
 $('routeExplanation').textContent=explanation?`Поворотов: ${explanation.bendCount}. 90°: ${measured(explanation.turn90Count)}, 45°: ${measured(explanation.turn45Count)}. Штраф поворотов: ${measured(explanation.turnPenaltyM)} усл. м. Длина ${measured(explanation.lengthM)} м, напрямую ${measured(explanation.straightDistanceM)} м. ${explanation.directRouteConstraints.join('; ')||'Прямой участок без обхода.'}`:'';
 if(p){
  drawProfile();$('mapHint').textContent='Выбран участок '+([...profileByEdge.keys()].indexOf(edgeId)+1)+'. На схеме и разрезе: А — начало, Б — конец.';
 }else{
  $('profileChart').textContent='';$('mapHint').textContent=reportData.checks?.[selected]?.depthChecked===false?'Участок выделен. Глубина не рассчитывалась: выберите этап «План и глубина» и запустите расчёт.':'Для этого участка в отчёте нет рассчитанного профиля глубины.';
 }
 draw();
 if(scroll&&p)$('depthPanel').scrollIntoView({behavior:matchMedia('(prefers-reduced-motion: reduce)').matches?'auto':'smooth',block:'nearest'});
}
$('profileEdge').onchange=()=>selectRoute($('profileEdge').value);
$('clearProfile').onclick=()=>{clearProfile();$('featureDetails').hidden=true;canvas.focus({preventScroll:true});};
function axisStep(range,target){const raw=range/target,base=10**Math.floor(Math.log10(raw));return ([1,2,5,10].find(n=>n*base>=raw)||10)*base;}
function profileDepthAt(profile,distance){
 const stations=profile.stations;
 for(let i=1;i<stations.length;i++)if(distance<=stations[i].distanceM){const a=stations[i-1],b=stations[i],span=b.distanceM-a.distanceM;return span>0?a.depthM+(b.depthM-a.depthM)*(distance-a.distanceM)/span:b.depthM;}
 return stations[stations.length-1].depthM;
}
function drawProfile(){
 const p=profileByEdge.get(selectedRouteId);if(!p)return;
 if(!Number.isFinite(p.lengthM)||p.lengthM<=0||!p.stations?.length||p.stations.some(s=>!Number.isFinite(s.distanceM)||!Number.isFinite(s.depthM))){$('profileChart').textContent='Недостаточно данных для построения разреза.';return;}
 const rows=(activeDepth.crossings||[]).filter(c=>c.edgeId===p.edgeId);
 const w=Math.max(280,$('profileChart').clientWidth),compact=w<500,height=compact?320:350,left=compact?48:62,right=24,top=54,bottom=58;
 profileWidth=$('profileChart').clientWidth;
 const deepest=Math.max(...p.stations.map(s=>s.depthM),...rows.map(c=>Number.isFinite(c.existingTopM)&&Number.isFinite(c.existingHeightM)?c.existingTopM+c.existingHeightM:0));
 const requestedMax=Math.max(4,activeDepth.allowedMaximumM||0,deepest+.4),depthStep=axisStep(requestedMax,6),max=Math.ceil(requestedMax/depthStep)*depthStep;
 const plotWidth=w-left-right,plotBottom=height-bottom,x=d=>left+d/p.lengthM*plotWidth,y=h=>top+h/max*(plotBottom-top);
 const minDepth=Math.min(...p.stations.map(s=>s.depthM)),maxDepth=Math.max(...p.stations.map(s=>s.depthM));
 $('depthSummary').textContent=`DN ${measured(p.diameter)} · длина ${precise(p.lengthM)} м · глубина ${precise(minDepth)}–${precise(maxDepth)} м`;
 let svg=`<svg class="profileSvg" viewBox="0 0 ${w} ${height}" role="img" aria-labelledby="profileSvgTitle profileSvgDesc" data-edge-id="${escapeHtml(p.edgeId)}"><title id="profileSvgTitle">Продольный разрез выбранного участка: от А до Б</title><desc id="profileSvgDesc">Длина ${precise(p.lengthM)} м. Глубина до верха труб от ${precise(minDepth)} до ${precise(maxDepth)} м. Нулевая глубина — поверхность земли; глубина увеличивается вниз.</desc><defs><clipPath id="profileClip"><rect x="${left}" y="${top}" width="${plotWidth}" height="${plotBottom-top}"/></clipPath></defs><rect x="${left}" y="${top}" width="${plotWidth}" height="${plotBottom-top}" fill="#faf0e9"/>`;
 for(let h=0;h<=max+1e-6;h+=depthStep)svg+=`<line x1="${left}" x2="${w-right}" y1="${y(h)}" y2="${y(h)}" stroke="#e6d7cd"/><text x="${left-10}" y="${y(h)+4}" text-anchor="end">${measured(h)}</text>`;
 const lengthStep=axisStep(p.lengthM,compact?3:6),ticks=[];
 for(let d=0;d<p.lengthM;d+=lengthStep)if(d===0||x(p.lengthM)-x(d)>42)ticks.push(d);
 ticks.push(p.lengthM);
 for(const d of ticks)svg+=`<line x1="${x(d)}" x2="${x(d)}" y1="${top}" y2="${plotBottom}" stroke="#eddfd5"/><line x1="${x(d)}" x2="${x(d)}" y1="${plotBottom}" y2="${plotBottom+5}" stroke="#778983"/><text x="${x(d)}" y="${plotBottom+21}" text-anchor="middle">${number(d)}</text>`;
 if(Number.isFinite(activeDepth.ordinaryDepthM))svg+=`<line x1="${left}" x2="${w-right}" y1="${y(activeDepth.ordinaryDepthM)}" y2="${y(activeDepth.ordinaryDepthM)}" stroke="#8aa39a" stroke-dasharray="5 5"/>`;
 svg+=`<g clip-path="url(#profileClip)">`;
 for(const c of rows){
  if(![c.fromM,c.toM,c.coreFromM,c.coreToM].every(Number.isFinite))continue;
  svg+=`<rect x="${x(c.fromM)}" y="${top}" width="${Math.max(.5,x(c.toM)-x(c.fromM))}" height="${plotBottom-top}" fill="#d6a873" opacity=".12"/>`;
  const center=x((c.coreFromM+c.coreToM)/2),span=Math.max(10,x(c.coreToM)-x(c.coreFromM)),title=`${crossingNames[c.type]||'Коммуникация'} · ${c.existingObjectId} · ${precise(c.coreFromM)} м от А`;
  if(c.position==='under_surface'){
   svg+=`<line class="crossingSurface" x1="${x(c.coreFromM)}" x2="${x(c.coreToM)}" y1="${top+2}" y2="${top+2}" stroke="#8b857c" stroke-width="5"><title>${escapeHtml(title)}</title></line>`;
  }else if(Number.isFinite(c.existingTopM)&&Number.isFinite(c.existingHeightM)){
   const pipeHeight=Math.max(2,y(c.existingTopM+c.existingHeightM)-y(c.existingTopM));
   svg+=`<rect class="crossingSection" x="${center-span/2}" y="${y(c.existingTopM)}" width="${span}" height="${pipeHeight}" rx="2" fill="#f08b3c" stroke="#bc601f"><title>${escapeHtml(title)} · верх на глубине ${precise(c.existingTopM)} м · зазор ${precise(c.actualClearanceM)} м</title></rect>`;
  }
 }
 svg+=`<polyline class="routeProfile" points="${p.stations.map(s=>`${x(s.distanceM)},${y(s.depthM)}`).join(' ')}" fill="none" stroke="${SELECTED_ROUTE_COLOR}" stroke-width="4" stroke-linejoin="round" stroke-linecap="round"/>`;
 for(const s of p.stations)svg+=`<circle cx="${x(s.distanceM)}" cy="${y(s.depthM)}" r="2.5" fill="${SELECTED_ROUTE_COLOR}"><title>${precise(s.distanceM)} м от А · глубина ${precise(s.depthM)} м</title></circle>`;
 svg+='</g>';
 svg+=`<line class="groundSurface" x1="${left}" x2="${w-right}" y1="${top}" y2="${top}" stroke="#b57951" stroke-width="2"/><line x1="${left}" x2="${left}" y1="${top}" y2="${plotBottom}" stroke="#778983"/><line x1="${left}" x2="${w-right}" y1="${plotBottom}" y2="${plotBottom}" stroke="#778983"/><text x="${left}" y="17">Глубина, м ↓</text><text x="${left+8}" y="${top-10}" class="surfaceLabel">Поверхность земли · 0 м</text><text x="${left}" y="${plotBottom+42}" class="endpointLabel">А</text><text x="${w-right}" y="${plotBottom+42}" text-anchor="end" class="endpointLabel">Б</text><text x="${left+plotWidth/2}" y="${height-4}" text-anchor="middle">Длина участка, м →</text><g id="profileCursor" hidden><line y1="${top}" y2="${plotBottom}" stroke="#507e60" stroke-dasharray="3 3"/><circle r="5" fill="white" stroke="${SELECTED_ROUTE_COLOR}" stroke-width="2"/></g></svg>`;
 $('profileChart').innerHTML=svg;
 const defaultReadout=`А: ${precise(p.stations[0].depthM)} м · Б: ${precise(p.stations[p.stations.length-1].depthM)} м. Наведите указатель на разрез для точного значения.`;
 $('profileReadout').textContent=defaultReadout;
 $('profileCaption').textContent=`Зелёная линия — глубина до верха труб. Пунктир — обычная глубина ${measured(activeDepth.ordinaryDepthM)} м. Рельеф принят плоским; масштабы осей различаются. Оранжевые сечения коммуникаций условные, их положение по глубине взято из расчёта.`;
 const root=$('profileChart').querySelector('svg'),cursor=$('profileCursor');
 root.onpointermove=e=>{
  const rect=root.getBoundingClientRect(),px=(e.clientX-rect.left)/rect.width*w,distance=Math.max(0,Math.min(p.lengthM,(px-left)/plotWidth*p.lengthM)),depth=profileDepthAt(p,distance);
  cursor.removeAttribute('hidden');const line=cursor.querySelector('line'),point=cursor.querySelector('circle');line.setAttribute('x1',x(distance));line.setAttribute('x2',x(distance));point.setAttribute('cx',x(distance));point.setAttribute('cy',y(depth));
  $('profileReadout').textContent=`От А: ${precise(distance)} м · глубина до верха труб: ${precise(depth)} м`;
 };
 root.onpointerleave=()=>{cursor.setAttribute('hidden','');$('profileReadout').textContent=defaultReadout;};
 const names={above:'Над коммуникацией',below:'Под коммуникацией',under_surface:'Под покрытием'};
 $('depthCrossings').innerHTML=rows.length?rows.map(c=>`<tr><td>${escapeHtml(crossingNames[c.type]||'Коммуникация')}<small>${escapeHtml(c.existingObjectId)} · ${precise(c.coreFromM)} м от А</small></td><td>${names[c.position]||escapeHtml(c.position)}</td><td>${precise(c.depthM)}</td><td>${precise(c.actualClearanceM)} / ${precise(c.requiredClearanceM)}</td></tr>`).join(''):'<tr><td colspan="6">На этом участке пересечений нет</td></tr>';
}

const canvas=$('map'),ctx=canvas.getContext('2d');let scale=1,tx=0,ty=0,latScale=1,origin=[0,0],drag=null,lastMove=0,drawn=[];
function points(geom,out=[]){if(!geom)return out;function walk(c){if(typeof c[0]==='number')out.push(c);else c.forEach(walk);}walk(geom.coordinates);return out;}
function xy(p){return[(p[0]-origin[0])*latScale,(origin[1]-p[1])];}
function screen(p){const q=xy(p);return[q[0]*scale+tx,q[1]*scale+ty];}
function fit(){const all=[...inputFeatures,...outputFeatures].flatMap(f=>points(f.geometry));if(!all.length){draw();return;}let x0=Infinity,x1=-Infinity,y0=Infinity,y1=-Infinity;for(const p of all){x0=Math.min(x0,p[0]);x1=Math.max(x1,p[0]);y0=Math.min(y0,p[1]);y1=Math.max(y1,p[1]);}origin=[(x0+x1)/2,(y0+y1)/2];latScale=Math.cos(origin[1]*Math.PI/180);scale=Math.min((canvas.clientWidth-70)/Math.max(1e-6,(x1-x0)*latScale),(canvas.clientHeight-70)/Math.max(1e-6,y1-y0));tx=canvas.clientWidth/2;ty=canvas.clientHeight/2;draw();}
function draw(){const width=canvas.clientWidth,height=canvas.clientHeight,dpr=window.devicePixelRatio||1;canvas.width=width*dpr;canvas.height=height*dpr;ctx.setTransform(dpr,0,0,dpr,0,0);ctx.clearRect(0,0,width,height);drawn=[];
 function paint(f,overlay){if(!f.geometry)return;const type=f.properties.object_type,g=f.geometry;let color=overlay?'#df743e':'#a5aba8',fill='#dce0da',stroke=1;if(type==='heat_network'){color=overlay?'#df743e':'#43858b';stroke=overlay?3.4:2;}if(type==='heat_network_reconstruction'){color='#7457b9';stroke=5;}if(type==='restriction'&&f.properties.restriction_type==='water'){color='#7fa5b2';fill='#c5dce1';}if(type==='oks_connection_point'){color='#152e40';fill=color;}if(type==='heat_chamber'||type==='tie_in'){fill='#fff';color=overlay?'#df743e':'#43858b';}if(type==='source'){fill='#43858b';color=fill;}if(type==='tie_in'){fill='#176b68';color='#fff';stroke=2;}
 const routeId=overlay?routeIdOf(f):null;if(routeId&&routeId===selectedRouteId){color=SELECTED_ROUTE_COLOR;stroke=5;}
 ctx.strokeStyle=color;ctx.fillStyle=fill;ctx.lineWidth=stroke;ctx.lineJoin='round';ctx.lineCap='round';
 const path=new Path2D(),screenPoints=[];function line(ring,close){ring.forEach((p,i)=>{const q=screen(p);screenPoints.push(q);if(i===0)path.moveTo(...q);else path.lineTo(...q);});if(close)path.closePath();}
 if(g.type==='Point'){const p=screen(g.coordinates);path.arc(p[0],p[1],type==='tie_in'?6.5:type==='oks_connection_point'?4.5:3.5,0,2*Math.PI);ctx.fill(path);ctx.stroke(path);}else if(g.type==='LineString'){line(g.coordinates,false);ctx.stroke(path);}else{const polygons=g.type==='Polygon'?[g.coordinates]:g.coordinates;for(const polygon of polygons)for(const ring of polygon)line(ring,true);ctx.fill(path,'evenodd');ctx.stroke(path);}
 drawn.push({feature:f,path,overlay,routeId,screenPoints});
 }
 const rank=f=>f.geometry?.type==='Point'?2:f.geometry?.type==='LineString'?1:0;
 const overlay=[...outputFeatures].sort((a,b)=>rank(a)-rank(b)+(selectedRouteId&&routeIdOf(a)===selectedRouteId?0.5:0)-(selectedRouteId&&routeIdOf(b)===selectedRouteId?0.5:0));
 for(const f of [...inputFeatures].sort((a,b)=>rank(a)-rank(b)))if(f.geometry?.type!=='Point')paint(f,false);
 for(const f of overlay)if(f.geometry?.type!=='Point')paint(f,true);
 for(const f of inputFeatures)if(f.geometry?.type==='Point')paint(f,false);
 for(const f of overlay)if(f.geometry?.type==='Point')paint(f,true);
 if($('showEntryIds').checked){
  ctx.save();ctx.font='600 11px system-ui';ctx.textBaseline='middle';ctx.lineWidth=3;ctx.lineJoin='round';
  for(const f of inputFeatures){if(f.properties.object_type!=='oks_connection_point'||f.geometry?.type!=='Point')continue;
   const p=screen(f.geometry.coordinates);if(p[0]<0||p[1]<0||p[0]>width||p[1]>height)continue;
   const label=String(f.properties.id);ctx.strokeStyle='#f5f4ed';ctx.fillStyle='#193442';ctx.strokeText(label,p[0]+8,p[1]-7);ctx.fillText(label,p[0]+8,p[1]-7);
  }ctx.restore();
 }
 const profile=profileByEdge.get(selectedRouteId);
 if(profile){const prefix='tt:v'+(selected+1)+':';for(const [label,atStart] of [['А',true],['Б',false]]){
  const part=drawn.find(item=>item.overlay&&item.routeId===selectedRouteId&&item.feature.properties.id===prefix+profile.edgeId+':'+(atStart?0:profile.stations.length-2));
  if(!part)continue;const q=part.screenPoints[atStart?0:part.screenPoints.length-1],x=Math.max(13,Math.min(width-13,q[0]+12)),y=Math.max(13,Math.min(height-13,q[1]-14));
  ctx.beginPath();ctx.arc(x,y,11,0,2*Math.PI);ctx.fillStyle=SELECTED_ROUTE_COLOR;ctx.fill();ctx.fillStyle='#fff';ctx.font='bold 11px system-ui';ctx.textAlign='center';ctx.textBaseline='middle';ctx.fillText(label,x,y);
 }ctx.textBaseline='alphabetic';}

 if(!inputFeatures.length&&!outputFeatures.length){ctx.fillStyle='#78908f';ctx.font='14px system-ui';ctx.textAlign='center';ctx.fillText('Схема появится после загрузки GeoJSON',width/2,height/2);}
}
function nearestRoute(x,y,tolerance=8){
 let best=null,bestDistance=tolerance;
 for(const item of drawn){
  if(!item.overlay||!item.routeId)continue;
  for(let i=1;i<item.screenPoints.length;i++){
   const a=item.screenPoints[i-1],b=item.screenPoints[i],dx=b[0]-a[0],dy=b[1]-a[1],length2=dx*dx+dy*dy;
   const t=length2?Math.max(0,Math.min(1,((x-a[0])*dx+(y-a[1])*dy)/length2)):0;
   const distance=Math.hypot(x-a[0]-t*dx,y-a[1]-t*dy);
   if(distance<bestDistance){best=item;bestDistance=distance;}
  }
 }
 return best;
}
function mapPosition(e){const r=canvas.getBoundingClientRect();return[e.clientX-r.left,e.clientY-r.top];}
canvas.onwheel=e=>{e.preventDefault();const [x,y]=mapPosition(e),k=e.deltaY<0?1.15:1/1.15;tx=x+(tx-x)*k;ty=y+(ty-y)*k;scale*=k;draw();};
canvas.onpointerdown=e=>{
 if(e.button!==0||e.isPrimary===false)return;
 drag={pointerId:e.pointerId,x:e.clientX,y:e.clientY,ox:tx,oy:ty};lastMove=0;canvas.setPointerCapture(e.pointerId);canvas.focus({preventScroll:true});canvas.style.cursor='grabbing';
};
canvas.onpointermove=e=>{
 if(drag){
  if(drag.pointerId!==e.pointerId)return;
  lastMove=Math.max(lastMove,Math.hypot(e.clientX-drag.x,e.clientY-drag.y));
  if(lastMove>5){tx=drag.ox+e.clientX-drag.x;ty=drag.oy+e.clientY-drag.y;draw();}
 }else canvas.style.cursor=nearestRoute(...mapPosition(e))?'pointer':'grab';
};
canvas.onpointerup=e=>{
 if(!drag||drag.pointerId!==e.pointerId)return;
 const moved=lastMove>5;drag=null;if(canvas.hasPointerCapture(e.pointerId))canvas.releasePointerCapture(e.pointerId);canvas.style.cursor='grab';if(moved)return;
 const [x,y]=mapPosition(e),route=nearestRoute(x,y,e.pointerType==='touch'?14:8);
 if(route){$('selection').textContent=JSON.stringify(route.feature.properties,null,2);$('featureDetails').hidden=false;selectRoute(route.routeId,true);return;}
 // Other objects retain their attribute inspector; they must not leave an unrelated profile open.
 const dpr=window.devicePixelRatio||1;ctx.save();ctx.lineWidth=12;
 let hit=null;
 for(const item of [...drawn].reverse()){
  const t=item.feature.geometry.type;
  if(t==='Polygon'||t==='MultiPolygon'?ctx.isPointInPath(item.path,x*dpr,y*dpr,'evenodd'):ctx.isPointInStroke(item.path,x*dpr,y*dpr)){hit=item;break;}
 }
 ctx.restore();clearProfile();$('featureDetails').hidden=!hit;
 if(hit){
  $('selection').textContent=JSON.stringify(hit.feature.properties,null,2);
  const type=hit.feature.properties.object_type;
  $('mapHint').textContent=type==='heat_network'||type==='heat_network_reconstruction'?'Для существующей сети и реконструкции новый профиль глубины не рассчитывался. Выберите участок новой сети.':'Выбран исходный объект или узел. Его атрибуты доступны ниже; для разреза нажмите на новую линию.';
 }
};
canvas.onpointercancel=canvas.onlostpointercapture=()=>{drag=null;canvas.style.cursor='grab';};
canvas.onkeydown=e=>{
 if(e.key==='Escape'){e.preventDefault();clearProfile();$('featureDetails').hidden=true;return;}
 if(!['ArrowLeft','ArrowRight','ArrowUp','ArrowDown'].includes(e.key)||!profileByEdge.size)return;
 e.preventDefault();const ids=[...profileByEdge.keys()],current=ids.indexOf(selectedRouteId),direction=['ArrowLeft','ArrowUp'].includes(e.key)?-1:1;
 selectRoute(ids[current<0?(direction>0?0:ids.length-1):(current+direction+ids.length)%ids.length]);
};
$('fit').onclick=fit;
new ResizeObserver(()=>fit()).observe(canvas);
new ResizeObserver(()=>{const width=$('profileChart').clientWidth;if(width&&width!==profileWidth&&selectedRouteId)drawProfile();}).observe($('profileChart'));
draw();

let generalJob=null;
async function refreshExperience(){
 try{
  const state=await request('/experience');
  $('experienceStatus').textContent=`Участков с настройками: ${state.territories}. Для общей модели: ${state.trainingMaps} обучающих, ${state.validationMaps} проверочных. Общая модель: ${state.generalModelId}. Участков с обучением дерева: ${state.treeLearningTerritories||0}; принятых обновлений весов: ${state.treeAcceptedGradientSteps||0}.`;
  $('trainGeneral').disabled=!!generalJob||!state.canTrainGeneral;
  if(!generalJob&&state.lastGeneralTraining)$('generalStatus').textContent=state.lastGeneralTraining.message;
 }catch(e){$('experienceStatus').textContent='Не удалось прочитать опыт: '+e.message;}
}
$('refreshExperience').onclick=refreshExperience;
$('trainGeneral').onclick=async()=>{
 try{
  $('trainGeneral').disabled=true;$('generalReport').hidden=true;
  const start=await request('/experience/train?passes='+Number($('generalPasses').value),{method:'POST'});
  generalJob=start.id;$('cancelGeneral').hidden=false;
  for(;;){
   const state=await request('/jobs/'+generalJob);$('generalStatus').textContent=state.message||state.status;
   if(state.status==='FAILED')throw new Error(state.message);
   if(state.status==='DONE'){$('generalReport').href=api+'/jobs/'+generalJob+'/report';$('generalReport').hidden=false;break;}
   if(state.status==='CANCELLED')break;
   await pause();
  }
 }catch(e){$('generalStatus').textContent=e.message;}
 finally{generalJob=null;$('cancelGeneral').hidden=true;await refreshExperience();}
};
$('cancelGeneral').onclick=async()=>{try{if(generalJob)await request('/jobs/'+generalJob+'/cancel',{method:'POST'});}catch(e){error(e.message);}};
refreshExperience();

function updateLearningSettings(){
 const enabled=$('rlLearning').checked,remember=$('rlRemember').checked;
 $('rlBatchSize').disabled=!enabled;$('rlLearningRate').disabled=!enabled;
 $('rlLearningNote').textContent=enabled
  ? 'Веса обновляются после пакета новых попыток. Первая попытка — проверка модели, без обучения. '+(remember?'Незавершённый пакет продолжается в следующем расчёте. Лучшие полные сети сохраняются отдельно.':'Обучение и лучшие сети относятся только к этому запуску: сохранение опыта выключено.')
  : 'Обучение выключено: веса в этом расчёте не меняются. '+(remember?'Сохранённая модель и лучшие сети используются; новые лучшие сети сохраняются.':'Используется начальная модель без сохранённого опыта.');
}
$('rlLearning').onchange=updateLearningSettings;$('rlRemember').onchange=()=>{updateLearningSettings();updateTreeLearningSettings();};
updateLearningSettings();

function renderTreeProgress(report){
 const tree=report.search?.treeSearch,q=report.search?.qualityProgress;
 $('treeProgress').hidden=!tree;if(!tree)return;
 const graph=tree.graph||{};renderTreeLearning(report);renderRootDiagnostics(report);
 const geometricRuns=[tree.sharedOptimization?.geometricOptimization,tree.sharedOptimization?.geometricFinalOptimization].filter(Boolean),geometricMoves=geometricRuns.flatMap(run=>run.moves||[]);
 const rows=[['Связей в обзорном графе',number(graph.candidateEdges||0)],['Связей в остове коридоров',number(graph.skeletonEdges||0)],['Уровней дерева пройдено',tree.resumedFromBestNetwork?'Продолжение сохранённой сети':tree.beamSkippedAfterCorridorTree?'Полное дерево на карте проходов':`${tree.completedLevels}/${report.search.requiredConnections}`],['Полных деревьев на карте проходов',number(tree.corridorTrees?.completeCandidates||0)],['Проверено продолжений',number(tree.testedExtensions||0)],['Отклонено продолжений',number(tree.rejectedExtensions||0)],['Улучшений отдельных ветвей',number(tree.acceptedRepairs||0)],['Улучшений геометрии и структуры',number(geometricMoves.length)],['Из них переподключений поддеревьев',number(geometricMoves.filter(move=>move.kind==='subtree_exchange').length)],['Объединений поддеревьев общей развилкой',number(geometricMoves.filter(move=>move.kind==='nonadjacent_steiner_merge').length)],['Время геометрического поиска, с',number(geometricRuns.reduce((sum,run)=>sum+(run.elapsedMs||0),0)/1000)],['Улучшений глубокой перестройкой групп',number(tree.sharedOptimization?.acceptedGroups||0)],['Улучшений глубокой перестройкой стволов',number(tree.sharedOptimization?.acceptedStems||0)],['Улучшений перестройкой развилок',number(tree.sharedOptimization?.junctionOptimization?.acceptedPatches||0)],['Время перестройки развилок, с',number((tree.sharedOptimization?.junctionOptimization?.elapsedMs||0)/1000)],['Улучшений общей схемы стволов',number(tree.sharedOptimization?.acceptedLayouts||0)],['Повторных проверок использовано из кеша',number(tree.transitionCacheHits||0)],['Врезок в выбранной сети',number(tree.sharedOptimization?.selectedTieIns||0)],['Лучший score до расчёта',scoreText(q?.bestBeforeRun)],['Лучший score после расчёта',scoreText(q?.bestAfterRun)]];
 $('treeMetrics').innerHTML=reportTable('Поиск и результат',['Показатель','Значение'],rows.map(([a,b])=>`<tr><th scope="row">${escapeHtml(a)}</th><td>${escapeHtml(b)}</td></tr>`));
 $('treeQuality').textContent=q?.improved?`Лучший score улучшился на ${scoreText(q.improvementPercent)}%.`:Number.isFinite(q?.bestBeforeRun)?'Лучший сохранённый score не улучшился.':Number.isFinite(q?.bestAfterRun)?'Получена первая полная сеть для сравнения следующих запусков.':'Полная сеть не найдена в проверенных продолжениях.';
 $('treeLimits').textContent=`Обзорная сетка: шаг ${number(graph.spacingM)} м. Общая карта проходов дополнена входами и обходами зданий. Готовые сети проверяются точно. ${report.search?.treeLearning?.weightsChanged?'Рабочая модель обновлена.':'Рабочие веса не менялись.'} Диффузионная модель не используется. Исследовано до ${tree.beamWidth} деревьев на уровне — глобальный минимум не доказан.`;
 const shared=tree.sharedOptimization||{},rootTrials=shared.singleRootTrials||[];
 if(shared.singleRootComparisonEnabled){const full=rootTrials.filter(r=>r.complete);$('treeLimits').textContent+=full.length?` Проверка общей врезки: полных вариантов ${full.length}; лучший score ${scoreText(Math.min(...full.map(r=>r.candidateScore)))}. Выбрано врезок: ${shared.selectedTieIns}.`:' В проверенных местах и порядках полная сеть с одной врезкой не найдена.';}
 const grouped=Object.entries(tree.rejectedByEntry||{}).sort((a,b)=>b[1]-a[1]);
 const corridorRejected=(tree.corridorTrees?.trials||[]).filter(t=>!t.complete&&t.reason);
 const reasonText=reason=>/non-noded|TopologyException/.test(reason)?'Геометрия кандидата не прошла проверку; вариант отклонён.':reason;
 const rejectedRows=grouped.map(([id,count])=>{const sample=(tree.rejections||[]).find(r=>r.entryId===id);return `<p><b>Ввод ${escapeHtml(id)} · ${count}</b><br>${escapeHtml(reasonText(sample?.reason||'Подробности в JSON-отчёте'))}</p>`;});
 for(const trial of corridorRejected)rejectedRows.push(`<p><b>Врезка ${escapeHtml(trial.existingId)} · карта проходов</b><br>${escapeHtml(reasonText(trial.reason))}</p>`);
 $('treeRejections').innerHTML=rejectedRows.join('')||'Отклонённых продолжений нет.';
}

verifyServerBuild().catch(e=>error(e.message));

function updateTreeLearningSettings(){
 const enabled=$('treeLearning').checked,remember=$('rlRemember').checked;
 $('treeLearningRounds').disabled=!enabled;
 $('treeLearningNote').textContent=enabled
  ? 'Копия модели учится между полными проходами. Рабочие веса заменяются после контрольного поиска, если его результат на этом участке не хуже. '+(remember?'Модель, примеры и лучшие сети сохраняются для следующих расчётов.':'Изменения относятся только к текущему расчёту: сохранение опыта выключено.')
  : 'Поиск и перестройка дерева без обновления весов. '+(remember?'Лучшие полные сети сохраняются.':'Сохранение опыта выключено.');
}
$('treeLearning').onchange=updateTreeLearningSettings;
updateTreeLearningSettings();
function renderTreeLearning(report){
 const learning=report.search?.treeLearning;
 $('treeLearningDetails').hidden=!learning;
 $('treeLearningMetrics').innerHTML='';$('treeLearningPasses').innerHTML='';
 if(!learning){$('treeLearningStatus').textContent='Дообучение выключено. Поиск маршрутов и сохранение лучших сетей работают.';return;}
 const rows=[['Проходов поиска',`${learning.completedSearchPasses}/${learning.requestedSearchPasses}`],['Сравнений загружено из опыта',number(learning.initialReplayPairs)],['Новых сравнений сохранено',number(learning.newReplayPairs)],['Сравнений в памяти',number(learning.replayPairs)],['Шагов обучения выполнено',number(learning.gradientStepsThisRun)],['Шагов принято после проверки',number(learning.acceptedGradientStepsThisRun)],['Моделей принято / отклонено',`${learning.acceptedModelsThisRun} / ${learning.rejectedModelsThisRun}`],['Изменилось весов рабочей модели',`${learning.changedWeightCount}/${learning.weightCount}`],['Величина изменения весов (L2)',scoreText(learning.weightDeltaL2)]];
 $('treeLearningMetrics').innerHTML=reportTable('Автоматическое обучение',['Показатель','Значение'],rows.map(([a,b])=>`<tr><th scope="row">${escapeHtml(a)}</th><td>${escapeHtml(b)}</td></tr>`));
 const improved=Number.isFinite(learning.baselineFreshScore)&&Number.isFinite(learning.acceptedPolicyFreshScore)&&learning.acceptedPolicyFreshScore<learning.baselineFreshScore-1e-8;
 $('treeLearningStatus').textContent=learning.weightsChanged
  ? 'Рабочая модель обновлена и проверена на этом участке. '+(improved?'Поиск с новыми весами улучшил контрольный результат.':'Качество контрольного результата сохранилось; улучшение маршрута от обучения пока не подтверждено.')
  : learning.gradientStepsThisRun>0?'Дообучение выполнено, рабочие веса сохранены прежними: кандидат не прошёл проверку или обучение было прервано.':'Новые веса не обучались: нет информативных сравнений полных сетей либо расчёт был отменён.';
 if(learning.stopReason==='two_validation_passes_without_quality_gain')$('treeLearningStatus').textContent+=' Поиск с новыми весами остановлен после двух проверок без улучшения.';
 const passes=(report.search?.treeSearch?.passes||[]).map(p=>`<tr><td>${p.pass}</td><td>${p.role==='baseline'?'Контрольный':'С новой моделью'}</td><td>${scoreText(p.freshBestScore)}</td><td>${number(p.elapsedMs/1000)} с</td><td>${p.role==='baseline'?'Исходная модель':p.modelAccepted?'Принята':'Сохранена прежняя'}</td></tr>`);
 const updates=(learning.updateHistory||[]).map(u=>`<tr><td>${u.afterPass}</td><td>${scoreText(u.lossBefore)}</td><td>${scoreText(u.lossAfter)}</td><td>${u.accepted?'Принято':u.validatedBySearch?'Отклонено':'Не применено'}</td></tr>`);
 $('treeLearningPasses').innerHTML=reportTable('Сравнение проходов',['Проход','Модель','Score ↓','Время','Решение'],passes)+reportTable('Обучение по примерам',['После прохода','Ошибка до ↓','Ошибка после ↓','Решение'],updates);
}

function updateRootSettings(){
 const single=$('treeSingleRootRequired').checked;
 $('treeJunctionRepair').disabled=!single;$('treeGeometricSearch').disabled=!single;
 $('singleRootTrialLabel').hidden=single;
 $('rootSettingNote').textContent=single
  ? 'Все новые ветви растут из общей сети. Место врезки проверяется для каждого ввода. Опыт этого режима сохраняется отдельно.'
  : 'Разрешено несколько врезок. Результат выбирается по стоимости, длине и поворотам. Доступен опыт предыдущих версий.';
}
$('treeSingleRootRequired').onchange=updateRootSettings;updateRootSettings();
function renderRootDiagnostics(report){
 const tree=report.search.treeSearch,roots=tree.rootSelection,shared=tree.sharedOptimization||{};
 const single=!!report.options.treeSingleRootRequired,variant=report.variants?.[0];
 const geometry=shared.geometricOptimization,polish=shared.geometricFinalOptimization;
 $('geometricSummary').textContent=geometry?`Геометрический поиск: принято ${geometry.acceptedMoves+(polish?.acceptedMoves||0)} перестроек; score ${scoreText(geometry.beforeScore)} → ${scoreText(polish?.afterScore??shared.junctionOptimization?.afterScore??geometry.afterScore)}. Все сохранённые варианты проверены целиком.`:'';
 $('rootPolicyBadge').textContent=single?'Одна врезка обязательна':'Несколько врезок разрешены';
 $('rootPolicySummary').textContent=variant
  ? `${variant.connected_connection_count} из ${variant.required_connection_count} вводов · ${variant.tie_in_count} ${variant.tie_in_count===1?"врезка":"врезок"} · новая трасса ${number(variant.new_network_length)} м. ${single?'Все ветви соединены в одно дерево.':''}`
  : single?'Полное дерево с одной врезкой не найдено в проверенных вариантах.':'Полное подключение не найдено в проверенных вариантах.';
 $('rootDiagnostics').hidden=!roots;
 if(!roots)return;
 $('rootScreenNote').textContent=`Проверено мест: ${roots.checkedLocations}. По направлениям входов подходят: ${roots.entranceCompatibleLocations}. Для построения выбрано: ${roots.selectedLocations.length}. Это предварительная проверка; препятствия, диаметры и глубина проверяются при построении.`;
 const outcomes=tree.rootOutcomes||[];
 const selected=roots.selectedLocations.map(r=>{
  const same=(a,b)=>Math.abs(a[0]-b[0])<1e-8&&Math.abs(a[1]-b[1])<1e-8;
  const trials=[...(shared.singleRootTrials||[]),...(tree.corridorTrees?.trials||[])].filter(t=>t.existingId===r.existingId&&t.coordinates&&same(t.coordinates,r.coordinates));
  const state=outcomes.find(t=>t.existingId===r.existingId&&same(t.coordinates,r.coordinates));
  const connected=Math.max(state?.connected||0,...trials.map(t=>t.connected||0));
  const full=state?.complete||trials.some(t=>t.complete);
  const outcome=full?'Полное дерево':connected?`${connected} / ${report.search.requiredConnections} точек; проверка не пройдена`:'Нет полного результата';
  return `<tr><th scope="row">${escapeHtml(r.existingId)}</th><td>${number(r.axisDegrees)}°</td><td>${outcome}</td></tr>`;
 });
 $('rootCandidates').innerHTML=reportTable('Проверяемые врезки',['Объект','Направление','Результат'],selected);
 const windows=(report.search.entryAssignments||[]).filter(e=>Number.isFinite(e.eligibleWallCount)).sort((a,b)=>a.eligibleWallCount-b.eligibleWallCount);
 const selectedWalls=new Map((report.checks?.[0]?.permittedBuildingEntries||[]).map(e=>[e.sourceEntryId,e]));
 $('entryWindows').innerHTML=reportTable('Стены подключения',['Ввод','Доступно стен','От сети до выбранной / ближайшей'],windows.map(e=>{const wall=selectedWalls.get(e.sourceEntryId);return `<tr><th scope="row">${escapeHtml(e.sourceEntryId)}</th><td>${e.eligibleWallCount}</td><td>${wall?`${number(wall.selectedWallDistanceToNetworkM)} / ${number(wall.nearestWallDistanceToNetworkM)} м`:'Выбор после расчёта'}</td></tr>`}));
}

$('showEntryIds').onchange=draw;
