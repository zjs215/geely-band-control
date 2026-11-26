// Reference 29-case suite retargeted to merged page; legacy peers default to Galaxy.
const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const path = require('node:path')
const { test } = require('node:test')
const vm = require('node:vm')

const manifest = require('../src/manifest.json')
const compiled = readFileSync(path.join(__dirname, '../build/pages/garage/index.js'), 'utf8')
  .trim().replace(/^export default /, '')

function start(options = {}) {
  const sent = []
  const connection = {
    send(message) {
      sent.push(message)
      if (options.send) options.send(message)
    },
    getReadyState({ success }) {
      if (options.alreadyOpen !== false) success({ status: 1 })
    }
  }
  const native = { '@app-module/system.interconnect': { instance: () => connection }, ...options.native }

  let clock = 0
  let timerId = 0
  const timers = new Map()
  const runtime = {
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
    receive({ v: 1, id: sent[0].data.id, type: 'hello-reply', phoneApp: '0.1.17',
      protocol: 1, features: ['confirm', 'cancel', 'refresh'], ...overrides })
  }
  function reply(overrides = {}) {
    receive({ v: 1, id: sent.at(-1).data.id, type: 'reply', mode: 'live', status: 'ready',
      mapped: ['climate'], available: ['climate'], controlDirections: { climate: 'open' }, ...overrides })
  }
  return { page, connection, sent, advance, receive, hello, reply, types: () => sent.map(item => item.data.type) }
}

test('a ready link negotiates before the first read and records the advertised capabilities', () => {
  const link = start({ alreadyOpen: false })
  assert.deepEqual(link.types(), [])
  link.connection.onopen()
  assert.deepEqual(link.types(), ['hello'])
  assert.equal(link.sent[0].data.bandApp, manifest.versionName)
  assert.equal(link.sent[0].data.minPhoneApp, '0.1.14')
  link.receive({ v: 1, type: 'hello-reply', id: 'unrelated', phoneApp: '1.1.0', protocol: 2 })
  assert.deepEqual(link.types(), ['hello'])
  link.hello()
  assert.deepEqual(link.types(), ['hello', 'status'])
  assert.equal(link.page.phoneApp, '0.1.17')
  assert.equal(link.page.protocol, 1)
  assert.deepEqual(Array.from(link.page.features), ['confirm', 'cancel', 'refresh'])
  link.reply()
  link.connection.onclose()
  link.connection.onopen()
  assert.deepEqual(link.types(), ['hello', 'status', 'hello'])
})

test('an explicit mismatch stops polling and shows a persistent upgrade message', () => {
  for (const [overrides, message] of [
    [{ protocol: 2 }, '请将手机和手环更新到配套版本'],
    [{ v: 2 }, '请将手机和手环更新到配套版本'],
    [{ phoneApp: '1.0.0' }, '请更新手环端到配套版本'],
    [{ phoneApp: '0.0.9' }, '请更新手机端到配套版本']
  ]) {
    const link = start()
    link.hello(overrides)
    assert.equal(link.page.modeLabel, '版本不符')
    assert.equal(link.page.compatibilityNotice, message)
    link.advance(30000)
    link.page.refresh()
    assert.deepEqual(link.types(), ['hello'])
  }
})

test('legacy peers that ignore or reject hello can still read and refresh', () => {
  for (const failImmediately of [false, true]) {
    const link = start({ send: message => {
      if (failImmediately && message.data.type === 'hello') message.fail()
    } })
    if (!failImmediately) {
      link.advance(1999)
      assert.deepEqual(link.types(), ['hello'])
      link.advance(1)
    }
    assert.deepEqual(link.types(), ['hello', 'status'])
    link.reply()
    link.page.refresh()
    assert.deepEqual(link.types(), ['hello', 'status', 'refresh'])
  }
})

