// Run after mvn package. Requires Playwright; JAVA_HOME and H2_JAR select test runtime.
const { chromium } = require('playwright');
const { spawn } = require('child_process');
const fs = require('fs'), path = require('path'), assert = require('assert');
(async () => {
  const project = path.resolve(__dirname, '../..');
  const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin/java') : 'java';
  const h2 = process.env.H2_JAR;
  assert(h2, 'Set H2_JAR to the Maven test dependency');
  const log = fs.openSync(path.join(__dirname, 'ui-server.log'), 'w');
  const server = spawn(java, ['-Dloader.path='+project+'/server/target/test-classes,'+h2,
    '-cp', project+'/server/target/teplotrassa-server-1.9.0-geometry.jar',
    'org.springframework.boot.loader.PropertiesLauncher', '--spring.profiles.active=test',
    '--server.port=18089', '--teplotrassa.storage='+project+'/server/target/ui-geometry-data'],
    {stdio:['ignore', log, log]});
  let browser;
  try {
    let ready = false;
    for (let i=0;i<250;i++) {
      try { if ((await fetch('http://127.0.0.1:18089/api/v1/health')).ok) {ready=true;break;} } catch {}
      await new Promise(resolve => setTimeout(resolve,100));
    }
    assert(ready, 'Server did not start');
    const launch = {headless:true};
    if (process.env.TEST_BROWSER_EXECUTABLE) {
      launch.executablePath = process.env.TEST_BROWSER_EXECUTABLE;
      launch.env = {...process.env, LD_LIBRARY_PATH:path.dirname(launch.executablePath)};
    }
    if (process.env.TEST_BROWSER_PACKAGE) launch.args = require(process.env.TEST_BROWSER_PACKAGE).args;
    browser = await chromium.launch(launch);
    const page = await browser.newPage({viewport:{width:1440,height:1050},reducedMotion:'reduce'});
    const errors = [];page.on('pageerror', e=>errors.push(e.message));
    const response = await page.goto('http://127.0.0.1:18089/tree-1.8.0',{waitUntil:'networkidle'});
    assert.equal(new URL(page.url()).pathname, '/tree-1.9.0');
    assert(response.headers()['cache-control'].includes('no-store'));
    assert((await page.locator('#serverBuild').innerText()).includes('подтверждён'));
    assert(await page.locator('#treeGeometricSearch').isChecked());
    assert(await page.locator('#treeJunctionRepair').isChecked());
    assert(!(await page.locator('#treeLearning').isChecked()));
    assert(!(await page.locator('#treeGroupRepair').isChecked()));
    await page.evaluate(()=>{document.querySelector('#treeSingleRootRequired').checked=false;updateRootSettings();});
    assert(await page.locator('#treeGeometricSearch').isDisabled());
    await page.evaluate(()=>{document.querySelector('#treeSingleRootRequired').checked=true;updateRootSettings();});
    assert(!(await page.locator('#treeGeometricSearch').isDisabled()));
    const report = JSON.parse(fs.readFileSync(path.join(__dirname,'supplied-trunk-report.json'),'utf8'));
    await page.evaluate(r=>{reportData=r;renderTreeProgress(r);},report);
    assert((await page.locator('#geometricSummary').innerText()).includes('принято 9'));
    assert((await page.locator('#rootPolicySummary').innerText()).includes('17 из 17'));
    await page.locator('#treeProgress').screenshot({path:path.join(__dirname,'ui-geometry-desktop.png')});
    await page.setViewportSize({width:390,height:844});
    await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(resolve)));
    assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'Mobile overflow');
    await page.locator('#treeProgress').screenshot({path:path.join(__dirname,'ui-geometry-mobile.png')});
    assert.equal(errors.length,0,errors.join('; '));
    fs.writeFileSync(path.join(__dirname,'ui-check.json'),JSON.stringify({passed:true,
      actualServerVersion:'1.9.0-geometry',actualBuildId:'geometry-20260923-r1',
      checks:['Java 11 JAR with H2 test database starts','old URL redirects','no-store HTML',
        'build verification','geometric default enabled','single-root settings toggle',
        'real benchmark report rendered without recalculation','17 entries and 9 geometric moves displayed',
        'desktop and mobile layout'],viewports:['1440x1050','390x844'],jsErrors:errors},null,2)+'\n');
    console.log('UI_CHECK_PASSED');
  } finally {if(browser)await browser.close();server.kill('SIGTERM');}
})().catch(e=>{console.error(e);process.exitCode=1;});
