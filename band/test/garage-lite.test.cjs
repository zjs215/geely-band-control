const assert = require('node:assert/strict')
const { existsSync, readFileSync } = require('node:fs')
const path = require('node:path')
const { test } = require('node:test')
const vm = require('node:vm')

const compiled = readFileSync(path.join(__dirname, '../build-lite/pages/garage/index.js'), 'utf8')
  .trim().replace(/^export default /, '')

function startLite(options = {}) {
  const sent = []
  const stored = new Map(Object.entries(options.stored || {}))
  const pulses = []
  const connection = {
    send(message) {
      sent.push(message)
      if (options.send) options.send(message)
    },
    getReadyState({ success }) {
      if (options.alreadyOpen !== false) success({ status: 1 })
    }
  }
  const storage = {
    get({ key, success, fail }) {
      if (stored.has(key)) success(stored.get(key))
      else if (fail) fail()
      else success('')
    },
    set({ key, value, complete }) {
      stored.set(key, value)
      if (complete) complete()
    }
  }
  const deviceInfo = options.deviceInfo || { screenWidth: 336, screenHeight: 480 }
  const native = {
    '@app-module/system.interconnect': { instance: () => connection },
    '@app-module/system.storage': storage,
    '@app-module/system.device': { getInfo: ({ success }) => success(deviceInfo) },
    '@app-module/system.vibrator': { vibrate: ({ mode }) => pulses.push(mode) },
    ...options.native
  }

  let clock = 0
  let timerId = 0
  const timers = new Map()
  const runtime = {
    ...(options.now === undefined ? {} : {
      Date: class extends Date {
        constructor(...args) { super(...(args.length ? args : [options.now + clock])) }
        static now() { return options.now + clock }
      }
    }),
    $app_require$(name) {
      if (!(name in native)) throw new Error('Unknown native module ' + name)
      return native[name]
    },
    setTimeout(callback, delay) {
      timers.set(++timerId, { callback, due: clock + delay })
      return timerId
    },
    clearTimeout(id) { timers.delete(id) }
  }
  function advance(ms) {
    const end = clock + ms
    while (timers.size) {
      const [id, timer] = [...timers].sort((left, right) => left[1].due - right[1].due)[0]
      if (timer.due > end) break
      timers.delete(id)
      clock = timer.due
      timer.callback()
    }
    clock = end
  }

  const exports = {}
  vm.runInNewContext('(' + compiled + ')', runtime)(runtime, runtime, runtime, exports, null)
  const component = {}
  exports.entry(component)
  const page = Object.assign(component.default, component.default.data)
  for (const [key, fn] of Object.entries(component.default.computed || {})) {
    Object.defineProperty(page, key, { get: fn, enumerable: true, configurable: true })
  }
  page.onInit()
  page.onShow()
  page.onReady()
  advance(100)

  function receive(reply) {
    connection.onmessage({ data: JSON.stringify(reply) })
  }
  function hello(overrides = {}) {
    receive({ v: 1, id: sent[0].data.id, type: 'hello-reply', phoneApp: '0.2.10',
      protocol: 1, features: ['confirm', 'cancel', 'refresh', 'theme_v1', 'band_display', 'control_icons'], ...overrides })
  }
  function reply(overrides = {}) {
    receive({ v: 1, id: sent.at(-1).data.id, type: 'reply', mode: 'live', status: 'ready',
      mapped: ['climate'], available: ['climate'], controlDirections: { climate: 'open' }, ...overrides })
  }
  return { page, sent, stored, pulses, advance, receive, hello, reply }
}