test('a negotiated peer only gets requests it advertises', () => {
  const link = start()
  link.hello({ features: ['custom_read'] })
  link.reply()
  link.page.refresh()
  link.page.select('climate')
  link.page.request('unknown_request')
  assert.deepEqual(link.types(), ['hello', 'status'])
  assert.match(link.page.toastText, /手机端不支持/)
  link.page.request('custom_read')
  assert.deepEqual(link.types(), ['hello', 'status', 'custom_read'])
})

const CLIMATE_CONFIRMATION = {
  status: 'confirm', action: 'climate', token: 'a'.repeat(36), message: '开启空调'
}

function confirmClimate(link) {
  link.page.select('climate')
  assert.equal(link.sent.at(-1).data.type, 'prepare')
  link.reply(CLIMATE_CONFIRMATION)
  assert.equal(link.page.confirmTitle, CLIMATE_CONFIRMATION.message)
}

test('window controls follow the phone directions and prepare the action shown on the tile', () => {
  const link = start()
  link.hello()
  const windows = ['window_vent', 'window_open']
  const openActions = { mapped: windows, available: windows, controlDirections: { window_vent: 'open', window_open: 'open' } }
  const closeAction = { mapped: windows, available: ['window_vent'], controlDirections: { window_vent: 'close' },
    blocked: { window_open: '车窗未关，当前卡片按钮未显示关窗方向' } }
  link.reply(openActions)
  assert.equal(link.page.displayRows[0].first.label, '窗微开')
  assert.equal(link.page.displayRows[0].second.label, '窗全开')
  link.page.select('window_vent')
  assert.equal(link.sent.at(-1).data.controlDirection, 'open')
  link.reply({ ...closeAction, status: 'blocked', message: '按钮状态已变化，请重新选择' })
  assert.equal(link.page.displayRows[0].first.label, '关窗')
  assert.equal(link.page.displayRows[0].second.hint, '暂不可用')
  const before = link.sent.length
  link.page.select('window_open')
  assert.equal(link.sent.length, before)
  assert.match(link.page.toastText, /未显示关窗方向/)
  link.page.select('window_vent')
  assert.equal(link.sent.at(-1).data.controlDirection, 'close')
  const token = 'b'.repeat(36)
  link.reply({ ...closeAction, status: 'confirm', action: 'window_vent', message: '关闭车窗', token })
  assert.equal(link.page.confirmTitle, '关闭车窗')
  link.page.execute()
  assert.equal(link.sent.at(-1).data.type, 'execute')
  assert.equal(link.sent.at(-1).data.token, token)
  link.reply({ mapped: windows, available: [], controlDirections: {}, status: 'dispatched', controlPending: true })
  assert.equal(link.page.displayRows[0].first.label, '关窗')
  link.advance(1500)
  link.reply(openActions)
  assert.equal(link.page.displayRows[0].first.label, '窗微开')
})

test('cached and offline replies clear directional labels and availability', () => {
  const link = start()
  link.hello()
  const mapped = ['window_open', 'climate', 'lock']
  const controls = { mapped, available: mapped, controlDirections: { window_open: 'close', climate: 'close', lock: 'lock' } }
  link.reply(controls)
  assert.equal(link.page.displayRows[0].first.label, '关窗')
  link.page.refresh()
  link.reply({ ...controls, vehicleCached: true })
  assert.equal(link.page.displayRows[0].first.label, '窗全开')
  assert.equal(link.page.displayRows[0].second.label, '空调')
  assert.equal(link.page.displayRows[1].first.label, '车锁')
  assert.match(link.page.displayRows[0].first.style, /paused/)
  assert.deepEqual(Array.from(link.page.available), [])
  link.page.syncStatus()
  link.reply(controls)
  link.connection.onclose()
  assert.equal(link.page.displayRows[0].second.label, '空调')
  assert.deepEqual(Array.from(link.page.available), [])
})

test('live window and switch tiles request an upgrade when the phone cannot check the direction', () => {
  const link = start()
  link.hello({ phoneApp: '0.1.16' })
  const mapped = ['window_vent', 'climate']
  link.reply({ mapped, available: mapped, controlDirections: undefined })
  assert.equal(link.page.displayRows[0].first.hint, '暂不可用')
  assert.equal(link.page.displayRows[0].second.hint, '暂不可用')
  link.page.select('window_vent')
  assert.equal(link.page.toastText, '请更新手机端后使用此项车控')
  link.page.select('climate')
  assert.deepEqual(link.types(), ['hello', 'status'])
})

