// Exercise the compiled release against missing and contradictory firmware screen information.
const assert = require('node:assert/strict')
const {existsSync, readdirSync, readFileSync} = require('node:fs')
const vm = require('node:vm')
const path = require('node:path')
const [root, profile] = process.argv.slice(2)
assert.ok(['standard', 'pill'].includes(profile))
const script = name => '(' + readFileSync(path.join(root, name), 'utf8').trim().replace(/^export default /, '') + ')'
{
  const sky = path.join(root, 'common/sky')
  assert.deepEqual(existsSync(sky) ? readdirSync(sky).sort() : [], profile === 'standard' ? ['day.png', 'night.png'] : [])
  const motion = path.join(root, 'common/motion')
  assert.deepEqual(existsSync(motion) ? readdirSync(motion).sort() : [], profile === 'standard'
    ? ['alpine.png', 'bamboo.png', 'circuit.png', 'city.png', 'coast.png', 'enamel.png', 'flux.png',
      'hamster.png', 'origami.png', 'paw.png', 'prism.png', 'ridge.png', 'sky-day.png', 'sky-night.png', 'tape.png'] : [])
  assert.equal(existsSync(path.join(root, 'common/default-vehicle.png')), profile === 'standard')
  // Check assets in the release itself. A valid source-tree path alone does not put it in the RPK.
  for (const group of ['', 'day/', 'open/']) {
    const categories = group === 'open/' ? ['LOCK','TRUNK','WINDOW','CLIMATE','SUNROOF','ENGINE'] : ['LOCK','WINDOW','FIND','TRUNK','ENGINE','SUNROOF','CLIMATE','OTHER']
    for (const category of categories) {
      const image = readFileSync(path.join(root, 'common/controls/local', group, category + '.png'))
      assert.deepEqual([...image.subarray(0,8)], [137,80,78,71,13,10,26,10])
      assert.equal(image.readUInt32BE(16), 64)
      assert.equal(image.readUInt32BE(20), 64)
    }
  }
}
for (const info of [null, {screenShape:'pill-shaped', screenWidth:192, screenHeight:490},
    {screenWidth:212, screenHeight:520}, {screenShape:'rect', screenWidth:336, screenHeight:480},
    {screenWidth:390, screenHeight:450}, {screenWidth:176, screenHeight:220},
    {screenShape:'circle', screenWidth:466, screenHeight:466}]) {
  const sent = []
  const runtime = {
    $app_require$: name => ({
      '@app-module/system.interconnect': {instance: () => ({send: message => sent.push(message.data)})},
      '@app-module/system.device': {getInfo({success}) { if (info) success(info) }},
      '@app-module/system.app': {getInfo: () => ({versionCode:26})}
    })[name],
    setTimeout() { return 1 }, clearTimeout() {}
  }
  const sandbox = vm.createContext(runtime), exports = {}, component = {}
  vm.runInContext(script('app.js'), sandbox)(runtime, runtime, runtime, {}, null)
  vm.runInContext(script('pages/garage/index.js'), sandbox)(runtime, runtime, runtime, exports, null)
  exports.entry(component)
  const page = Object.assign(component.default, component.default.private || component.default.data || {})
  page.onInit(); page.onReady(); page.onShow()
  assert.equal(page.layout.supportsTheme, profile === 'standard')
  assert.equal(page.motionImage.startsWith('/common/motion/'), profile === 'standard')
  assert.equal(sent[0].bandVersionCode, 26)
  assert.equal(sent[0].bandPackageProfile, profile)
  assert.equal(!!sent[0].bandPhoto, profile === 'standard')
  assert.equal(!!sent[0].bandPhotoHd, profile === 'standard')
  assert.equal(!!sent[0].remoteThemes, profile === 'standard')
  assert.equal(!!sent[0].bandTheme, profile === 'standard')
  if (profile === 'pill') assert.equal(page.layout.single, true)
  page.showSettings()
  assert.equal(page.mode, 'settings')
  page.onBackPress()
  assert.equal(page.mode, 'home')
}
console.log(`${profile}: missing, pill-shaped and rectangular device reports passed`)
