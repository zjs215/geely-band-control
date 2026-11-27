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
    ...(options.now === undefined ? {} : { Date: class extends Date {
      constructor(...args) { super(...(args.length ? args : [options.now + clock])) }
      static now() { return options.now + clock }
    } }),
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
    [{ phoneApp: '0.1.9' }, '请更新手机端到配套版本']
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
  link.page.setWatchPage(1)
  link.page.select('window_vent')
  assert.equal(link.sent.at(-1).data.controlDirection, 'close')
  const token = 'b'.repeat(36)
  link.reply({ ...closeAction, status: 'confirm', action: 'window_vent', message: '关闭车窗', token })
  assert.equal(link.page.confirmTitle, '关闭车窗')
  assert.equal(link.page.themeConfirmIcon, '/common/confirm-icons/night/window_vent.png')
  link.page.setWatchPage(0)
  assert.equal(link.page.watchPage, 1)
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
  assert.equal(link.page.displayRows[0].third.label, '车锁')
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

test('Galaxy and Lynk keep loading and unknown slots in place and cannot prepare them', () => {
  for (const [brand, ids] of [
    ['GALAXY', ['climate', 'heat', 'trunk', 'find']],
    ['LYNKCO', ['lynk_unlock', 'lynk_window', 'lynk_kjck', 'lynk_ccqk']]
  ]) {
    const link = start()
    link.hello({ brand })
    link.reply({ brand, mapped: ids, available: ids, controlStates: { [ids[0]]: 'loading', [ids[1]]: 'unknown' } })
    const tiles = link.page.displayRows.flatMap(row => [row.first, row.second]).filter(tile => tile.id)
    assert.deepEqual(Array.from(tiles, tile => tile.id), ids)
    assert.deepEqual(Array.from(tiles.slice(0, 2), tile => tile.hint), ['加载中', '状态未知'])
    assert.equal(tiles[0].displayLabel, '加载中')
    assert.equal(tiles[0].displayHint, '')
    assert.equal(tiles[1].displayHint, '状态未知')
    const count = link.sent.length
    link.page.select(ids[0])
    link.page.select(ids[1])
    assert.equal(link.sent.length, count)
    assert.equal(link.page.toastText, '状态未知')
  }
})

test('a compact execution acknowledgment shows the result while a separate request reads vehicle state', () => {
  for (const status of ['dispatched', 'unknown']) {
    const link = start()
    link.hello({ features: ['confirm', 'cancel', 'refresh', 'compact_execute_ack'] })
    link.reply()
    confirmClimate(link)
    link.page.execute()
    const { id, compactAck } = link.sent.at(-1).data
    assert.equal(compactAck, true)
    link.receive({ v: 1, type: 'execute_ack', id: 'other-id', status })
    assert.equal(link.page.pending.id, id)
    link.receive({ v: 1, type: 'execute_ack', id, status })
    assert.deepEqual(link.types().slice(-2), ['execute', 'status'])
    assert.equal(link.page.controlPending, true)
    const receipt = link.page.toastText
    assert.match(receipt, status === 'dispatched' ? /指令已发送/ : /结果待核对/)
    link.reply()
    assert.equal(link.page.toastText, receipt)
  }
})