test('lock tiles carry the displayed action into prepare with both new and legacy phone replies', () => {
  const link = start()
  link.hello()
  const mapped = ['lock']
  for (const [lockState, direction, label, map] of [
    ['locked', 'unlock', '解锁', { lock: 'unlock' }], ['unlocked', 'lock', '上锁', undefined]
  ]) {
    link.reply({ mapped, available: mapped, lockState, controlDirections: map })
    assert.equal(link.page.displayRows[0].first.label, label)
    link.page.select('lock')
    assert.equal(link.sent.at(-1).data.controlDirection, direction)
  }
  link.reply({ mapped, available: mapped, lockState: 'locked', controlDirections: undefined,
    status: 'confirm', action: 'lock', token: 'a'.repeat(36), message: '解锁车门' })
  assert.equal(link.page.confirmTitle, '')
  assert.equal(link.page.displayRows[0].first.label, '解锁')
  assert.match(link.page.toastText, /按钮状态已变化/)
})

for (const [action, shortName, fullName] of [
  ['climate', '空调', '空调'], ['purify', '净化', '净化'], ['heat', '升温', '极速升温'],
  ['cool', '降温', '极速降温'], ['defrost', '除霜', '除霜'], ['sentry', '哨兵', '哨兵模式']
]) {
  test(`${action} ties both tile labels and confirmation prompts to the requested direction`, () => {
    const link = start()
    link.hello()
    const mapped = [action]
    for (const [direction, tilePrefix, confirmPrefix] of [['open', '开', '开启'], ['close', '关', '关闭']]) {
      const controls = { mapped, available: mapped, controlDirections: { [action]: direction } }
      link.reply(controls)
      assert.equal(link.page.displayRows[0].first.label, tilePrefix + shortName)
      link.page.select(action)
      assert.equal(link.sent.at(-1).data.controlDirection, direction)
      const token = 'c'.repeat(36)
      const message = confirmPrefix + fullName
      link.reply({ ...controls, status: 'confirm', action, token, message })
      assert.equal(link.page.confirmTitle, message)
      link.page.execute()
      assert.equal(link.sent.at(-1).data.type, 'execute')
      assert.equal(link.sent.at(-1).data.token, token)
      assert.equal(link.sent.at(-1).data.controlDirection, undefined, 'The ticket binds the execution direction')
    }
    link.reply({ mapped, available: mapped, controlDirections: { [action]: 'toggle' },
      blocked: { [action]: '请在手机核对开关状态' } })
    assert.equal(link.page.displayRows[0].first.hint, '暂不可用')
    const before = link.sent.length
    link.page.select(action)
    assert.equal(link.sent.length, before)
    assert.match(link.page.toastText, /核对开关状态/)
  })
}

test('only a failed read send retries after 500 ms with the original request ID', () => {
  for (const type of ['status', 'refresh']) {
    const link = start()
    link.hello()
    if (type === 'refresh') {
      link.reply()
      link.page.refresh()
    }
    const first = link.sent.at(-1)
    assert.equal(first.data.type, type)
    const count = link.sent.length
    first.fail()
    first.fail()
    link.advance(499)
    assert.equal(link.sent.length, count)
    link.advance(1)
    const second = link.sent.at(-1)
    assert.equal(link.sent.length, count + 1)
    assert.equal(second.data.id, first.data.id)
    first.fail()
    second.fail()
    assert.equal(link.page.modeLabel, '离线')
    link.advance(501)
    assert.equal(link.sent.length, count + 2)
    assert.equal(link.types().at(-1), 'hello')
    assert.equal(link.sent.filter(message => message.data.id === first.data.id).length, 2)
  }
})