test('lite targets rect and capsule layouts and clamps watch shapes to rect', () => {
  const rect = startLite({ deviceInfo: { screenWidth: 336, screenHeight: 480 } })
  assert.equal(rect.page.layoutProfile, 'rect')
  assert.equal(rect.page.canChangeDisplay, false)
  assert.equal(rect.page.canSyncBandDisplay(), true)
  assert.equal(rect.page.canEditTheme, false)

  const capsule = startLite({ deviceInfo: { screenWidth: 212, screenHeight: 520, screenShape: 'pill-shaped' } })
  assert.equal(capsule.page.layoutProfile, 'capsule')
  assert.equal(capsule.page.canChangeDisplay, false)
  assert.equal(capsule.page.canSyncBandDisplay(), true)

  const circleClamped = startLite({ deviceInfo: { screenWidth: 480, screenHeight: 480, screenShape: 'circle' } })
  assert.equal(circleClamped.page.layoutProfile, 'rect')
})

test('lite uses phone-controlled solid day/night themes without image assets', () => {
  const link = startLite({ now: new Date(2026, 3, 1, 12, 0).getTime() })
  assert.equal(link.page.hasThemeSurface, false)
  assert.equal(link.page.themeStage, '')

  link.hello({ theme: { themeId: 'alpine', mode: 'fixed', variant: 'day', dayStartMinute: 360, nightStartMinute: 1080 } })
  assert.equal(link.page.themeArt, '')
  assert.equal(link.page.hasThemeSurface, true)
  assert.equal(link.page.themeDay, true)
  assert.equal(link.page.themeStage, ' theme-stage-day')
  assert.equal(link.page.themeIconPath, '/common/day-icons/')

  link.reply({ theme: { themeId: 'alpine', mode: 'fixed', variant: 'night', dayStartMinute: 360, nightStartMinute: 1080 } })
  assert.equal(link.page.themeArt, '')
  assert.equal(link.page.hasThemeSurface, true)
  assert.equal(link.page.themeDay, false)
  assert.equal(link.page.themeStage, ' theme-stage-night')
  assert.equal(link.page.themeIconPath, '/common/images/')

  link.advance(12000)
  link.reply({ theme: { themeId: 'classic', mode: 'schedule', variant: 'day', dayStartMinute: 360, nightStartMinute: 1080 } })
  assert.equal(link.page.hasThemeSurface, false)
  assert.equal(link.page.themeStage, '')
  assert.equal(link.page.themeDay, false)
})

test('lite disables local settings and syncs haptics and two-page display from phone', () => {
  const link = startLite({
    stored: {
      'rect-two-page': JSON.stringify({ twoPage: false, revision: 1, pending: true }),
      'control-haptics': 'on'
    }
  })
  assert.equal(link.page.displayPending, false)
  link.page.openSettings()
  link.page.openThemeEditor()
  assert.equal(link.page.settingsOpen, false)
  assert.equal(link.page.themeEditorOpen, false)
  assert.equal(link.page.onBackPress(), false)

  link.hello({ haptics: false, bandDisplay: { twoPage: true, revision: 2 } })
  assert.equal(link.page.hapticsEnabled, false)
  assert.equal(link.stored.get('control-haptics'), 'off')
  assert.equal(link.page.rectTwoPage, true)
  assert.equal(link.page.useTwoPageShell, true)

  // Status sent by lite never pushes local displayPending back to phone.
  const statusReq = link.sent.find(item => item.data.type === 'status')
  assert.equal(statusReq.data.bandDisplay, undefined)

  link.page.select('climate')
  assert.deepEqual(link.pulses, [])

  link.reply({ haptics: true, bandDisplay: { twoPage: false, revision: 0 } })
  assert.equal(link.page.hapticsEnabled, true)
  assert.equal(link.stored.get('control-haptics'), 'on')
  assert.equal(link.page.rectTwoPage, false)

  link.page.select('climate')
  assert.deepEqual(link.pulses, ['short'])

  const liteSource = readFileSync(path.join(__dirname, '../src/pages/garage/lite.ux'), 'utf8')
  assert.equal(/focusable=|enableswipe=|position:\s*absolute|letter-spacing:|transform:/.test(liteSource), false)
  assert.equal(existsSync(path.join(__dirname, '../dist/com.garage.bandcontrol.lite.' + require('../src/manifest.json').versionName + '.rpk')), true)
})