test('disconnect reconnects immediately, backs off, and clears the old confirmation', () => {
  const link = start()
  link.hello()
  link.reply()
  confirmClimate(link)
  assert.ok(link.page.confirmToken)
  link.connection.onclose()
  assert.equal(link.page.confirmToken, '')
  link.advance(0)
  assert.equal(link.types().at(-1), 'hello')
  link.connection.onclose()
  const count = link.sent.length
  link.advance(999)
  assert.equal(link.sent.length, count)
  link.advance(1)
  assert.equal(link.types().at(-1), 'hello')
  link.receive({ v: 1, type: 'hello-reply', id: link.sent.at(-1).data.id,
    protocol: 1, phoneApp: manifest.versionName, features: ['confirm', 'cancel'] })
  assert.equal(link.types().at(-1), 'status')
  link.reply()
  assert.equal(link.page.reconnectAttempt, 0)
  assert.ok(!link.types().includes('execute'))
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

test('layout keeps two columns for round and square displays and one for tall displays', () => {
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
  assert.equal(capsule.page.ui.confirmActions, 'confirm-actions confirm-actions-capsule')
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
    assert.equal(circle.page.ui.confirmActions, 'confirm-actions confirm-actions-circle')
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

  for (const [width, height, profile, count] of [
    [400, 400, 'square', 2], [336, 396, 'rect', 4], [336, 480, 'rect', 4], [192, 490, 'capsule', 4]
  ]) {
    const link = start({ native: {
      '@app-module/system.device': { getInfo({ success }) { success({ screenWidth: width, screenHeight: height, screenShape: 'rect' }) } }
    } })
    assert.equal(link.page.layoutProfile, profile)
    if (profile === 'rect' || profile === 'square') assert.equal(link.page.ui.confirmActions, 'confirm-actions')
    assert.equal(link.page.useTwoPageShell, profile === 'square')
    link.hello()
    const mapped = ['lock', 'climate', 'window_vent', 'trunk']
    link.reply({ mapped, available: ['climate'] })
    assert.equal(link.page.displayRows.length, count)
    assert.deepEqual(Array.from(link.page.displayRows).flatMap(row => [row.first.id, row.second.id].filter(Boolean)), mapped)
  }
  assert.equal(start().page.layoutProfile, 'square')
})

test('rect profile can toggle 2-page watch mode in Settings and persists across reopen', () => {
  const fixture = hapticsFixture()
  fixture.native['@app-module/system.device'] = {
    getInfo({ success }) { success({ screenWidth: 336, screenHeight: 480, screenShape: 'rect' }) }
  }
  const sourceUx = readFileSync(path.join(__dirname, '../src/pages/garage/index.ux'), 'utf8')
  assert.match(sourceUx, /if="\{\{ canChangeDisplay \}\}">[\s\S]*双屏显示/)
  assert.match(sourceUx, /if="\{\{ homeLayoutReady && !settingsOpen && useTwoPageShell \}\}"/)
  assert.match(sourceUx, /class="confirm-overlay" if="\{\{ confirmTitle \}\}"/)
  assert.match(sourceUx, /@click="execute">确认<\/text>\s*<text[^>]+@click="cancel">取消<\/text>/)

  const link = start(fixture)
  assert.equal(link.page.layoutProfile, 'rect')
  assert.equal(link.page.rectTwoPage, false)
  assert.equal(link.page.useTwoPageShell, false)
  link.hello()
  link.reply({ mapped: ['lock', 'climate'], available: ['climate'] })
  assert.equal(link.page.displayRows.length, 2)
  assert.match(link.page.displayRows[0].first.style, /tile-rect/)
  assert.match(link.page.displayRows[0].first.watchCellStyle, /watch-cell-single watch-cell-rect/)
  assert.equal(link.page.displayRows[0].first.watchCellCopyStyle, 'watch-control-copy watch-control-copy-single')
  assert.equal(link.page.displayRows[1].first.watchCellCopyStyle, link.page.displayRows[0].first.watchCellCopyStyle)
  assert.equal(link.page.displayRows[0].second.id, '')
  assert.equal(link.page.displayRows[0].first.watchOrbSizeStyle, 'watch-orb-single')

  link.page.openSettings()
  link.page.changeRectTwoPage({ checked: true })
  assert.equal(link.page.rectTwoPage, true)
  assert.equal(link.page.useTwoPageShell, true)
  assert.equal(link.page.displayRows.length, 2)
  assert.equal(link.page.displayRows[1].first.id, 'climate')
  assert.equal(JSON.parse(fixture.values.get('rect-two-page')).twoPage, true)
  link.page.closeSettings()

  link.page.onWatchPageChange({ index: 1 })
  assert.equal(link.page.watchPage, 1)
  assert.deepEqual(fixture.pulses, ['short'])
  link.page.setWatchPage(0)
  assert.equal(link.page.watchPage, 0)
  link.page.toggleRange()
  assert.equal(link.page.showTotalRange, true)

  const reopened = start(fixture)
  assert.equal(reopened.page.layoutProfile, 'rect')
  assert.equal(reopened.page.rectTwoPage, true)
  assert.equal(reopened.page.useTwoPageShell, true)

  reopened.page.changeRectTwoPage({ checked: false })
  assert.equal(reopened.page.rectTwoPage, false)
  assert.equal(reopened.page.useTwoPageShell, false)
  assert.equal(reopened.page.watchPage, 0)
  assert.equal(JSON.parse(fixture.values.get('rect-two-page')).twoPage, false)
})

test('startup waits for the saved tall-screen mode before exposing the home layout', () => {
  let receiveDevice
  let receiveDisplay
  const link = start({ native: {
    '@app-module/system.device': { getInfo({ success }) { receiveDevice = success } },
    '@app-module/system.storage': {
      get({ key, success }) {
        if (key === 'rect-two-page') receiveDisplay = success
        else success('')
      },
      set() {}
    }
  } })
  assert.equal(link.page.homeLayoutReady, false)
  receiveDevice({ screenWidth: 336, screenHeight: 396, screenShape: 'rect' })
  assert.equal(link.page.homeLayoutReady, false)
  receiveDisplay('on')
  assert.equal(link.page.homeLayoutReady, true)
  assert.equal(link.page.useTwoPageShell, true)
  assert.match(link.page.watchControlsUi.settingsPill, /watch-settings-pill-short/)
  assert.equal(link.page.total, '—')
  link.hello()
  link.reply({ vehicle: { totalKm: 298, updatedAt: 1795482900000 } })
  assert.equal(link.page.total, '298')
  assert.equal(link.page.useTwoPageShell, true)
  assert.match(link.page.displayRows[0].first.watchCellStyle, /watch-cell-rect-short/)
})

test('phone and rectangular band synchronize display choices without replacing a newer phone change', () => {
  const fixture = hapticsFixture()
  fixture.values.set('rect-two-page', 'on')
  fixture.native['@app-module/system.device'] = {
    getInfo({ success }) { success({ screenWidth: 336, screenHeight: 480, screenShape: 'rect' }) }
  }
  const link = start(fixture)
  link.hello({ features: ['confirm', 'band_display'], bandDisplay: { twoPage: false, revision: 0 } })
  assert.equal(link.sent.at(-1).data.bandDisplay.twoPage, true)
  assert.equal(link.sent.at(-1).data.bandDisplay.revision, 0)
  link.reply({ bandDisplay: { twoPage: true, revision: 1 } })
  assert.equal(link.page.useTwoPageShell, true)
  link.page.syncStatus()
  link.reply({ bandDisplay: { twoPage: false, revision: 2 } })
  assert.equal(link.page.useTwoPageShell, false)

  link.page.changeRectTwoPage({ checked: true })
  assert.equal(link.sent.at(-1).data.bandDisplay.revision, 2)
  link.page.changeRectTwoPage({ checked: false })
  link.reply({ bandDisplay: { twoPage: true, revision: 3 } })
  assert.equal(link.page.useTwoPageShell, false)
  link.page.syncStatus()
  assert.equal(link.sent.at(-1).data.bandDisplay.revision, 3)
  assert.equal(link.sent.at(-1).data.bandDisplay.twoPage, false)
  link.reply({ bandDisplay: { twoPage: false, revision: 4 } })
  assert.deepEqual(JSON.parse(fixture.values.get('rect-two-page')), { twoPage: false, revision: 4, pending: false })
})

test('second-page upward pull refreshes once on release and leaves down gestures to the pager', () => {
  const link = start()
  link.hello()
  link.reply()
  const touch = (x, y, identifier = 0) => ({ clientX: x, clientY: y, identifier })
  const begin = () => link.page.startControlsPull({ touches: [touch(160, 260)] })
  const move = (x, y) => link.page.moveControlsPull({ touches: [touch(x, y)] })
  const end = (x, y) => link.page.endControlsPull({ changedTouches: [touch(x, y)] })
  const before = link.sent.length
  begin()
  end(160, 180)
  link.page.setWatchPage(1)
  begin()
  move(163, 230)
  assert.equal(link.page.controlsPullText, '上拉刷新')
  assert.ok(link.page.controlsPullOffset < 0 && link.page.controlsPullProgress > 0)
  end(163, 230)
  begin()
  end(160, 334)
  begin()
  end(260, 180)
  assert.equal(link.sent.length, before)
  begin()
  move(164, 184)
  assert.equal(link.page.controlsPullText, '松手刷新')
  assert.equal(link.page.controlsPullProgress, 100)
  assert.deepEqual(JSON.parse(link.page.controlsPullTransform), { translateY: '-19px' })
  assert.equal(link.sent.length, before)
  end(164, 184)
  assert.equal(link.page.watchPage, 1)
  assert.equal(link.sent.at(-1).data.type, 'refresh')
  assert.equal(link.page.controlsPullText, '')
  assert.equal(link.page.controlsPullOffset, 0)
  assert.deepEqual(JSON.parse(link.page.controlsPullTransform), { translateY: '0px' })
  assert.equal(link.page.refreshing, true)
  end(164, 184)
  assert.equal(link.sent.length, before + 1)
  link.reply()
  assert.equal(link.page.refreshing, false)
  begin()
  move(164, 184)
  link.page.onWatchPageChange({ index: 0 })
  end(164, 184)
  assert.equal(link.sent.length, before + 1)
})

test('capsule uses phone themes without a local editor and keeps the theme behind the controls', () => {
  const fixture = hapticsFixture()
  fixture.native['@app-module/system.device'] = {
    getInfo({ success }) { success({ screenWidth: 212, screenHeight: 520, screenShape: 'pill-shaped' }) }
  }
  const link = start(fixture)
  const fixed = { themeId: 'coast', mode: 'fixed', variant: 'day', dayStartMinute: 360, nightStartMinute: 1080 }
  link.hello({ features: ['confirm', 'band_display'], bandDisplay: { twoPage: true, revision: 1 }, theme: fixed })
  link.reply()
  link.page.openThemeEditor()
  assert.equal(link.page.themeEditorOpen, false)
  assert.equal(link.page.useTwoPageShell, true)
  assert.match(link.page.themeArt, /capsule\/coast-day\.jpg$/)
  assert.equal(link.page.themeDay, true)
  link.page.syncStatus()
  link.reply({ theme: { ...fixed, variant: 'night' } })
  assert.match(link.page.themeArt, /capsule\/coast-night\.jpg$/)
  assert.equal(link.page.themeDay, false)
  link.page.receiveTheme({ ...fixed, themeId: 'alpine', variant: 'night' })
  assert.match(link.page.themeArt, /capsule\/alpine-night\.jpg$/)
  assert.equal(link.page.themeDay, false)
  const source = readFileSync(path.join(__dirname, '../src/pages/garage/index.ux'), 'utf8')
  assert.match(source, /<stack class="page">\s*<stack class="theme-layer">[\s\S]*?<\/stack>\s*<div class="home-layout-pending"/)
  assert.doesNotMatch(source, /theme-scrim|theme-active-(day|night)/)
})

test('themes switch at a local clock boundary and persist an independent fixed choice offline', () => {
  const fixture = hapticsFixture()
  fixture.now = new Date(2026, 10, 26, 17, 59, 59).getTime()
  const link = start(fixture)
  const phone = { themeId: 'sky', mode: 'schedule', variant: 'day', dayStartMinute: 360, nightStartMinute: 1080 }
  link.hello({ theme: phone })
  link.reply()
  assert.match(link.page.themeArt, /square\/sky-day\.jpg$/)
  link.advance(900)
  assert.match(link.page.themeArt, /square\/sky-night\.jpg$/)

  link.page.openSettings()
  link.page.openThemeEditor()
  assert.equal(link.page.themeDraftChoice, 0)
  link.page.changeThemeChoice({ newSelected: 3 })
  assert.equal(link.page.themeTimesExpanded, false)
  link.page.toggleThemeTimes()
  assert.equal(link.page.themeTimesExpanded, true)
  link.page.changeThemeNight({ hour: '06', minute: '00' })
  link.page.saveThemeEditor()
  assert.ok(link.page.themeEditorError)
  link.page.changeThemeDay({ hour: '23', minute: '30' })
  link.page.changeThemeNight({ hour: '06', minute: '15' })
  link.page.saveThemeEditor()
  link.page.refreshTheme(new Date(2026, 10, 27, 5, 59))
  assert.match(link.page.themeArt, /ridge-day\.jpg$/)
  link.page.refreshTheme(new Date(2026, 10, 27, 6, 15))
  assert.match(link.page.themeArt, /ridge-night\.jpg$/)

  link.page.openThemeEditor()
  link.page.changeThemeMode({ newSelected: 2 })
  assert.equal(link.page.themeTimesExpanded, false)
  link.page.saveThemeEditor()
  link.page.receiveTheme({ ...phone, themeId: 'city' })
  assert.match(link.page.themeArt, /ridge-night\.jpg$/)
  assert.equal(link.page.themeTimer, null)
  const reopened = start(fixture)
  assert.match(reopened.page.themeArt, /ridge-night\.jpg$/)
  reopened.page.openSettings()
  reopened.page.openThemeEditor()
  reopened.page.changeThemeChoice({ newSelected: 0 })
  reopened.page.saveThemeEditor()
  assert.match(reopened.page.themeArt, /city-day\.jpg$/)
  reopened.page.receiveTheme({ ...phone, themeId: 'unknown' })
  assert.match(reopened.page.themeArt, /city-day\.jpg$/)
  reopened.page.onHide()
  assert.equal(reopened.page.themeTimer, null)
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

test('startup loads cached car image directly without flashing the bundled default car image', () => {
  const storage = new Map([
    ['vehicle-snapshot', JSON.stringify({ brand: 'GALAXY', updatedAt: 1700000000000, totalKm: 520 })],
    ['car-image-cache', JSON.stringify({ hash: 'cached777', uri: 'internal://files/car_cached777.png', brand: 'GALAXY' })]
  ])
  let deliverImageCache = null
  const link = start({
    native: {
      '@app-module/system.storage': {
        get({ key, success }) {
          if (key === 'car-image-cache') {
            deliverImageCache = () => success(storage.get(key) || '')
            return
          }
          success(storage.get(key) || '')
        },
        set({ key, value, complete }) { storage.set(key, value); if (complete) complete() }
      }
    }
  })
  assert.equal(link.page.carImageSrc, '')
  deliverImageCache()
  assert.equal(link.page.carImageSrc, 'internal://files/car_cached777.png')
  assert.equal(link.page.carImageHash, 'cached777')

  const lynkStorage = new Map([
    ['vehicle-snapshot', JSON.stringify({ brand: 'LYNKCO', updatedAt: 1700000000000, totalKm: 410 })],
    ['car-image-cache', JSON.stringify({ hash: 'lynk888', uri: 'internal://files/car_lynk888.png', brand: 'LYNKCO' })]
  ])
  let deliverSnapshot = null
  const lynk = start({
    native: {
      '@app-module/system.storage': {
        get({ key, success }) {
          if (key === 'vehicle-snapshot') {
            deliverSnapshot = () => success(lynkStorage.get(key) || '')
            return
          }
          success(lynkStorage.get(key) || '')
        },
        set({ key, value, complete }) { lynkStorage.set(key, value); if (complete) complete() }
      }
    }
  })
  assert.equal(lynk.page.carImageSrc, '')
  deliverSnapshot()
  assert.equal(lynk.page.brand, 'LYNKCO')
  assert.equal(lynk.page.carImageSrc, 'internal://files/car_lynk888.png')

  let deliverCircleCache = null
  const circle = start({
    native: {
      '@app-module/system.device': {
        getInfo({ success }) { success({ screenWidth: 480, screenHeight: 480, screenShape: 'circle' }) }
      },
      '@app-module/system.storage': {
        get({ key, success }) {
          if (key === 'car-image-cache') {
            deliverCircleCache = () => success(storage.get(key) || '')
            return
          }
          success(storage.get(key) || '')
        },
        set({ key, value, complete }) { storage.set(key, value); if (complete) complete() }
      }
    }
  })
  assert.equal(circle.page.homeLayoutReady, false)
  assert.equal(circle.page.carImageSrc, '')
  deliverCircleCache()
  assert.equal(circle.page.homeLayoutReady, true)
  assert.equal(circle.page.carImageSrc, 'internal://files/car_cached777.png')
})

test('both home layouts keep a vehicle image when themes change and recover a broken cache', () => {
  const fallback = '/common/images/starshine-8.png'
  const cached = 'internal://files/car_theme888.png'
  const fixture = hapticsFixture()
  fixture.values.set('vehicle-snapshot', JSON.stringify({ brand: 'LYNKCO', updatedAt: 1700000000000, totalKm: 207 }))
  fixture.values.set('car-image-cache', JSON.stringify({ hash: 'theme888', uri: cached, brand: 'LYNKCO' }))
  fixture.native['@app-module/system.device'] = {
    getInfo({ success }) { success({ screenWidth: 336, screenHeight: 480, screenShape: 'rect' }) }
  }
  const link = start(fixture)
  const theme = { themeId: 'coast', mode: 'fixed', variant: 'day', dayStartMinute: 360, nightStartMinute: 1080 }
  link.hello({ brand: 'LYNKCO', features: ['confirm', 'car_image'], theme })
  link.reply({ brand: 'LYNKCO' })
  assert.equal(link.page.displayCarImage, cached)
  assert.equal(link.page.themeTileClass, ' theme-tile-day')
  link.page.changeRectTwoPage({ checked: true })
  link.page.receiveTheme({ ...theme, themeId: 'alpine', variant: 'night' })
  assert.equal(link.page.displayCarImage, cached)
  assert.equal(link.page.themeTileClass, ' theme-tile-night')
  link.page.onCarImageError()
  assert.equal(link.page.displayCarImage, fallback)
  link.page.syncStatus()
  link.reply({ brand: 'LYNKCO', carImageHash: 'theme888', carImageChunks: 1 })
  assert.equal(link.sent.at(-1).data.type, 'car_image')
  assert.equal(link.sent.at(-1).data.hash, 'theme888')

  const waiting = start({
    native: { '@app-module/system.storage': { get() {}, set() {} } }
  })
  assert.equal(waiting.page.displayCarImage, fallback)
  waiting.page.receiveTheme(theme)
  assert.equal(waiting.page.displayCarImage, fallback)
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
    vehicleName: '  俊哥的星耀8  ',
    vehicle: { totalKm: 470, electricKm: 120, fuelKm: 350, motion: '已驻车', updatedAt: 1700000000000, updatedLabel: '20:00' },
    mapped: ['lock'],
    available: ['lock'],
    controlDirections: { lock: 'unlock' }
  })
  assert.equal(circle.page.vehicleName, '俊哥的星耀8')
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
  const breakdown = () => Array.from(link.page.rangeBreakdown, ({ id, value }) => [id, value])
  assert.deepEqual(breakdown(), [['electric', '—\u00a0km'], ['fuel', '—\u00a0km']])
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
  assert.deepEqual(breakdown(), [['electric', '97%'], ['fuel', '517\u00a0km']])
  assert.deepEqual(Array.from(link.page.rangeBreakdown, reading => reading.percent), [97, 48])

  link.page.syncStatus()
  link.reply({
    vehicle: { electricPercent: 85, motion: '充电中', updatedAt: 1700000060000, updatedLabel: '20:21' }
  })
  assert.equal(link.page.total, '85')
  assert.equal(link.page.totalUnit, '%')
  assert.equal(link.page.electric, '85')
  assert.equal(link.page.electricUnit, '%')
  assert.deepEqual(breakdown(), [['electric', '85%']])
  assert.equal(link.page.rangeBreakdown[0].percent, 85)
})


test('Lynk fixed slots use neutral fallbacks and retain prepare/token/execute', () => {
  const link = start()
  link.hello({ brand: 'LYNKCO' })
  const state = { brand: 'LYNKCO', mapped: ['lynk_unlock', 'lynk_window', 'lynk_kjck', 'lynk_ccqk'],
    available: ['lynk_unlock', 'lynk_window', 'lynk_kjck', 'lynk_ccqk'], controlDirections: {},
    controlLabels: { lynk_unlock: '车锁' } }
  link.reply(state)
  assert.equal(link.page.brand, 'LYNKCO')
  assert.equal(link.page.displayRows[0].first.label, '车锁')
  assert.equal(link.page.displayRows[1].first.label, '功能3')
  assert.equal(link.page.displayRows[1].first.hint, '')
  link.page.select('lynk_kjck')
  assert.equal(link.sent.at(-1).data.type, 'prepare')
  assert.equal(link.sent.at(-1).data.action, 'lynk_kjck')
  assert.equal(link.sent.at(-1).data.controlDirection, undefined)
  link.reply({ ...state, status: 'confirm', action: 'lynk_kjck', token: 'a'.repeat(36), message: 'kjck（待验证）' })
  assert.match(link.page.confirmTitle, /待验证/)
  link.page.execute()
  assert.equal(link.sent.at(-1).data.type, "execute")
  assert.equal(link.sent.at(-1).data.token, "a".repeat(36))
  assert.equal(link.sent.at(-1).data.action, "lynk_kjck")
})

test('brand changes drop Galaxy actions, old vehicle state and image identity', () => {
  const link = start()
  link.hello()
  link.reply({ vehicle: { totalKm: 600, updatedAt: 1700000000000 }, mapped: ['climate'], available: ['climate'] })
  link.page.carImageHash = 'galaxy-image'
  link.page.syncStatus()
  link.reply({ brand: 'LYNKCO', mapped: ['climate', 'lynk_ccqk'], available: ['climate', 'lynk_ccqk'], controlDirections: {} })
  assert.deepEqual(Array.from(link.page.mapped), ['lynk_ccqk'])
  assert.equal(link.page.carImageHash, '')
  assert.equal(link.page.total, '—')
  link.page.syncStatus()
  link.reply({ mapped: ['lynk_kjck', 'climate'], available: ['lynk_kjck', 'climate'] })
  assert.equal(link.page.brand, 'GALAXY')
  assert.deepEqual(Array.from(link.page.mapped), ['climate'])
})

test('Lynk official confirmation suspends fixed controls and expires the local prompt', () => {
  const link = start()
  link.hello({ brand: 'LYNKCO' })
  link.reply({ brand: 'LYNKCO', mapped: ['lynk_kjck'], available: ['lynk_kjck'], phoneConfirmation: true })
  const before = link.sent.length
  link.page.select('lynk_kjck')
  assert.equal(link.page.phoneConfirmation, true)
  assert.match(link.page.controlNotice, /领克/)
  assert.ok(link.sent.slice(before).every(item => item.data.type !== 'prepare' && item.data.type !== 'execute'))
})

test('Geely has its own vehicle cache and presents its official confirmation', () => {
  const link = start()
  link.hello()
  link.reply({ vehicle: { totalKm: 600, updatedAt: 1700000000000 }, mapped: ['climate'], available: ['climate'] })
  link.page.carImageHash = 'galaxy-image'
  link.page.syncStatus()
  link.reply({ brand: 'GEELY', vehicle: { fuelPercent: 52, updatedAt: 1700000000000 },
    mapped: ['find', 'lynk_window'], available: ['find', 'lynk_window'], phoneConfirmation: true })
  assert.equal(link.page.brand, 'GEELY')
  assert.equal(link.page.total, '—')
  assert.equal(link.page.carImageHash, '')
  assert.deepEqual(Array.from(link.page.mapped), ['find'])
  assert.match(link.page.controlNotice, /吉利汽车/)
  link.page.syncStatus()
  link.reply({ brand: 'GEELY', mapped: ['find'], available: ['find'], armed: true })
  link.page.select('find')
  assert.equal(link.sent.at(-1).data.type, 'prepare')
  assert.equal(link.sent.at(-1).data.brand, 'GEELY')
})


test('Lynk voice routes only lock and window through prepare and wearable confirmation', () => {
  for (const action of ['lynk_unlock', 'lynk_window']) {
    const link = start()
    link.hello({ brand: 'LYNKCO' })
    link.reply({ brand: 'LYNKCO', mapped: [action], available: [action], voiceAction: action })
    assert.equal(link.sent.at(-1).data.type, 'prepare')
    assert.equal(link.sent.at(-1).data.action, action)
    link.reply({ brand: 'LYNKCO', mapped: [action], available: [action], status: 'confirm', action,
      token: 'v'.repeat(36), message: '领克语音 · 核对当前卡片' })
    assert.match(link.page.confirmTitle, /核对/)
    assert.notEqual(link.sent.at(-1).data.type, 'execute')
    link.page.execute()
    assert.equal(link.sent.at(-1).data.type, 'execute')
    assert.equal(link.sent.at(-1).data.token, 'v'.repeat(36))
  }
  for (const action of ['TEST', 'lynk_kjck', 'lynk_ccqk', 'engine', 'heat', 'cool', 'sunroof', 'trunk', 'find']) {
    const link = start()
    link.hello({ brand: 'LYNKCO' })
    link.reply({ brand: 'LYNKCO', mapped: ['lynk_kjck', 'lynk_ccqk'], available: ['lynk_kjck', 'lynk_ccqk'], voiceAction: action })
    assert.equal(link.sent.at(-1).data.type, 'status')
    assert.equal(link.page.confirmTitle, '')
  }
  const galaxy = start()
  galaxy.hello()
  galaxy.reply({ voiceAction: 'lynk_unlock' })
  assert.equal(galaxy.sent.at(-1).data.type, 'status')
})


test('configurable Lynk slot and owner mark contracts remain separate from Galaxy icon catalog', () => {
  const source = name => readFileSync(path.join(__dirname, '../../android/app/src/main/java/com/garage/bandcontrol/', name), 'utf8')
  const config = source('BrandConfig.java')
  for (const [container, icon, progress] of [['fr_unlock', 'unlock', 'pb_unlock'], ['fr_window', 'window', 'pb_window'],
      ['fr_kjck', 'kjck', 'pb_kjck'], ['fr_ccqk', 'ccqk_on', 'pb_ccqk_on']]) {
    assert.ok(config.includes('new Slot("' + container + '", "' + icon + '", "' + progress + '")'))
  }
  assert.ok(config.includes("lynk ? SlotMode.CONFIGURABLE : SlotMode.DYNAMIC"))
  const mark = source('LynkMark.java')
  assert.ok(mark.includes("this.fingerprint.equals(fingerprint)"))
  assert.ok(mark.includes("this.description.equals(description)"))
  assert.doesNotMatch(mark, /car_wg_funicon_/)
  assert.ok(source("GalaxyWidgets.java").includes("button.slot().equals(binding.slot()) && !binding.matches(button)"))
})

test('crown focus follows homeLayoutReady, settings, confirmation, and two-page transitions', () => {
  const rect = start({
    native: {
      '@app-module/system.device': {
        getInfo({ success }) { success({ screenWidth: 336, screenHeight: 480, screenShape: 'rect' }) }
      }
    }
  })
  const focused = []
  rect.page.$element = id => ({
    focus(options) { focused.push([id, options && options.focus]) }
  })

  rect.page.homeLayoutReady = false
  rect.page.rectTwoPageLoaded = true
  rect.page.updateHomeLayoutReady()
  assert.deepEqual(focused, [['homeScroll', true]])
  rect.advance(60)
  assert.deepEqual(focused, [['homeScroll', true], ['homeScroll', true]])

  focused.length = 0
  rect.page.openSettings()
  rect.advance(60)
  assert.deepEqual(focused, [['settingsScroll', true], ['settingsScroll', true]])

  focused.length = 0
  rect.page.changeRectTwoPage({ checked: true })
  rect.advance(60)
  assert.deepEqual(focused, [['settingsScroll', true], ['settingsScroll', true]])

  focused.length = 0
  rect.page.openThemeEditor()
  rect.advance(60)
  assert.deepEqual(focused, [['themeEditorScroll', true], ['themeEditorScroll', true]])

  focused.length = 0
  rect.page.closeThemeEditor()
  rect.advance(60)
  assert.deepEqual(focused, [['settingsScroll', true], ['settingsScroll', true]])

  focused.length = 0
  rect.page.closeSettings()
  rect.advance(60)
  assert.deepEqual(focused, [['watchSwiper', true], ['watchSwiper', true]])

  focused.length = 0
  rect.page.confirmTitle = '确认解锁？'
  rect.page.clearConfirmation()
  rect.advance(60)
  assert.deepEqual(focused, [['watchSwiper', true], ['watchSwiper', true]])
})

test('watch controls keep distinct background and orb states across night and day themes', () => {
  const storage = new Map()
  const circle = start({
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
  circle.hello()
  circle.reply({
    mapped: ['lock', 'climate', 'trunk'],
    available: ['lock', 'climate'],
    controlDirections: { lock: 'lock', climate: 'open' }
  })
  circle.page.themeArt = '/common/themes/alpine-circle-night.jpg'
  circle.page.themeNight = true
  circle.page.updateRows()
  assert.equal(circle.page.displayRows[0].first.watchCellStateStyle, ' watch-cell-active')
  assert.equal(circle.page.displayRows[0].second.watchCellStateStyle, ' watch-cell-ready')
  assert.equal(circle.page.displayRows[0].third.watchCellStateStyle, ' watch-cell-paused')
  assert.equal(circle.page.displayRows[0].first.watchOrbDayStyle, '')

  circle.page.themeArt = '/common/themes/alpine-circle-day.jpg'
  circle.page.themeNight = false
  circle.page.updateRows()
  assert.equal(circle.page.displayRows[0].first.watchCellStateStyle, ' watch-cell-active-day')
  assert.equal(circle.page.displayRows[0].second.watchCellStateStyle, ' watch-cell-ready-day')
  assert.equal(circle.page.displayRows[0].third.watchCellStateStyle, ' watch-cell-paused-day')
  assert.equal(circle.page.displayRows[0].first.watchOrbDayStyle, ' watch-orb-active-day')
  assert.equal(circle.page.displayRows[0].second.watchOrbDayStyle, ' watch-orb-ready-day')

  circle.page.syncStatus()
  circle.reply({
    theme: { themeId: 'alpine', mode: 'fixed', variant: 'day', dayStartMinute: 360, nightStartMinute: 1080 },
    mapped: ['lock', 'climate', 'trunk'],
    available: ['lock', 'climate'],
    controlDirections: { lock: 'unlock', climate: 'close' }
  })
  assert.equal(circle.page.displayRows[0].first.watchCellStateStyle, ' watch-cell-ready-day')
  assert.equal(circle.page.displayRows[0].second.watchCellStateStyle, ' watch-cell-active-day')
})

test('control icon sync pulls widget icons by hash, caches them, and falls back cleanly on error', () => {
  const files = new Map()
  const storage = new Map()
  const pngBytes = Buffer.from([0x89, 0x50, 0x4e, 0x47, 1, 2, 3, 4])
  const b64 = pngBytes.toString('base64')
  const part0 = b64.slice(0, 6)
  const part1 = b64.slice(6)
  const native = {
    '@app-module/system.device': {
      getInfo({ success }) { success({ screenWidth: 480, screenHeight: 480, screenShape: 'circle' }) }
    },
    '@app-module/system.storage': {
      get({ key, success }) { success(storage.get(key) || '') },
      set({ key, value, complete }) { storage.set(key, value); if (complete) complete() }
    },
    '@app-module/system.file': {
      writeArrayBuffer({ uri, buffer, success }) { files.set(uri, Buffer.from(buffer)); success() },
      delete({ uri }) { files.delete(uri) }
    }
  }
  const link = start({ native })
  link.hello({ features: ['confirm', 'cancel', 'refresh', 'car_image', 'control_icons'] })
  assert.equal(link.sent.at(-1).data.type, 'status')
  assert.equal(link.sent.at(-1).data.themeDay, false)

  link.reply({
    mapped: ['lock', 'trunk'],
    available: ['lock', 'trunk'],
    controlDirections: { lock: 'unlock' },
    actionIcons: { trunk: ['trunkhash123', 2] }
  })
  assert.equal(link.sent.at(-1).data.type, 'control_icon')
  assert.equal(link.sent.at(-1).data.hash, 'trunkhash123')
  assert.equal(link.sent.at(-1).data.index, 0)

  link.receive({ v: 1, id: link.sent.at(-1).data.id, type: 'control_icon_reply', hash: 'trunkhash123', index: 0, total: 2, data: part0 })
  assert.equal(link.sent.at(-1).data.type, 'control_icon')
  assert.equal(link.sent.at(-1).data.index, 1)

  link.receive({ v: 1, id: link.sent.at(-1).data.id, type: 'control_icon_reply', hash: 'trunkhash123', index: 1, total: 2, data: part1 })
  assert.equal(files.get('internal://files/cicon_trunkhash123.png').equals(pngBytes), true)
  assert.equal(link.page.displayRows[0].second.icon, 'internal://files/cicon_trunkhash123.png')

  link.page.select('trunk')
  link.reply({ status: 'confirm', action: 'trunk', token: 't'.repeat(36), message: '打开后备箱' })
  assert.equal(link.page.themeConfirmIcon, 'internal://files/cicon_trunkhash123.png')
  link.page.cancel()

  const reopened = start({ native })
  reopened.hello({ features: ['confirm', 'cancel', 'refresh', 'car_image', 'control_icons'] })
  reopened.reply({
    mapped: ['lock', 'trunk'],
    available: ['lock', 'trunk'],
    controlDirections: { lock: 'unlock' },
    actionIcons: { trunk: ['trunkhash123', 2] }
  })
  assert.equal(reopened.page.displayRows[0].second.icon, 'internal://files/cicon_trunkhash123.png')
  assert.equal(reopened.types().includes('control_icon'), false)

  reopened.page.onControlIconError('trunk')
  assert.equal(reopened.page.displayRows[0].second.icon, '/common/images/trunk.png')
})

test('startup waits for late interconnect onopen without false send failure toast', () => {
  let state = 2
  const sent = []
  const focused = []
  const connection = {
    send(message) {
      sent.push(message)
      if (state !== 1) message.fail()
    },
    getReadyState({ success }) {
      success({ status: state })
    }
  }
  const link = start({
    native: {
      '@app-module/system.device': {
        getInfo({ success }) { success({ screenWidth: 336, screenHeight: 480, screenShape: 'rect' }) }
      },
      '@app-module/system.interconnect': {
        instance: () => connection
      }
    }
  })
  link.page.$element = id => ({
    focus(options) { focused.push([id, options && options.focus]) }
  })
  assert.equal(link.page.homeLayoutReady, true)
  link.advance(1000)
  assert.equal(sent.length, 0)
  assert.equal(link.page.toastText, '')
  assert.equal(link.page.handshakeComplete, false)

  state = 1
  connection.onopen()
  assert.equal(sent.length, 1)
  assert.equal(sent[0].data.type, 'hello')
  assert.equal(link.page.toastText, '')

  connection.onmessage({
    data: JSON.stringify({ v: 1, id: sent[0].data.id, type: 'hello-reply', phoneApp: '0.2.8', protocol: 1, features: ['confirm'] })
  })
  assert.equal(sent.length, 2)
  assert.equal(sent[1].data.type, 'status')
  assert.equal(link.page.toastText, '')
})

test('unready openTimer fallback hello failure does not mark handshake complete or falsely toast', () => {
  let state = 0
  const sent = []
  const connection = {
    send(message) {
      sent.push(message)
      if (state !== 1) message.fail()
    },
    getReadyState() {}
  }
  const link = start({
    native: {
      '@app-module/system.interconnect': { instance: () => connection }
    }
  })
  link.advance(600)
  assert.equal(sent.length, 1)
  assert.equal(sent[0].data.type, 'hello')
  assert.equal(link.page.handshakeComplete, false)
  assert.equal(link.page.toastText, '')

  state = 1
  connection.onopen()
  assert.equal(sent.at(-1).data.type, 'hello')
  connection.onmessage({
    data: JSON.stringify({ v: 1, id: sent.at(-1).data.id, type: 'hello-reply', phoneApp: '0.2.8', protocol: 1, features: ['confirm'] })
  })
  assert.equal(sent.at(-1).data.type, 'status')
  assert.equal(link.page.toastText, '')
})

test('circular screen supports trio 3-button row, 2x2 quad, and optional single-screen mode defaulting to 2-page', () => {
  const storage = new Map()
  const native = {
    '@app-module/system.device': {
      getInfo({ success }) { success({ screenWidth: 466, screenHeight: 466, screenShape: 'circle' }) }
    },
    '@app-module/system.storage': {
      get({ key, success }) { success(storage.get(key) || '') },
      set({ key, value, complete }) { storage.set(key, value); if (complete) complete() }
    }
  }
  const circle = start({ native })
  assert.equal(circle.page.layoutProfile, 'circle')
  assert.equal(circle.page.canChangeDisplay, true)
  assert.equal(circle.page.circleTwoPage, true)
  assert.equal(circle.page.twoPageChecked, true)
  assert.equal(circle.page.useTwoPageShell, true)

  circle.hello({ bandDisplay: { twoPage: false, revision: 3 } })
  circle.reply({
    brand: 'LYNKCO',
    mapped: ['lynk_window', 'lynk_unlock', 'lynk_kjck'],
    available: ['lynk_window', 'lynk_unlock', 'lynk_kjck'],
    controlDirections: {},
    controlLabels: { lynk_window: '车窗', lynk_unlock: '车锁', lynk_kjck: '后备箱' },
    vehicle: { updatedAt: 1700000000000, updatedLabel: '11/26 20:01' }
  })
  assert.equal(circle.page.useTwoPageShell, true)
  assert.equal(circle.page.singleCircleRangeText, '')
  assert.equal(circle.page.displayRows.length, 1)
  assert.equal(circle.page.displayRows[0].first.label, '车窗')
  assert.equal(circle.page.displayRows[0].second.label, '车锁')
  assert.equal(circle.page.displayRows[0].third.label, '后备箱')
  assert.equal(circle.page.displayRows[0].first.watchOrbSizeStyle, 'watch-orb-trio')

  circle.page.openSettings()
  circle.page.changeTwoPage({ checked: false })
  assert.equal(circle.page.circleTwoPage, false)
  assert.equal(circle.page.twoPageChecked, false)
  assert.equal(circle.page.useTwoPageShell, false)
  assert.equal(storage.get('circle-two-page'), 'off')
  circle.page.closeSettings()

  const reopened = start({ native })
  assert.equal(reopened.page.circleTwoPage, false)
  assert.equal(reopened.page.useTwoPageShell, false)

  reopened.hello({ features: ['confirm', 'cancel', 'refresh'] })
  reopened.reply({
    brand: 'GALAXY',
    mapped: ['lock', 'climate', 'window_vent', 'trunk'],
    available: ['lock', 'climate'],
    controlDirections: { lock: 'unlock', climate: 'open' },
    vehicle: { totalKm: 641, electricKm: 124, fuelKm: 517, updatedAt: 1700000000000, updatedLabel: '10:00' }
  })
  assert.equal(reopened.page.displayRows.length, 2)
  assert.equal(reopened.page.displayRows[0].third.id, '')
  assert.equal(reopened.page.singleCircleRangeText, '641\u00a0km · 电 124\u00a0km · 油 517\u00a0km')

  reopened.page.openSettings()
  assert.equal(reopened.page.settingsOpen, true)
  reopened.page.onOverlaySwipe({ direction: 'right' })
  assert.equal(reopened.page.settingsOpen, false)
})