test('a reply, a superseding request, or leaving the screen cancels a pending read retry', () => {
  for (const resolve of [link => link.reply(), link => link.page.refresh(), link => link.page.onHide()]) {
    const link = start()
    link.hello()
    link.sent.at(-1).fail()
    resolve(link)
    const count = link.sent.length
    link.advance(500)
    assert.equal(link.sent.length, count)
  }
})

test('prepare, execute, and cancel each get one attempt on send failure or exception', () => {
  for (const type of ['prepare', 'execute', 'cancel']) {
    for (const throwInstead of [false, true]) {
      const link = start({ send: message => {
        if (throwInstead && message.data.type === type) throw new Error('transport down')
      } })
      link.hello()
      link.reply()
      if (type === 'prepare') link.page.select('climate')
      else {
        confirmClimate(link)
        link.page[type]()
      }
      const failed = link.sent.at(-1)
      assert.equal(failed.data.type, type)
      if (!throwInstead) failed.fail()
      assert.equal(link.page.modeLabel, '离线')
      if (type === 'execute') assert.match(link.page.toastText, /结果待核对/)
      link.advance(500)
      assert.equal(link.types().filter(sentType => sentType === type).length, 1)
    }
  }
})

test('read response deadlines stay at 8 and 13 seconds and do not trigger a resend', () => {
  for (const [type, timeout] of [['status', 8000], ['refresh', 13000]]) {
    const link = start()
    link.hello()
    if (type === 'refresh') {
      link.reply()
      link.page.refresh()
    }
    const count = link.sent.length
    link.advance(timeout - 1)
    assert.equal(link.page.pending.type, type)
    link.advance(1)
    assert.equal(link.page.pending.type, 'hello')
    assert.equal(link.page.modeLabel, '离线')
    assert.equal(link.sent.length, count + 1)
  }
})

test('the event ring restores history, caps persistence at 20, and shows the latest three rows', () => {
  let restore
  let stored
  const writes = []
  const link = start({ native: { '@app-module/system.storage': {
    get({ key, success }) {
      if (key === 'connection-events') restore = success
      else success('')
    },
    set({ key, value, complete }) {
      assert.equal(key, 'connection-events')
      stored = JSON.parse(value)
      writes.push(complete)
    }
  } } })
  link.hello()
  link.reply()
  assert.equal(writes.length, 0)
  restore(JSON.stringify([{ ts: 123, event: 'disconnect', id: 'old-id', type: 'status' }]))
  assert.deepEqual(stored.map(entry => entry.event), ['disconnect', 'send', 'recv', 'send', 'recv'])
  assert.equal(stored[0].id, 'old-id')
  confirmClimate(link)
  link.page.cancel()
  for (let i = 0; i < 10; i++) {
    link.page.syncStatus()
    link.reply()
  }
  link.page.syncStatus()
  const id = link.sent.at(-1).data.id
  link.advance(8000)
  assert.equal(writes.length, 1, 'Only one storage write may be in flight')
  writes.shift()()
  assert.equal(stored.length, 20)
  assert.deepEqual(stored.slice(-3).map(entry => [entry.event, entry.id]), [
    ['timeout', id], ['disconnect', id], ['send', link.sent.at(-1).data.id]
  ])
  assert.ok(stored.every(entry => typeof entry.ts === 'number' && !('token' in entry)))
  link.page.openSettings()
  assert.equal(link.page.settingsOpen, true)
  assert.equal(link.page.diagnosticLines.length, 3)
  assert.match(link.page.diagnosticLines[1], /disconnect status timeout/)
  assert.equal(link.page.onBackPress(), true)
  assert.equal(link.page.settingsOpen, false)
})

test('oversized replies are logged before parsing and 2048 UTF-8 bytes are accepted', () => {
  const link = start()
  link.hello()
  const id = link.sent.at(-1).data.id
  link.connection.onmessage({ data: '中'.repeat(683) })
  assert.equal(link.page.pending.id, id)
  assert.equal(link.page.events.at(-1).event, 'drop')
  assert.equal(link.page.events.at(-1).detail, 'too_large')
  const reply = { v: 1, id, type: 'reply', mode: 'live', status: 'ready', message: '' }
  const room = 2048 - Buffer.byteLength(JSON.stringify(reply), 'utf8')
  reply.message = '🙂'.repeat(Math.floor(room / 4)) + 'x'.repeat(room % 4)
  assert.equal(Buffer.byteLength(JSON.stringify(reply), 'utf8'), 2048)
  link.connection.onmessage({ data: { ...reply, message: reply.message + 'x' } })
  assert.equal(link.page.pending.id, id)
  assert.equal(link.page.events.at(-1).event, 'drop')
  link.receive(reply)
  assert.equal(link.page.pending, null)
  assert.equal(link.page.modeLabel, '已连接')
})

test('error-only envelopes report their cause immediately, including for execute', () => {
  for (const [error, message] of [['too_large', /2048 B/], ['internal', /手机端内部错误/]]) {
    const link = start()
    link.hello()
    link.reply()
    confirmClimate(link)
    link.page.execute()
    const id = link.sent.at(-1).data.id
    link.receive({ v: 1, type: 'reply', id: 'other-id', error })
    assert.equal(link.page.pending.id, id)
    link.receive({ v: 1, type: 'reply', id, error })
    assert.equal(link.page.pending, null)
    assert.equal(link.page.modeLabel, '离线')
    assert.match(link.page.toastText, message)
    assert.match(link.page.toastText, /结果待核对/)
    assert.equal(link.page.events.at(-2).detail, error)
    assert.equal(link.page.events.at(-1).event, 'disconnect')
  }
  const hello = start()
  hello.receive({ v: 1, type: 'reply', id: hello.sent[0].data.id, error: 'internal' })
  assert.equal(hello.page.pending, null)
  assert.match(hello.page.toastText, /手机端内部错误/)
  assert.deepEqual(hello.types(), ['hello'])
})

function hapticsFixture(values = new Map()) {
  const pulses = []
  const native = {
    '@app-module/system.storage': {
      get({ key, success }) { success(values.get(key) || '') },
      set({ key, value, complete }) {
        values.set(key, value)
        if (complete) complete()
      }
    },
    '@app-module/system.vibrator': {
      vibrate({ mode }) { pulses.push(mode) }
    }
  }
  return { native, pulses, values }
}

test('the confirmation prompt is silent and control tap, confirmation tap and dispatch each pulse once', () => {
  const fixture = hapticsFixture()
  const link = start(fixture)
  link.hello()
  link.reply()
  link.page.openSettings()
  link.page.closeSettings()
  link.page.toggleRange()
  link.advance(12000)
  link.reply({ controlPending: true, phoneConfirmation: true })
  link.advance(2500)
  link.reply()
  assert.deepEqual(fixture.pulses, [], 'polling and screen changes are silent')
  assert.equal(link.page.hapticsEnabled, true)
  link.page.select('climate')
  assert.deepEqual(fixture.pulses, ['short'], 'control tap pulses')
  link.reply(CLIMATE_CONFIRMATION)
  assert.equal(link.page.confirmTitle, CLIMATE_CONFIRMATION.message)
  assert.deepEqual(fixture.pulses, ['short'], 'opening the confirmation adds no pulse')
  link.page.execute()
  assert.deepEqual(fixture.pulses, ['short', 'short'])
  link.reply({ status: 'dispatched', controlPending: true, message: '已发送命令' })
  assert.equal(link.page.toastText, '指令已发送')
  assert.deepEqual(fixture.pulses, ['short', 'short', 'short'])
  link.advance(1500)
  link.reply({ phoneConfirmation: true })
  assert.equal(link.page.toastText, '请在手机确认银河操作')
  assert.equal(fixture.pulses.length, 3)
})

test('blocked controls, response timeouts and expired confirmations give two short pulses', () => {
  const actions = [
    link => {
      link.page.select('climate')
      return () => link.reply({ status: 'blocked' })
    },
    link => {
      confirmClimate(link)
      link.page.execute()
      return () => link.advance(8000)
    },
    link => {
      confirmClimate(link)
      return () => link.advance(13000)
    }
  ]
  for (const act of actions) {
    const fixture = hapticsFixture()
    const link = start(fixture)
    link.hello()
    link.reply()
    const fail = act(link)
    const before = fixture.pulses.length
    fail()
    assert.equal(fixture.pulses.length, before + 1)
    link.advance(199)
    assert.equal(fixture.pulses.length, before + 1)
    link.advance(1)
    assert.deepEqual(fixture.pulses.slice(before), ['short', 'short'])
  }
})

test('disabling haptics stops a pending pulse and persists across page creation', () => {
  const fixture = hapticsFixture()
  const link = start(fixture)
  link.hello()
  link.reply()
  link.page.select('climate')
  link.reply({ status: 'blocked' })
  assert.equal(fixture.pulses.length, 2)
  link.page.changeHaptics({ checked: false })
  link.advance(200)
  assert.equal(fixture.pulses.length, 2)
  assert.equal(fixture.values.get('control-haptics'), 'off')
  const reopened = start(fixture)
  assert.equal(reopened.page.hapticsEnabled, false)
  reopened.hello()
  reopened.reply()
  confirmClimate(reopened)
  reopened.page.execute()
  reopened.reply({ status: 'dispatched' })
  assert.equal(fixture.pulses.length, 2)
  reopened.page.changeHaptics({ checked: true })
  confirmClimate(reopened)
  assert.equal(fixture.pulses.length, 3)
})

test('a missing vibrator leaves confirmations and dispatch usable', () => {
  const link = start()
  link.hello()
  link.reply()
  confirmClimate(link)
  link.page.execute()
  link.reply({ status: 'dispatched', message: '已发送命令' })
  assert.equal(link.page.toastText, '指令已发送')
  assert.equal(link.page.vibrator, null)
})

test('layout profile adapts to Band 10 capsule, circular Watch 5, and Band 10 Pro rect', () => {
  const capsule = start({
    native: {
      '@app-module/system.device': {
        getInfo({ success }) {
          success({ screenWidth: 212, screenHeight: 520, screenShape: 'pill-shaped' })
        }
      }
    }
  })
  assert.equal(capsule.page.layoutProfile, 'capsule')
  assert.equal(capsule.page.ui.range, 'range range-capsule')
  assert.equal(capsule.page.ui.split, 'split split-capsule')
  assert.equal(capsule.page.ui.confirmation, 'confirmation confirmation-capsule')
  capsule.hello()
  capsule.reply({ mapped: ['lock', 'climate', 'window_vent', 'trunk'], available: ['climate'] })
  assert.equal(capsule.page.displayRows.length, 4)
  assert.ok(capsule.page.displayRows.every(row => row.second.id === '' && /full-tile/.test(row.first.style) && /tile-capsule/.test(row.first.style)))
  assert.match(capsule.page.displayRows[1].first.labelStyle, /tile-label-capsule/)

  for (const [deviceInfo, expectedHomeSize] of [
    [{ screenWidth: 480, screenHeight: 480, screenShape: 'circle' }, 'large'],
    [{ screenWidth: 466, screenHeight: 466 }, 'standard'],
    [{ screenWidth: 240, screenHeight: 240 }, 'compact']
  ]) {
    const circle = start({
      native: {
        '@app-module/system.device': {
          getInfo({ success }) { success(deviceInfo) }
        }
      }
    })
    assert.equal(circle.page.layoutProfile, 'circle')
    assert.equal(circle.page.watchHomeSize, expectedHomeSize)
    assert.equal(circle.page.ui.homeScreen, 'home-screen home-screen-circle')
    assert.equal(circle.page.ui.confirmation, 'confirmation confirmation-circle')
    assert.equal(
      circle.page.watchHomeUi.car,
      expectedHomeSize === 'standard' ? 'watch-car' : 'watch-car watch-car-' + expectedHomeSize
    )
    circle.hello()
    circle.reply({ mapped: ['lock', 'climate', 'window_vent', 'trunk'], available: ['climate'] })
    assert.equal(circle.page.displayRows.length, 2)
    assert.match(circle.page.displayRows[0].first.style, /tile-circle/)
    assert.match(circle.page.displayRows[0].second.style, /tile-circle/)
    assert.equal(circle.page.displayRows[0].first.watchOrbStyle, 'watch-orb watch-orb-paused')
    assert.equal(circle.page.displayRows[0].second.watchOrbStyle, 'watch-orb watch-orb-ready')
    assert.equal(circle.page.watchPage, 0)
    circle.page.onWatchPageChange({ index: 1 })
    assert.equal(circle.page.watchPage, 1)
    circle.page.setWatchPage(0)
    assert.equal(circle.page.watchPage, 0)
  }

  const rect = start({ native: {
    '@app-module/system.device': { getInfo({ success }) { success({ screenWidth: 336, screenHeight: 480, screenShape: 'rect' }) } }
  } })
  assert.equal(rect.page.layoutProfile, 'rect')
  assert.equal(rect.page.ui.range, 'range')
  rect.hello()
  rect.reply({ mapped: ['lock', 'climate', 'window_vent', 'trunk'], available: ['climate'] })
  assert.equal(rect.page.displayRows.length, 4)
  assert.equal(rect.page.displayRows[0].first.labelStyle, 'tile-label')
})

test('car image sync pulls chunks when hash changes, caches via system.file, and yields to user controls', () => {
  const files = new Map()
  const storage = new Map()
  const pngBytes = Buffer.from([0x89, 0x50, 0x4e, 0x47, 1, 2, 3, 4, 5, 6, 7, 8])
  const b64 = pngBytes.toString('base64')
  const part0 = b64.slice(0, 8)
  const part1 = b64.slice(8)
  const link = start({
    native: {
      '@app-module/system.storage': {
        get({ key, success }) { success(storage.get(key) || '') },
        set({ key, value, complete }) { storage.set(key, value); if (complete) complete() }
      },
      '@app-module/system.file': {
        writeArrayBuffer({ uri, buffer, success }) {
          files.set(uri, Buffer.from(buffer))
          success()
        },
        delete({ uri }) { files.delete(uri) }
      }
    }
  })
  link.hello({ features: ['confirm', 'cancel', 'refresh', 'car_image'] })
  link.reply({ carImageHash: 'carhash01234', carImageChunks: 2 })
  assert.equal(link.sent.at(-1).data.type, 'car_image')
  assert.equal(link.sent.at(-1).data.hash, 'carhash01234')
  assert.equal(link.sent.at(-1).data.index, 0)
  assert.equal(link.page.busy, false)

  link.receive({ v: 1, id: link.sent.at(-1).data.id, type: 'car_image_reply', hash: 'carhash01234', index: 0, total: 2, data: part0 })
  assert.equal(link.sent.at(-1).data.type, 'car_image')
  assert.equal(link.sent.at(-1).data.index, 1)

  link.receive({ v: 1, id: link.sent.at(-1).data.id, type: 'car_image_reply', hash: 'carhash01234', index: 1, total: 2, data: part1 })
  assert.equal(link.page.carImageSrc, 'internal://files/car_carhash01234.png')
  assert.deepEqual(files.get('internal://files/car_carhash01234.png'), pngBytes)

  link.page.syncStatus()
  link.reply({ carImageHash: 'newhash99999', carImageChunks: 1 })
  assert.equal(link.sent.at(-1).data.type, 'car_image')
  link.page.select('climate')
  assert.equal(link.sent.at(-1).data.type, 'prepare', 'User control must supersede background car_image fetch')
  link.reply(CLIMATE_CONFIRMATION)
  link.page.cancel()

  link.page.onCarImageError()
  assert.equal(link.page.carImageSrc, '/common/images/starshine-8.png')
})

test('vehicle name defaults to generic label, syncs from phone, persists in snapshot, and circular orbs adapt to count and active state', () => {
  const storage = new Map()
  const circle = start({
    native: {
      '@app-module/system.device': {
        getInfo({ success }) {
          success({ screenWidth: 480, screenHeight: 480, screenShape: 'circle' })
        }
      },
      '@app-module/system.storage': {
        get({ key, success }) { success(storage.get(key) || '') },
        set({ key, value, complete }) { storage.set(key, value); if (complete) complete() }
      }
    }
  })
  assert.equal(circle.page.vehicleName, '我的座驾')
  circle.hello()
  circle.reply({
    vehicleName: '  俊哥的领克08  ',
    vehicle: { totalKm: 470, electricKm: 120, fuelKm: 350, motion: '已驻车', updatedAt: 1700000000000, updatedLabel: '20:00' },
    mapped: ['lock'],
    available: ['lock'],
    controlDirections: { lock: 'unlock' }
  })
  assert.equal(circle.page.vehicleName, '俊哥的领克08')
  assert.equal(circle.page.displayRows[0].first.watchOrbSizeStyle, 'watch-orb-hero')
  assert.equal(circle.page.displayRows[0].first.watchOrbStyle, 'watch-orb watch-orb-ready')

  circle.page.syncStatus()
  circle.reply({
    mapped: ['lock', 'climate'],
    available: ['lock', 'climate'],
    controlDirections: { lock: 'lock', climate: 'close' }
  })
  assert.equal(circle.page.displayRows[0].first.watchOrbSizeStyle, 'watch-orb-pair')
  assert.equal(circle.page.displayRows[0].first.watchOrbStyle, 'watch-orb watch-orb-ready watch-orb-active')
  assert.equal(circle.page.displayRows[0].second.watchOrbStyle, 'watch-orb watch-orb-ready watch-orb-active')

  circle.page.syncStatus()
  circle.reply({
    vehicleName: '   ',
    mapped: ['lock', 'climate', 'window_vent', 'trunk'],
    available: ['lock'],
    controlDirections: { lock: 'unlock' }
  })
  assert.equal(circle.page.vehicleName, '我的座驾')
  assert.equal(circle.page.displayRows[0].first.watchOrbSizeStyle, 'watch-orb-grid')

  const reopened = start({
    native: {
      '@app-module/system.device': {
        getInfo({ success }) { success({ screenWidth: 480, screenHeight: 480, screenShape: 'circle' }) }
      },
      '@app-module/system.storage': {
        get({ key, success }) { success(storage.get(key) || '') },
        set({ key, value, complete }) { storage.set(key, value); if (complete) complete() }
      }
    }
  })
  assert.equal(reopened.page.vehicleName, '我的座驾')
})

test('energy readings fall back to battery percentage when kilometers are unavailable', () => {
  const link = start()
  link.hello()
  link.reply({
    vehicle: { totalKm: 643, electricPercent: 97, fuelKm: 517, fuelPercent: 48, motion: '已驻车', updatedAt: 1700000000000, updatedLabel: '20:20' }
  })
  assert.equal(link.page.total, '643')
  assert.equal(link.page.totalUnit, '\u00a0km')
  assert.equal(link.page.electric, '97')
  assert.equal(link.page.electricUnit, '%')
  assert.equal(link.page.fuel, '517')
  assert.equal(link.page.fuelUnit, '\u00a0km')
  assert.deepEqual(Array.from(link.page.rangeBreakdown, ({ id, value }) => [id, value]), [['electric', '97%'], ['fuel', '517\u00a0km']])

  link.page.syncStatus()
  link.reply({
    vehicle: { electricPercent: 85, motion: '充电中', updatedAt: 1700000060000, updatedLabel: '20:21' }
  })
  assert.equal(link.page.total, '85')
  assert.equal(link.page.totalUnit, '%')
  assert.equal(link.page.electric, '85')
  assert.equal(link.page.electricUnit, '%')
  assert.deepEqual(Array.from(link.page.rangeBreakdown, ({ id, value }) => [id, value]), [['electric', '85%']])
})
