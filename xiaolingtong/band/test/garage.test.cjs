const {test} = require('node:test')
const assert = require('node:assert/strict')
const {readFileSync} = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

const source = readFileSync(path.resolve(__dirname, '../build/pages/garage/index.js'), 'utf8')
  .trim().replace(/^export default /, '')
const appSource = readFileSync(path.resolve(__dirname, '../build/app.js'), 'utf8')
  .trim().replace(/^export default /, '')
const slot = {slot:'fr_window', key:'card/vehicle/window/pixels', label:'关窗'}

const square = {screenShape:'rect', screenWidth:336, screenHeight:336}
const designWidth = require('../src/manifest.json').config.designWidth
const binding = value => typeof value === 'function' ? value() : value

// Style entries in the compiled page pair a selector list with its declarations.
function compiledRule(page, name) {
  const entry = page.style.find(([selectors]) => selectors.some(([, selector]) => selector === name))
  assert.ok(entry, `Missing compiled style for ${name}`)
  return entry[1]
}

// Evaluate the compiled UX bindings, including conditional list items and click handlers.
// This is a component test, not a substitute for the firmware's text and gesture renderer.
const aiot = {
  __ci__({__opts__: opts}, children) { return opts.shown() ? children() : [] },
  __cf__({__opts__: opts}, children) { return opts.exp().__list__.map((item, i) => children(i, item)) },
  __ce__(tag, {__opts__: opts}, children) {
    if (opts.show && !opts.show()) return []
    return {tag, type:opts.type, children:Array.from(children.flat(Infinity)), click:opts.events?.click,
      complete:opts.events?.complete, error:opts.events?.error,
      value:binding(opts.value), src:binding(opts.src), disabled:binding(opts.disabled),
      classes:Array.from(binding(opts.classList) || []), style:opts.style?.() || {}}
  }
}

function setup(device = {getInfo: ({success}) => success(square)}, offline = false, hour = 12, store, file) {
  const sent = []
  const timers = new Map()
  let now = 100000
  let next = 0
  const connection = {send: message => sent.push(message.data)}
  const runtime = {
    Date: class extends Date { static now() { return now } getHours() { return hour } },
    aiot,
    $app_require$: name => ({
      '@app-module/system.interconnect':offline ? undefined : {instance: () => connection},
      '@app-module/system.storage':store, '@app-module/system.device':device, '@app-module/system.file':file
    })[name],
    setTimeout(fn, delay) { timers.set(++next, {fn, delay}); return next },
    clearTimeout(timer) { timers.delete(timer) }
  }
  const exports = {}
  const context = vm.createContext(runtime)
  vm.runInContext('Object.fromEntries = undefined', context)
  vm.runInContext('(' + appSource + ')', context)(runtime, runtime, runtime, {}, null)
  vm.runInContext('(' + source + ')', context)(runtime, runtime, runtime, exports, null)
  const component = {}
  exports.entry(component)
  const page = Object.assign(component.default, component.default.private || component.default.data || {})
  page.onInit()
  assert.equal(page.template(page).style.backgroundImage, undefined)
  page.onReady()
  page.onShow()
  function reply(payload = {}) {
    connection.onmessage({data: JSON.stringify({v:1, id:sent.at(-1).id, actions:[slot],
      data:{vehicle:'测试车辆', range:'320', battery:'52%', fuel:'17L', time:'14:30'}, ...payload})})
  }
  return {page, sent, reply, connection, timers, visibleRows: () => page.layout.single || page.controlList ? page.singleRows : page.pairedRows, feed: () => page.template(page).children.find(child => child.tag === 'list'), backdrop: () => page.template(page).children.find(child => child.classes.includes('themeBackdrop')), motion: () => page.template(page).children.find(child => child.classes.includes('themeMotion')), advance(ms) { now += ms }}
}

// A real PNG exercises byte-for-byte transport, including the native file module's input type.
const png = readFileSync(path.resolve(__dirname, '../src/common/metrics/battery.png'))
const encoded = png.toString('base64')
function photoSession(device, writeArrayBuffer = () => {}) {
  const writes = [], truncated = [], deleted = []
  const s = setup(device, false, 12, undefined, {
    writeText({uri, text, append, success}) { assert.equal(text, ' '); assert.equal(append, false); truncated.push(uri); success() },
    writeArrayBuffer(options) { writes.push(options); writeArrayBuffer(options) },
    delete(options) { deleted.push(options.uri); options.complete() }
  })
  const {reply} = s
  const replyPhoto = (payload = {}) => reply({actions:[], ...payload})
  return Object.assign(s, {writes, truncated, deleted, reply:replyPhoto,
    row: () => s.feed().children.find(row => row.type === 'carPhoto' && !row.children[0].src.startsWith('/common/')),
    poll() {
      const timer = s.timers.get(s.page.pollTimer)
      assert.ok(timer)
      s.advance(timer.delay); timer.fn()
      return s.sent.at(-1)
    },
    chunk(id, from = 0, to = encoded.length, overrides = {}, actions = []) {
      replyPhoto({actions, data:{vehicle:'测试车辆', condition:'已停稳', carPhotoId:id, carPhotoChunk:encoded.slice(from, to),
        carPhotoMime:'image/png', carPhotoOffset:from, carPhotoLength:encoded.length, ...overrides}})
    }})
}

function assertNoPhotoFields(request) {
  for (const key of ['bandPhoto','bandPhotoHd','carPhotoId','photoOffset','photoTransferId']) assert.equal(key in request, false, key)
}

test('standard home assembles a PNG, reuses its cached URI and unmounts stale photos', () => {
  const s = photoSession()
  assert.equal(s.sent[0].bandPhoto, true)
  assert.equal(s.sent[0].bandPhotoHd, true)
  assert.equal(s.sent[0].carPhotoId, '')
  assert.equal(s.sent[0].photoTransferId, '')
  assert.equal(s.sent[0].photoOffset, 0)
  assert.equal(s.row(), undefined)
  s.chunk('first', 0, 713)
  assert.equal(s.row(), undefined)
  const next = s.poll()
  assert.equal(next.photoTransferId, 'first')
  assert.equal(next.photoOffset, 713)
  assert.equal(next.carPhotoId, '')
  s.chunk('first', 713, encoded.length, {}, [{...slot, key:''}])
  assert.equal(s.row(), undefined)
  assert.equal(s.writes.length, 1)
  const write = s.writes[0]
  assert.deepEqual(s.truncated, [write.uri])
  assert.equal(Object.prototype.toString.call(write.buffer), '[object ArrayBuffer]')
  assert.deepEqual(Buffer.from(write.buffer), png)
  write.success()
  assert.match(s.row().children[0].src, /^internal:\/\/cache\/garage-car-[01]\.png$/)
  const types = s.feed().children.map(row => row.type)
  assert.ok(types.indexOf('condition') < types.indexOf('carPhoto') && types.indexOf('carPhoto') < types.indexOf('actionpair'))
  assert.equal(s.poll().carPhotoId, 'first')
  s.reply({data:{vehicle:'测试车辆', carPhotoId:'first'}})
  assert.equal(s.row().children[0].src, write.uri)
  assert.equal(s.writes.length, 1)
  s.poll(); s.chunk('second', 0, 320)
  assert.equal(s.row(), undefined)
  assert.deepEqual(s.deleted, [write.uri])
  assert.equal(s.poll().photoTransferId, 'second')
  s.reply({data:{carPhotoId:'', vehicle:'测试车辆'}})
  assert.equal(s.poll().photoOffset, 0)
  s.reply() // Legacy phone without photo support.
  assert.equal(s.row(), undefined)
  assert.equal(s.timers.get(s.page.pollTimer).delay, 5000)
})

test('photo writer retries the legacy buffer once on a type mismatch and falls back on I/O failure', () => {
  for (const code of [202, '202', 300]) {
    const s = photoSession()
    s.chunk('native')
    s.writes[0].fail('native failure', code)
    assert.equal(s.row(), undefined)
    if (Number(code) === 202) {
      assert.equal(s.writes.length, 2)
      assert.equal(Object.prototype.toString.call(s.writes[1].buffer), '[object Uint8Array]')
      assert.deepEqual(Buffer.from(s.writes[1].buffer), png)
      s.writes[1].success()
      assert.equal(s.row().children[0].src, s.writes[0].uri)
    } else {
      assert.equal(s.writes.length, 1)
      assert.deepEqual(s.deleted, [s.writes[0].uri])
    }
  }
  const legacy = photoSession(undefined, options => {
    if (!ArrayBuffer.isView(options.buffer)) throw new TypeError('typed array only')
    options.success()
  })
  legacy.chunk('legacy')
  assert.equal(legacy.writes.length, 2)
  assert.ok(legacy.row())
})

test('standard screens resolve HD then legacy then fallback without a placeholder row', () => {
  for (const [screenWidth, screenHeight] of [[176,220], [336,480], [390,450], [432,514], [466,466], [480,480]]) {
    const s = photoSession({getInfo:({success}) => success({screenWidth, screenHeight})})
    const photo = () => s.feed().children.find(row => row.type === 'carPhoto')
    assert.equal(s.sent[0].bandPhotoHd, true)
    assert.equal(photo(), undefined)
    s.reply({data:{vehicle:'测试车辆', carPhotoId:''}})
    assert.equal(photo(), undefined)
    const legacy = s.poll()
    assert.equal(legacy.bandPhoto, true)
    assert.equal(legacy.bandPhotoHd, undefined)
    s.reply({data:{vehicle:'测试车辆', carPhotoId:''}})
    assert.equal(photo().children[0].src, '/common/default-vehicle.png')
  }
})

test('REDMI Watch 4 uses the known PNG path and can refresh and configure two columns', () => {
  for (const screenShape of ['rect', undefined]) {
    const s = photoSession({getInfo:({success}) => success({screenShape, screenWidth:390, screenHeight:450})}, write => {
      assert.equal(Object.prototype.toString.call(write.buffer), '[object ArrayBuffer]')
      assert.deepEqual(Buffer.from(write.buffer), png)
      write.success()
    })
    const row = type => s.feed().children.find(item => item.type === type)
    const actions = [slot, {...slot, slot:'lock', label:'解锁'}, {...slot, slot:'climate', label:'开空调'}]
    assert.equal(s.page.layout.feedStyle.width, '94%')
    assert.equal(s.sent[0].bandPhoto, true)
    assert.equal(s.sent[0].bandPhotoHd, true)
    assert.equal(row('carPhoto'), undefined)
    s.chunk('watch4-photo', 0, encoded.length, {}, actions)
    assert.match(row('carPhoto').children[0].src, /^internal:\/\/cache\//)
    assert.equal(s.visibleRows().length, 2)
    assert.ok(row('actionpair'))
    row('settings').children[0].click()
    row('controlList').children[0].click()
    row('back').children[0].click()
    assert.equal(s.visibleRows().length, 3)
    const outstanding = s.poll()
    const button = () => row('refresh').children[0]
    assert.equal(button().tag, 'text')
    button().click()
    assert.match(button().value, /^正在刷新/)
    assert.equal(s.sent.at(-1), outstanding)
    s.reply({actions, data:{vehicle:'测试车辆', carPhotoId:'watch4-photo', time:'14:30'}})
    assert.equal(s.sent.at(-1).type, 'refresh')
    s.reply({actions, note:'已请求刷新，等候领克更新', data:{vehicle:'测试车辆',time:'14:31'}})
    assert.equal(button().value, '刷新车况\n14:31')
    assert.equal(row('receipt').children[0].value, '已请求刷新，等候领克更新')
  }
})

test('pill and undetected layouts ignore even unsolicited valid photos', () => {
  for (const device of [{}, {getInfo:({success}) => success({screenShape:'pill-shaped', screenWidth:192, screenHeight:490})}]) {
    const s = photoSession(device)
    assertNoPhotoFields(s.sent[0]); s.chunk('unsolicited')
    assert.equal(s.writes.length, 0)
    assert.equal(s.row(), undefined)
    assertNoPhotoFields(s.poll())
  }
})

test('grid centers its odd last tile and settings switch to a persisted full-width list', () => {
  const actions = [slot, {...slot, slot:'trunk', label:'后备箱'}, {...slot, slot:'find', label:'寻车'}]
  let saved
  const store = {
    get({key, success}) { success(key === 'garageControlLayout' ? saved : undefined) },
    set({key, value}) { if (key === 'garageControlLayout') saved = value }
  }
  const s = setup(undefined, false, 12, store)
  const row = (session, type) => session.feed().children.find(item => item.type === type)
  s.reply({actions:[slot]})
  row(s, 'settings').children[0].click()
  assert.equal(row(s, 'controlGrid'), undefined)
  assert.ok(row(s, 'theme'))
  row(s, 'back').children[0].click()
  s.page.refresh(); s.reply({actions})
  const pairs = s.feed().children.filter(item => item.type === 'actionpair')
  assert.equal(pairs.length, 2)
  assert.equal(pairs[0].children.length, 2)
  assert.equal(pairs[0].classes.includes('centeredSlotRow'), false)
  assert.equal(pairs[1].children.length, 1)
  assert.equal(pairs[1].classes.includes('centeredSlotRow'), true)
  row(s, 'settings').children[0].click()
  assert.equal(row(s, 'controlGrid').children[0].value, '网格 · 已选')
  row(s, 'controlList').children[0].click()
  assert.equal(row(s, 'controlList').children[0].value, '列表 · 已选')
  assert.equal(saved, 'list')
  row(s, 'back').children[0].click()
  const singles = s.feed().children.filter(item => item.type === 'actionSingle')
  assert.equal(singles.length, 3)
  assert.equal(singles[0].children[0].style.width, '311px')
  const reopened = setup(undefined, false, 12, store)
  reopened.reply({actions})
  assert.equal(reopened.page.singleRows.length, 3)
  row(reopened, 'settings').children[0].click()
  row(reopened, 'controlGrid').children[0].click()
  assert.equal(saved, 'grid')
  const small = setup({getInfo:({success}) => success({screenShape:'rect', screenWidth:176, screenHeight:220})}, false, 12, store)
  small.reply({actions})
  assert.equal(small.page.singleRows.length, 3)
  row(small, 'settings').children[0].click()
  assert.equal(row(small, 'controlGrid'), undefined)
  assert.ok(row(small, 'theme'))
})

test('refresh waits for the in-flight request, and controls ignore preempted photo replies', () => {
  const s = photoSession()
  s.chunk('a', 0, 100, {}, [slot])
  const stale = s.poll()
  assert.equal(stale.bandPhoto, true)
  assert.equal(stale.photoOffset, 100)
  s.page.prepare(slot.slot)
  assert.equal(s.sent.at(-1).type, 'prepare')
  assertNoPhotoFields(s.sent.at(-1))
  s.reply({id:stale.id, data:{carPhotoId:''}})
  assert.equal(s.page.pending.type, 'prepare')
  s.reply({actions:[slot], token:'one', label:slot.label})
  s.advance(600); s.page.confirm()
  assert.equal(s.sent.at(-1).type, 'execute')
  assertNoPhotoFields(s.sent.at(-1)); s.reply()
  const outstanding = s.poll()
  assert.equal(outstanding.photoOffset, 100)
  s.page.refresh(); s.page.refresh()
  assert.equal(s.sent.at(-1), outstanding)
  s.chunk('a', 100, encoded.length)
  assert.equal(s.sent.at(-1).type, 'refresh')
  assertNoPhotoFields(s.sent.at(-1))
  assert.equal(s.sent.filter(request => request.type === 'refresh').length, 1)
  assert.equal(s.writes.length, 0)
})

test('pull-to-refresh starts only at the top and defers photo writes during a drag', () => {
  const s = photoSession()
  const down = {touches:[{identifier:3, clientX:48, clientY:24}]}
  const up = {changedTouches:[{identifier:3, clientX:51, clientY:84}]}
  const carRow = () => s.feed().children.find(row => row.type === 'carPhoto')
  s.page.feedScrolled({scrollY:42}); s.page.feedTouchStarted(down); s.page.feedTouchEnded(up)
  assert.equal(s.page.queuedRefresh, false)
  s.page.feedScrolled({scrollY:0}); s.page.feedTouchStarted(down); s.page.feedTouchEnded(up)
  assert.equal(s.page.queuedRefresh, true)
  assert.equal(s.sent.length, 1)
  s.chunk('drag')
  assert.equal(s.writes.length, 0)
  assert.equal(s.sent.at(-1).type, 'refresh')
  assert.equal(carRow(), undefined)
  s.reply({data:{vehicle:'测试车辆'}})
  assert.equal(s.page.note, '')
  s.poll(); s.chunk('drag'); s.writes[0].success()
  assert.match(carRow().children[0].src, /^internal:\/\/cache\//)
  carRow().children[0].error()
  assert.equal(carRow(), undefined)
  assert.equal(s.poll().bandPhotoHd, undefined)
  s.reply({data:{vehicle:'测试车辆', carPhotoId:''}})
  assert.equal(carRow().children[0].src, '/common/default-vehicle.png')
})

test('timed-out native photo writes do not replace the fallback or reuse a live path', () => {
  const s = photoSession()
  s.chunk('slow')
  const timer = s.timers.get(s.page.photoFile.timer)
  s.advance(timer.delay); timer.fn()
  assert.equal(s.row(), undefined)
  const retry = s.poll()
  assert.equal(retry.bandPhoto, true)
  assert.equal(retry.bandPhotoHd, undefined)
  s.chunk('retry')
  assert.notEqual(s.writes[0].uri, s.writes[1].uri)
  s.writes[0].success(); s.writes[1].success()
  assert.equal(s.row().children[0].src, s.writes[1].uri)
})

test('repeated idle scroll events do not indefinitely postpone car photo chunks', () => {
  const s = photoSession()
  s.page.feedScrolled({scrollY:0, scrollState:0})
  s.chunk('idle', 0, 713)
  assert.equal(s.poll().photoOffset, 713)
  s.page.feedScrolled({scrollY:0, scrollState:0})
  s.chunk('idle', 713)
  assert.equal(s.writes.length, 1)
  s.writes[0].success()
  assert.ok(s.row())
})

test('an unreadable HD cache retries the proven standard photo and legacy writer', () => {
  const s = photoSession()
  assert.equal(s.sent[0].bandPhotoHd, true)
  s.chunk('hd'); s.writes[0].success()
  s.row().children[0].error()
  assert.equal(s.row(), undefined)
  s.advance(10000)
  const retry = s.poll()
  assert.equal(retry.bandPhoto, true)
  assert.equal(retry.bandPhotoHd, undefined)
  assert.equal(retry.carPhotoId, '')
  s.chunk('compatible')
  assert.equal(Object.prototype.toString.call(s.writes[1].buffer), '[object ArrayBuffer]')
  s.writes[1].success()
  assert.ok(s.row())
})

test('partial photos yield to command follow-up and queued refresh, then resume at the saved offset', () => {
  const s = photoSession()
  const item = {...slot, category:'WINDOW', controlState:'open'}
  s.chunk('image', 0, 100, {}, [item]); s.poll()
  s.page.prepare(item.slot); s.reply({actions:[item], token:'once', label:item.label})
  s.advance(501); s.page.confirm()
  s.reply({type:'executeAck', accepted:true, note:'已转交'})
  const required = s.sent.at(-1)
  assert.equal(required.type, 'status'); assertNoPhotoFields(required)
  s.page.refresh()
  assert.equal(s.sent.at(-1), required)
  s.reply({actions:[item]})
  assert.equal(s.sent.at(-1).type, 'refresh'); assertNoPhotoFields(s.sent.at(-1))
  s.reply({actions:[item]})
  assertNoPhotoFields(s.poll())
  s.reply({actions:[{...item, controlState:'closed'}]})
  const resumed = s.poll()
  assert.equal(resumed.photoTransferId, 'image'); assert.equal(resumed.photoOffset, 100)
  assert.equal(s.sent.filter(request => request.type === 'execute').length, 1)
})

test('bad metadata, oversized, reordered or mixed chunks discard partial transfers', () => {
  for (const bad of [{carPhotoMime:'image/jpeg'}, {carPhotoOffset:0}, {carPhotoOffset:1.5}, {carPhotoLength:32772},
    {carPhotoLength:encoded.length + 4}, {carPhotoLength:14}, {carPhotoChunk:''}, {carPhotoChunk:'*'},
    {carPhotoChunk:'A'.repeat(2049)}, {carPhotoId:'swapped'}, {carPhotoId:null}]) {
    const s = photoSession()
    s.chunk('a', 0, 100); s.poll(); s.chunk('a', 100, encoded.length, bad)
    assert.equal(s.writes.length, 0, JSON.stringify(bad))
    assert.equal(s.row(), undefined)
    const retry = s.poll()
    assert.equal(retry.photoOffset, 0)
    if (bad.carPhotoId !== null) assert.equal(retry.bandPhotoHd, undefined)
  }
  const s = photoSession()
  s.chunk('junk', 0, encoded.length, {carPhotoChunk:'A'.repeat(encoded.length)})
  assert.equal(s.writes.length, 0)
})

test('late cache writes cannot revive photos after revocation, hiding or disconnect', () => {
  for (const invalidate of ['empty','legacy','vehicle','hide','disconnect']) {
    const s = photoSession()
    s.chunk('a')
    if (invalidate === 'hide') s.page.onHide()
    else if (invalidate === 'disconnect') s.connection.onclose()
    else if (invalidate === 'vehicle') { s.page.refresh(); s.reply({data:{vehicle:'另一辆车'}}) }
    else { s.poll(); s.reply({data:{vehicle:'测试车辆', ...(invalidate === 'empty' ? {carPhotoId:''} : {})}}) }
    s.writes[0].success()
    assert.equal(s.row(), undefined)
    assert.deepEqual(s.deleted, [s.writes[0].uri])
  }
})

test('cold and warm photo timing with a frame-limited .37 phone', t => {
  const s = photoSession()
  const bytes = readFileSync(path.resolve(__dirname, '../src/common/icon.png'))
  const payload = bytes.toString('base64'), id = 'digest'
  const actions = [slot, {...slot, slot:'door', key:'card/door'}]
  // Simulate a 4 KiB status envelope with room for 768 base64 characters.
  // Measure scheduled idle time separately from transport and native decoder latency.
  function visit() {
    let idleMs = 0, chunks = 0, replies = 0
    while (!s.row()) {
      assert.ok(replies < 30, 'transfer must finish')
      if (replies++) {
        idleMs += s.timers.get(s.page.pollTimer).delay
        s.poll()
      }
      const request = s.sent.at(-1), data = {vehicle:'测试车辆'}
      assert.equal(request.type, 'status')
      if (request.bandPhoto) {
        data.carPhotoId = id
        if (request.carPhotoId !== id) {
          const offset = request.photoTransferId === id ? request.photoOffset : 0
          Object.assign(data, {carPhotoOffset:offset, carPhotoLength:payload.length,
            carPhotoMime:'image/png', carPhotoChunk:payload.slice(offset, offset + 768)})
          chunks++
        }
      }
      const writes = s.writes.length
      s.reply({actions, data})
      if (s.writes.length > writes) {
        assert.deepEqual(Buffer.from(s.writes.at(-1).buffer), bytes)
        s.writes.at(-1).success()
      }
    }
    return {idleMs, chunks, replies}
  }
  const cold = visit()
  s.page.onHide(); s.page.onShow()
  const warm = visit()
  t.diagnostic(JSON.stringify({base64Chars:payload.length, cold, warm}))
  assert.deepEqual(cold, {idleMs:288, chunks:10, replies:10})
  assert.deepEqual(warm, {idleMs:0, chunks:0, replies:1})
  assert.equal(s.writes.length, 1)
})

test('a suspended completed photo requires a fresh vehicle and photo ID, and is deleted on destroy', () => {
  for (const interrupt of ['hide','disconnect']) {
    for (const next of ['same','vehicle','missingVehicle','missingId','changedId','emptyId']) {
      const s = photoSession()
      s.chunk('original', 0, encoded.length, {}, [slot]); s.writes[0].success()
      const uri = s.row().children[0].src, stale = s.poll()
      if (interrupt === 'hide') { s.page.onHide(); s.page.onShow() }
      else { s.connection.onclose(); s.poll() }
      assert.equal(s.row(), undefined)
      assert.deepEqual(s.deleted, [])
      assert.equal(s.sent.at(-1).carPhotoId, 'original')
      s.reply({id:stale.id, data:{vehicle:'测试车辆', carPhotoId:'original'}})
      assert.equal(s.row(), undefined)
      const data = {vehicle:next === 'vehicle' ? '另一辆车' : '测试车辆', carPhotoId:'original'}
      if (next === 'missingVehicle') delete data.vehicle
      if (next === 'missingId') delete data.carPhotoId
      if (next === 'emptyId') data.carPhotoId = ''
      if (next === 'changedId') data.carPhotoId = 'different'
      s.reply({data})
      if (next === 'same') {
        assert.equal(s.row().children[0].src, uri)
        assert.equal(s.writes.length, 1)
        s.page.onHide()
        assert.equal(s.row(), undefined)
        s.page.onDestroy()
      } else assert.equal(s.row(), undefined)
      assert.deepEqual(s.deleted, [uri])
      if (next !== 'same') assert.equal(s.poll().carPhotoId, '')
    }
  }
})

test('paced photo retries have a bounded burst even if a peer sends a character per status', () => {
  const s = photoSession()
  const delays = []
  for (let i = 0; i < 20; i++) {
    s.chunk('small', i, i + 1)
    delays.push(s.timers.get(s.page.pollTimer).delay)
    assert.equal(s.poll().photoOffset, i + 1)
  }
  assert.deepEqual(delays.slice(0, 16), Array(16).fill(32))
  assert.equal(delays[16], 5000)
  assert.equal(delays[17], 32)
})

test('home uses only current widget slots and refuses read-only actions', () => {
  const s = setup()
  assert.equal(s.sent[0].selectedActions, true)
  s.reply({actions:[{...slot, category:'WINDOW', enabled:false}]})
  assert.equal(s.visibleRows().length, 1)
  assert.equal(s.visibleRows()[0].left.controlName, '关窗')
  assert.equal(s.visibleRows()[0].right.slot, '')
  s.page.prepare(slot.slot)
  assert.deepEqual(s.sent.map(m=>m.type), ['status'])
  s.page.refresh()
  s.reply({actions:[]})
  assert.equal(s.visibleRows().length, 0)
})

test('both tiles show only control names; confirmation and wire payload use the action label', () => {
  const s = setup()
  const climate = {slot:'climate', key:'ac', category:'CLIMATE', label:'关空调', displayLabel:'空调.开', controlName:'前排空调', controlState:'open'}
  const loading = {...slot, displayLabel:'车窗.加载'} // Older companion without controlState or controlName.
  const actions = [loading, climate]
  s.reply({actions})
  assert.equal(s.visibleRows()[0].left.controlName, '车窗')
  assert.equal(s.visibleRows()[0].right.controlName, '前排空调')
  s.page.prepare(loading.slot)
  assert.equal(s.sent.length, 1)
  s.page.prepare(climate.slot)
  s.reply({actions, token:'wrong', label:climate.displayLabel})
  assert.equal(s.page.mode, 'home')
  s.page.prepare(climate.slot)
  s.reply({actions, token:'valid', label:climate.label})
  assert.equal(s.page.question, '关空调')
  s.advance(501)
  s.page.confirm()
  assert.deepEqual(s.sent.map(m => m.type), ['status', 'prepare', 'prepare', 'execute'])
  for (const m of s.sent) {
    assert.equal(m.supportsControlState, true)
    if (m.type !== 'status') assert.equal(m.label, '关空调')
    assert.equal('displayLabel' in m, false)
  }
})

test('status displays official units and zero values', () => {
  const s = setup()
  assert.equal(s.sent[0].type, 'status')
  s.reply({data:{range:'0', battery:'0%', fuel:'0L'}})
  assert.equal(s.page.range, '0')
  assert.equal(s.page.battery, '0%')
  assert.equal(s.page.fuel, '0L')
})
test('actions require a matched prepare reply and explicit confirmation', () => {
  const s = setup()
  s.reply(); s.page.controls(); s.page.prepare(slot.slot)
  assert.equal(s.sent.at(-1).type, 'prepare')
  s.connection.onmessage({data: JSON.stringify({v:1, id:'stale', token:'bad', label:'关窗'})})
  assert.equal(s.page.mode, 'home')
  s.reply({token:'test-ticket', label:'关窗'})
  assert.equal(s.page.mode, 'confirm')
  s.page.confirm()
  assert.equal(s.sent.at(-1).type, 'prepare')
  s.advance(501)
  s.page.confirm(); s.page.confirm()
  assert.deepEqual(s.sent.map(m => m.type), ['status', 'prepare', 'execute'])
  assert.equal(s.sent.at(-1).token, 'test-ticket')
})
test('cancel, timeout and hiding a page never execute a command', () => {
  for (const action of ['cancel', 'expire', 'hide']) {
    const s = setup()
    s.reply(); s.page.prepare(slot.slot); s.reply({token:'t', label:'关窗'})
    if (action === 'cancel') s.page.cancel()
    if (action === 'expire') Array.from(s.timers.values()).find(t => t.delay === 12000).fn()
    if (action === 'hide') s.page.onHide()
    s.page.confirm()
    assert.equal(s.sent.filter(m => m.type === 'execute').length, 0)
  }
})
test('disconnected watch drops actionable slots and reports uncertain dispatch', () => {
  const s = setup()
  s.reply(); s.page.prepare(slot.slot); s.reply({token:'t', label:'关窗'}); s.advance(501); s.page.confirm()
  s.connection.onclose()
  assert.equal(s.page.actions.length, 0)
  assert.match(s.page.note, /待核对/)
  assert.equal(s.page.choice, null)
})
for (const [shape, w, h, columns] of [
  ['circle', 466, 466, 2], ['rect', 336, 336, 2], ['pill-shaped', 192, 490, 1],
  ['rect', 336, 396, 1], ['rect', 396, 336, 1], [undefined, 336, 396, 1],
  ['rect', 192, 490, 1], [undefined, 192, 490, 1], ['pill-shaped', 212, 520, 1],
  ['circle', 240, 240, 1], ['rect', 240, 240, 1], ['pill-shaped', 152, 486, 1]
]) {
  test(`scrollable home and confirmation on ${shape || 'unreported'} ${w}x${h}`, () => {
    const s = setup({getInfo: ({success}) => success({screenShape:shape, screenWidth:w, screenHeight:h})})
    const actions = Array.from({length:5}, (_, i) => ({...slot, slot:'s' + i, controlName:'车窗' + i,
      category:'WINDOW', label:'关闭车窗', controlState:'open'}))
    s.reply({actions, data:{vehicle:'较长的测试车辆名称', condition:'车辆已停稳，可以远程控制', battery:'100%', fuel:'52L'}})
    const feed = s.feed()
    assert.equal(feed.tag, 'list')
    const width = parseFloat(feed.style.width) * w / 100
    const height = parseFloat(feed.style.height) * h / 100
    if ((shape === 'rect' || !shape && w !== h) && h / w < 1.65) {
      assert.ok(width >= 0.9 * w && height >= 0.9 * h, 'square should use its corners')
    } else {
      // The viewport corners must lie inside the circular caps at both scroll limits.
      assert.ok(Math.hypot(width / 2, Math.max(0, (height - (h - w)) / 2)) <= w / 2)
    }
    const energy = feed.children.find(row => row.type === 'energy')
    assert.equal(energy.style.flexDirection, w >= 180 ? 'row' : 'column')
    assert.equal(energy.children[0].children[1].value, '100%')
    const rows = feed.children.filter(row => row.type === (columns === 1 ? 'actionSingle' : 'actionpair'))
    assert.deepEqual(rows.map(row => row.children.length), columns === 1 ? [1,1,1,1,1] : [2,2,1])
    const tiles = rows.flatMap(row => row.children)
    for (const [i, tile] of tiles.entries()) {
      if (columns === 1) {
        assert.ok(tile.classes.includes('singleTile'))
        assert.ok(parseFloat(tile.style.width) * w / designWidth >= width * .95)
        assert.equal(compiledRule(s.page, 'singleTile').flexDirection, 'row')
      }
      assert.equal(tile.children[0].children[0].tag, 'image')
      assert.equal(tile.children[1].value, '车窗' + i)
      assert.ok(parseFloat(tile.style.height) * w / designWidth >= 44)
      assert.ok(parseFloat(tile.children[1].style.fontSize) * w / designWidth >= 16)
    }
    const refresh = feed.children.at(-1).children[0]
    assert.equal(refresh.value, '刷新车况\n等候卡片更新')
    assert.equal(typeof refresh.click, 'function')
    assert.ok(parseFloat(refresh.style.height) * w / designWidth >= 44)
    assert.ok(parseFloat(refresh.style.fontSize) * w / designWidth >= 16)
    tiles.at(-1).click()
    assert.equal(s.sent.at(-1).slot, 's4')
    s.reply({actions, token:'t', label:'关闭车窗'})
    const confirm = s.feed()
    assert.equal(confirm.tag, 'list')
    assert.deepEqual(confirm.style, feed.style)
    assert.equal(confirm.children[0].children[2].value, '关闭车窗')
    const buttons = confirm.children.slice(1).map(row => row.children[0])
    assert.deepEqual(buttons.map(button => button.value), ['确认操作','取消'])
    assert.ok(buttons.every(button => parseFloat(button.style.height) * w / designWidth >= 44))
    buttons[1].click()
    assert.equal(s.page.mode, 'home')
    assert.equal(s.sent.at(-1).type, 'prepare')
  })
}

test('missing device API stays usable; late device info reflows existing actions', () => {
  let detected
  for (const device of [{}, {getInfo: options => { detected = options.success }}]) {
    const s = setup(device)
    s.reply({actions:[slot, {...slot, slot:'other'}]})
    assert.equal(s.visibleRows().length, 2)
    if (detected) {
      detected(square)
      assert.equal(s.visibleRows().length, 1)
      assert.equal(s.visibleRows()[0].right.slot, 'other')
      assert.equal(s.sent.length, 1)
    }
  }
})

test('icon badge uses reported state with either phone appearance; loading and unknown never arm', () => {
  for (const appearance of ['light','dark']) {
    const s = setup()
    const actions = ['open','closed','loading','unknown','unrecognized'].map((state, i) =>
      ({...slot, slot:'a' + i, controlState:state, controlName:'车窗', displayLabel:'车窗.开', enabled:true}))
    s.reply({actions, appearance})
    const tiles = s.feed().children.filter(row => row.type === 'actionpair').flatMap(row => row.children)
    const colors = {on:'#59cf91',tile:'#192b3e',loading:'#ffcc46',unknown:'#a2abb6'}
    assert.deepEqual(tiles.map(tile => tile.children[0].style.backgroundColor),
      [colors.on, colors.tile, colors.loading, colors.unknown, colors.unknown])
    assert.deepEqual(tiles.map(tile => tile.disabled), [false,false,true,true,true])
    for (const [i, tile] of tiles.entries()) {
      const badge = tile.children[0]
      assert.deepEqual(badge.classes, i === 2 ? ['badge','pulse'] : ['badge'])
      const darkGlyph = i !== 1
      assert.equal(badge.children[0].src, '/common/controls/local/' + (darkGlyph ? 'day/' : '') + 'OTHER.png')
    }
    for (const tile of tiles.slice(2)) tile.click()
    assert.equal(s.sent.length, 1)
    tiles[0].click()
    s.reply({actions:[{...actions[0], controlState:'loading'}], token:'stale', label:slot.label})
    assert.equal(s.page.mode, 'home')
    assert.equal(s.page.choice, null)
  }
})

test('compiled badge transitions color and only pulses while its slot is loading', () => {
  const s = setup()
  const badge = compiledRule(s.page, 'badge')
  assert.equal(badge.transitionProperty, 'background-color')
  assert.ok(parseFloat(badge.transitionDuration) > 0)
  const pulse = compiledRule(s.page, 'pulse')
  assert.equal(pulse.animationIterationCount, 'infinite')
  assert.ok(parseFloat(pulse.animationDuration) > 0)
  const frames = JSON.parse(compiledRule(s.page, pulse.animationName).keyframes)
  assert.deepEqual(frames.map(frame => frame.time), [0,50,100])
  assert.equal(frames[0].opacity, frames[2].opacity)
  assert.ok(frames[1].opacity > 0 && frames[1].opacity < frames[0].opacity)

  for (const states of [['closed','loading'], ['loading','open'], ['unknown','closed']]) {
    s.reply({actions:states.map((controlState, i) => ({...slot, slot:'s' + i, controlState}))})
    const tiles = s.feed().children.find(row => row.type === 'actionpair').children
    assert.deepEqual(tiles.map(tile => tile.children[0].classes.includes('pulse')),
      states.map(state => state === 'loading'))
    s.page.refresh()
  }
})

test('execute starts five seconds of status-only polling; unchanged loading keeps its badge', () => {
  const s = setup()
  s.reply(); s.page.prepare(slot.slot); s.reply({token:'once', label:slot.label})
  s.advance(501); s.page.confirm()
  const loading = {...slot, controlName:'车窗', controlState:'loading'}
  s.reply({actions:[loading]})
  const rows = s.visibleRows()
  for (let tick = 1; tick <= 10; tick++) {
    const timer = s.timers.get(s.page.pollTimer)
    assert.equal(timer.delay, 500)
    s.advance(timer.delay); timer.fn()
    assert.equal(s.sent.at(-1).type, 'status')
    assert.equal('token' in s.sent.at(-1), false)
    s.reply({actions:[tick === 10 ? {...loading, controlState:'closed'} : loading]})
    if (tick < 10) assert.equal(s.visibleRows(), rows)
  }
  assert.equal(s.visibleRows()[0].left.state, 'closed')
  assert.equal(s.timers.get(s.page.pollTimer).delay, 5000)
  assert.equal(s.sent.filter(m => m.type === 'execute').length, 1)
  s.page.onHide()
  assert.equal(s.visibleRows().length, 0)
  assert.equal(s.timers.size, 0)
})

const windowOpen = {...slot, category:'WINDOW', controlName:'车窗', controlState:'open'}
const windowClosed = {...windowOpen, key:'closed-window', label:'开窗', controlState:'closed'}
const findCar = {slot:'find', key:'find-car', category:'FIND', controlName:'寻车', label:'寻车', momentary:true}

function startOperation(s, item, actions = [item]) {
  s.reply({actions})
  s.page.prepare(item.slot)
  s.reply({actions, token:'one-use', label:item.label})
  s.advance(501)
  s.page.confirm()
  assert.equal(s.sent.at(-1).compactExecuteAck, true)
}

test('compact acceptance keeps the current dashboard and polls until the original control changes', () => {
  const s = setup()
  startOperation(s, windowOpen, [windowOpen, findCar])
  assert.equal(s.visibleRows()[0].left.state, 'loading')
  assert.equal(s.visibleRows()[0].right.enabled, false)
  s.reply({type:'executeAck', accepted:true, note:'已转交', actions:[], data:{}})
  assert.equal(s.page.range, '320')
  assert.equal(s.sent.at(-1).type, 'status')
  s.reply({controlsAllowed:false, status:'请在手机领克卡片确认', actions:[], data:{}})
  assert.equal(s.visibleRows()[0].left.state, 'loading')
  s.timers.get(s.page.pollTimer).fn()
  s.reply({actions:[{...windowClosed, category:'LOCK'}, findCar]})
  assert.equal(s.visibleRows().flatMap(row => [row.left, row.right]).filter(tile => tile.state === 'loading').length, 1)
  s.timers.get(s.page.pollTimer).fn()
  s.reply({actions:[windowClosed, findCar]})
  assert.equal(s.page.note, '车窗 · 已完成')
  assert.equal(s.visibleRows()[0].left.state, 'closed')
  assert.equal(s.visibleRows()[0].right.enabled, true)
  assert.equal(s.sent.filter(request => request.type === 'execute').length, 1)
})

test('refused dispatches and successful momentary controls unlock after a fresh snapshot', () => {
  for (const [item, accepted] of [[windowOpen, false], [findCar, true]]) {
    const s = setup()
    startOperation(s, item)
    s.reply({type:'executeAck', accepted, note:'手机已响应'})
    assert.equal(s.visibleRows()[0].left.enabled, false)
    assert.equal(s.visibleRows()[0].left.state, item.controlState || 'legacy')
    assert.equal(s.sent.at(-1).type, 'status')
    s.page.prepare(item.slot)
    assert.equal(s.sent.at(-1).type, 'status')
    s.reply({actions:[item]})
    assert.equal(s.visibleRows()[0].left.enabled, true)
  }
})

test('unchanged and ambiguous operations expire or disconnect with an uncertain result, without replay', () => {
  for (const outcome of ['timeout', 'disconnect', 'vehicle-changed']) {
    const s = setup()
    startOperation(s, windowOpen)
    s.reply({type:'executeAck', accepted:null, note:'手机正在核对'})
    if (outcome === 'timeout') {
      s.advance(44900)
      s.reply({actions:[windowOpen]})
      assert.equal(s.timers.get(s.page.pollTimer).delay, 100)
      s.advance(100)
      s.timers.get(s.page.pollTimer).fn()
      s.reply({actions:[windowOpen]})
      assert.equal(s.visibleRows()[0].left.enabled, true)
    } else if (outcome === 'disconnect') {
      s.connection.onclose()
      s.timers.get(s.page.pollTimer).fn()
      s.reply({actions:[windowOpen]})
    } else {
      s.reply({actions:[windowClosed], data:{vehicle:'另一辆车'}})
    }
    assert.match(s.page.note, /结果待核对/)
    assert.equal(s.sent.filter(request => request.type === 'execute').length, 1)
  }
})

test('legacy full execute snapshots keep a stateful action pending after five seconds', () => {
  const s = setup()
  startOperation(s, windowOpen)
  s.reply({actions:[windowOpen], note:'已转交领克卡片'})
  s.advance(5500)
  s.timers.get(s.page.pollTimer).fn()
  s.reply({actions:[windowOpen]})
  assert.equal(s.visibleRows()[0].left.state, 'loading')
  assert.equal(s.timers.get(s.page.pollTimer).delay, 500)
  s.timers.get(s.page.pollTimer).fn()
  s.reply({actions:[windowClosed]})
  assert.equal(s.page.note, '车窗 · 已完成')
})

test('pill queues refresh during status and revalidates a queued control against the new snapshot', () => {
  const device = {getInfo: ({success}) => success({screenShape:'pill-shaped', screenWidth:192, screenHeight:490})}
  for (const [latest, expected] of [[windowOpen, 'prepare'], [windowClosed, 'refresh']]) {
    const s = setup(device)
    s.reply({actions:[windowOpen]})
    s.timers.get(s.page.pollTimer).fn()
    const time = s.feed().children.find(row => row.type === 'refresh').children[0]
    time.click()
    time.click()
    s.page.prepare(windowOpen.slot)
    assert.deepEqual(s.sent.map(msg => msg.type), ['status', 'status'])
    s.reply({actions:[latest]})
    assert.equal(s.sent.at(-1).type, expected)
    if (expected === 'prepare') {
      s.reply({actions:[latest], token:'valid', label:latest.label})
      s.page.cancel()
    }
    assert.equal(s.sent.filter(msg => msg.type === 'refresh').length, 1)
    assert.equal(s.sent.filter(msg => msg.type === 'execute').length, 0)
  }
})

test('manual refresh after a disconnect waits for a new snapshot before sending the request', () => {
  const s = setup()
  s.reply()
  s.connection.onclose()
  s.page.refresh()
  assert.equal(s.sent.at(-1).type, 'status')
  s.reply()
  assert.equal(s.sent.at(-1).type, 'refresh')
})

test('tapping the bottom timestamp refreshes once and polls for the new time', () => {
  const s = setup()
  const footer = () => s.feed().children.at(-1).children[0]
  s.reply()
  assert.equal(footer().value, '刷新车况\n14:30')
  footer().click(); footer().click()
  assert.equal(s.sent.at(-1).type, 'refresh')
  assert.equal(s.sent.filter(msg => msg.type === 'refresh').length, 1)
  assert.equal(footer().value, '正在刷新\n14:30')
  s.reply({note:'等候领克更新'})
  Array.from(s.timers.values()).find(t => t.delay === 1600).fn()
  assert.equal(s.sent.at(-1).type, 'status')
  s.reply({data:{vehicle:'测试车辆', time:'14:31'}})
  assert.equal(footer().value, '刷新车况\n14:31')
})
test('official confirmation is presented on the phone', () => {
  const s = setup()
  s.reply({actions:[], status:'请在手机领克卡片确认'})
  assert.equal(s.page.actions.length, 0)
  assert.match(s.page.status, /在手机/)
})
test('leaving action page discards late prepare response', () => {
  const s = setup()
  s.reply(); s.page.controls(); s.page.prepare(slot.slot); s.page.home()
  s.reply({token:'t', label:'关窗'})
  assert.equal(s.page.mode, 'home')
  assert.equal(s.page.choice, null)
})

test('home paints a single readable default with missing firmware modules and either phone appearance', () => {
  const offline = setup({}, true)
  const title = offline.feed().children[0]
  assert.equal(title.children[0].value, '小领通')
  assert.match(offline.feed().children.find(row => row.type === 'connection').children.at(-1).value, /连接/)
  assert.equal(offline.sent.length, 0)
  const s = setup()
  for (const appearance of ['light', 'dark']) {
    s.reply({appearance})
    assert.equal(s.feed().children[0].children[0].value, '测试车辆')
    assert.equal(compiledRule(s.page, 'page').backgroundColor, '#0a0d14')
    s.page.refresh()
  }
})

test('a lost connection retries immediately, backs off and never repeats a prepared action', () => {
  const s = setup()
  s.reply(); s.page.prepare(slot.slot)
  s.connection.onclose()
  assert.equal(s.page.choice, null)
  const retry = delay => Array.from(s.timers.values()).find(t => t.delay === delay).fn()
  retry(0)
  assert.equal(s.sent.at(-1).type, 'status')
  s.connection.onerror()
  retry(1000)
  s.reply()
  s.connection.onclose()
  retry(0)
  assert.equal(s.sent.filter(message => message.type === 'execute').length, 0)
})

test('offline standard screen has two solar settings and a manual time picker', () => {
  const s = setup(undefined, true)
  assert.equal(s.page.skyMode, 'follow')
  assert.equal(s.page.template(s.page).tag, 'div')
  s.page.showSettings()
  const theme = s.feed().children.find(row => row.type === 'theme').children[0]
  theme.click()
  assert.ok(s.feed().children.some(row => row.type === 'solarTitle'))
  assert.match(s.page.solarInfo, /本机/);
  assert.equal(s.feed().children.some(row => row.type === 'auto'), false)
  s.feed().children.find(row => row.type === 'manual').children[0].click()
  assert.equal(s.feed().children.find(row => row.type === 'sunrise').children[1].tag, 'picker')
  s.page.setSunrise({hour:13, minute:0})
  assert.equal(s.page.riseTime, '13:00')
  assert.equal(s.backdrop()?.src, '/common/sky/night.png')
  s.page.setSunset({hour:13, minute:0})
  assert.equal(s.page.setTime, '18:00')
  assert.equal(compiledRule(s.page, 'themeBackdrop').objectFit, 'cover')
})

test('standard screen uses the default scene until assets arrive and allows a local solar override', () => {
  const s = setup()
  const image = () => s.backdrop()?.src
  const solarInfo = '日出 06:29 · 日落 17:11'
  assert.equal(s.sent[0].bandTheme, true)
  s.reply({theme:'COAST', themeNight:true, solarInfo})
  assert.equal(s.page.solarInfo, solarInfo)
  assert.equal(image(), '/common/sky/night.png')
  s.page.setSkyMode('manual')
  assert.equal(image(), '/common/sky/day.png')
  s.page.showSettings()
  s.feed().children.find(row => row.type === 'scene').children[0].click()
  assert.equal(s.feed().children.find(row => row.type === 'sceneInfo').children[0].value, '手机未提供主题包')
  assert.equal(s.feed().children.some(row => row.type === 'sceneOption'), false)
  s.page.home()
  s.page.open()
  assert.equal(s.sent.at(-1).bandThemeChoice, undefined)
  s.reply({theme:'ALPINE', themeNight:true, solarInfo})
  assert.equal(image(), '/common/sky/day.png')
  s.page.setSkyMode('follow')
  assert.equal(image(), '/common/sky/night.png')
  s.page.open()
  assert.equal('bandThemeChoice' in s.sent.at(-1), false)
})

function selectScene(s, scene) {
  s.page.showSettings()
  s.feed().children.find(row => row.type === 'scene').children[0].click()
  s.page.selectScene(scene)
}
function sceneStorage() {
  const values = new Map()
  return {values, get({key, success}) { success(values.get(key)) }, set({key, value}) { values.set(key, value) }}
}

test('stale and absent replies cannot reset a picked scene or flood a legacy phone', () => {
  for (const ignored of [{}, {theme:'SKY'}]) {
    const store = sceneStorage(), s = setup(undefined, false, 12, store)
    selectScene(s, 'CITY') // The initial status was already in flight.
    s.reply({theme:'SKY', actions:[]})
    assert.equal(s.sent.at(-1).bandThemeChoice, 'CITY')
    for (let i = 0; i < 12; i++) {
      const count = s.sent.length
      s.reply({...ignored, actions:[]})
      assert.equal(s.sent.length, count)
      assert.equal(s.page.theme, 'CITY')
      assert.equal(s.backdrop()?.src, '/common/sky/day.png')
      const timer = s.timers.get(s.page.pollTimer)
      assert.equal(timer.delay, 5000)
      s.advance(timer.delay); timer.fn()
    }
    assert.equal(s.sent.filter(r => r.bandThemeChoice === 'CITY').length, 3)
    s.page.onHide(); s.page.onShow()
    assert.equal(s.sent.at(-1).bandThemeChoice, 'CITY')
    s.connection.onclose(); s.page.open()
    assert.equal(s.page.theme, 'CITY')
    s.page.onDestroy()
    const next = setup(undefined, false, 12, store)
    assert.equal(next.sent[0].bandThemeChoice, 'CITY')
    next.reply({theme:'CITY'}) // .36 companion confirms without a revision.
    next.page.open(); assert.equal(next.sent.at(-1).bandThemeChoice, undefined)
    next.reply({theme:'RIDGE'})
    assert.equal(next.page.theme, 'RIDGE')
  }
})

test('changing between external themes on a running watch refetches the offer without a restart', () => {
  const s = setup()
  const offer = {key:'b'.repeat(64), title:'光栅棱镜', scene:'prism', dayCrc:123, nightCrc:456}
  const push = () => s.connection.onmessage({data:{v:1, type:'bandThemeChanged', theme:'SKY', bandThemeRevision:20}})
  s.reply({theme:'SKY', bandThemeRevision:20, remoteTheme:null})

  s.page.open()
  const pending = s.page.pending
  push(); push()
  assert.equal(s.page.pending, pending)
  const sent = s.sent.length
  s.reply({theme:'SKY', bandThemeRevision:20, remoteTheme:null})
  assert.equal(s.sent.length, sent + 1)
  assert.equal(s.sent.at(-1).type, 'status')
  s.reply({theme:'SKY', bandThemeRevision:20, remoteTheme:offer})
  const next = s.timers.get(s.page.pollTimer)
  assert.ok(next.delay < 500)
  s.advance(next.delay); next.fn()
  assert.equal(s.sent.at(-1).type, 'themeAsset')
  assert.equal(s.sent.at(-1).key, offer.key)
})

test('phone theme pushes redraw immediately and preserve in-flight status and confirmation', () => {
  const store = sceneStorage(), s = setup(undefined, false, 12, store)
  assert.equal(s.sent[0].bandThemePush, true)
  const push = (theme, revision) => s.connection.onmessage({data:JSON.stringify({v:1,type:'bandThemeChanged',theme,bandThemeRevision:revision})})
  const image = () => s.backdrop()?.src
  const initial = s.page.pending
  push('COAST', 7)
  assert.equal(s.page.pending, initial)
  assert.equal(image(), '/common/sky/day.png')
  s.reply({theme:'SKY', bandThemeRevision:0, themeNight:true, solarInfo:'手机日落'})
  assert.equal(image(), '/common/sky/night.png')
  for (const r of [6,7,-1,1.2,'8',null,undefined,1e15]) push('SKY', r)
  assert.equal(s.page.theme, 'COAST')
  assert.equal(s.page.pending.type, 'status')
  s.reply({theme:'COAST', bandThemeRevision:7, themeNight:true, solarInfo:'手机日落'})
  s.page.prepare(slot.slot)
  const prepare = s.page.pending, timer = s.page.requestTimer
  push('ALPINE', 8)
  assert.equal(s.page.pending, prepare)
  assert.equal(s.page.requestTimer, timer)
  assert.equal(s.page.solarInfo, '手机日落')
  assert.equal(image(), '/common/sky/night.png')
  s.reply({token:'valid', label:slot.label})
  assert.equal(s.page.mode, 'confirm')
  push('RIDGE', 9)
  assert.equal(s.page.mode, 'confirm')
  assert.equal(s.page.theme, 'RIDGE')
  s.page.onDestroy()
  assert.equal(setup(undefined, true, 12, store).page.theme, 'RIDGE')
})

test('theme transfer resumes only after the same offer is confirmed on reconnect or wake', () => {
  const day = readFileSync(path.resolve(__dirname, '../../themes/circuit/vela-day.png'))
  const offer = {key:'b'.repeat(64), title:'脉冲电路', dayCrc:123, nightCrc:456}
  for (const interrupt of ['sleep', 'disconnect']) {
    const s = setup()
    s.reply({remoteTheme:offer})
    s.page.open()
    s.reply({type:'themeAsset', key:offer.key, part:'day', offset:0, length:day.length,
      chunk:day.subarray(0,1536).toString('base64')})
    if (interrupt === 'sleep') { s.page.onHide(); s.page.onShow() }
    else { s.connection.onclose(); s.page.open() }
    assert.equal(s.sent.at(-1).type, 'status')
    s.reply({remoteTheme:offer})
    s.page.open()
    assert.equal(s.sent.at(-1).offset, 1536)
    s.page.onHide(); s.page.onShow()
    s.reply({remoteTheme:{...offer, key:'c'.repeat(64)}})
    s.page.open()
    assert.equal(s.sent.at(-1).offset, 0)
  }
})

test('missing theme writer callback releases the queue and a late write cannot replace new artwork', () => {
  const writes = [], metadata = []
  const store = {get({success}) { success() }, set({key,value}) { if (key === 'garageThemeArt') metadata.push(value) }}
  const file = {writeText({success}) { success() }, writeArrayBuffer(args) { writes.push(args) },
    delete({complete}) { complete() }}
  const s = setup(undefined, false, 12, store, file)
  const offer = {key:'a'.repeat(64), title:'第一套', dayCrc:1, nightCrc:2}
  const next = {...offer, key:'b'.repeat(64), title:'第二套'}
  s.reply({remoteTheme:offer})
  s.page.saveRemoteTheme(s.page.themeTarget, png, png)
  const timeout = [...s.timers.values()].find(t => t.delay === 8000)
  timeout.fn()
  assert.match(s.page.themeSyncInfo, /超时/)
  s.page.open()
  s.reply({remoteTheme:next})
  s.page.saveRemoteTheme(s.page.themeTarget, png, png)
  assert.notEqual(writes[0].uri, writes[1].uri)
  writes[1].success(); writes[2].success(); writes[0].success()
  assert.equal(s.page.themeActive.key, next.key)
  assert.equal(JSON.parse(metadata.at(-1)).key, next.key)
  assert.equal(s.page.themeSyncInfo, '已同步 · 第二套')
})

test('only a cold read-only status gets one early probe; late responses cannot complete refresh', () => {
  const s = setup()
  const first = s.sent[0]
  s.timers.get(s.page.firstProbeTimer).fn()
  assert.equal(s.sent[1], first)
  s.reply()
  assert.equal(s.timers.has(s.page.firstProbeTimer), false)
  s.page.refresh()
  const refresh = s.page.pending
  s.connection.onmessage({data:{v:1, id:first.id}})
  assert.equal(s.page.pending, refresh)
  assert.equal(s.sent.filter(r => r.type === 'refresh').length, 1)
})

test('a missing cache-restore callback does not block the next theme transfer', () => {
  let restore
  const store = {get({key, success}) { if (key === 'garageThemeArt') restore = success; else success() }}
  const s = setup(undefined, false, 12, store)
  assert.equal(s.page.themeRestoring, true)
  const offer = {key:'b'.repeat(64), title:'光栅棱镜', scene:'prism', dayCrc:123, nightCrc:456}
  s.reply({remoteTheme:offer})
  const timeout = [...s.timers.values()].find(t => t.delay === 2500)
  s.advance(timeout.delay); timeout.fn()
  assert.equal(s.page.themeRestoring, false)
  restore(JSON.stringify({...offer, key:'a'.repeat(64), slot:0}))
  assert.equal(s.page.themeActive, null)
  s.timers.get(s.page.pollTimer).fn()
  assert.equal(s.sent.at(-1).type, 'themeAsset')
  assert.equal(s.sent.at(-1).key, offer.key)
})

test('a later tap survives the first echo; a newer phone edit wins a revision conflict', () => {
  const s = setup()
  s.reply({theme:'SKY', bandThemeRevision:5})
  selectScene(s, 'RIDGE')
  assert.equal(s.sent.at(-1).bandThemeBaseRevision, 5)
  selectScene(s, 'CITY')
  s.reply({theme:'RIDGE', bandThemeRevision:6})
  assert.equal(s.page.theme, 'CITY')
  assert.equal(s.sent.at(-1).bandThemeChoice, 'CITY')
  assert.equal(s.sent.at(-1).bandThemeBaseRevision, 6)
  s.reply({theme:'COAST', bandThemeRevision:7, bandThemeConflict:true})
  assert.equal(s.page.theme, 'COAST')
  selectScene(s, 'ALPINE')
  s.connection.onmessage({data:{v:1,type:'bandThemeChanged',theme:'CITY',bandThemeRevision:8}})
  s.reply({theme:'COAST',bandThemeRevision:7})
  assert.equal(s.page.theme, 'CITY')
})

test('theme restores independently of first reply, a more recent tap, or unavailable storage', () => {
  const saved = JSON.stringify({scene:'ALPINE',pending:true,revision:23})
  for (const tap of [false, true]) {
    let restore
    const s = setup(undefined, false, 12, {get({key,success}) { if (key === 'garageScene') restore = success }, set() { throw Error('unavailable') }})
    s.reply({theme:'SKY',bandThemeRevision:0})
    if (tap) selectScene(s, 'RIDGE')
    restore(saved)
    assert.equal(s.page.theme, tap ? 'RIDGE' : 'ALPINE')
    s.page.onHide(); s.page.onShow()
    assert.equal(s.sent.at(-1).bandThemeChoice, tap ? 'RIDGE' : 'ALPINE')
  }
  const offline = setup(undefined, true)
  selectScene(offline, 'CITY')
  offline.page.onHide(); offline.page.onShow()
  assert.equal(offline.backdrop()?.src, '/common/sky/day.png')
})

test('capsule never sends or applies shared themes', () => {
  const s = setup({getInfo:({success}) => success({screenShape:'pill-shaped', screenWidth:192, screenHeight:490})})
  assert.equal('bandTheme' in s.sent[0], false)
  assert.equal('bandThemePush' in s.sent[0], false)
  s.connection.onmessage({data:{v:1,type:'bandThemeChanged',theme:'COAST',bandThemeRevision:50}})
  assert.equal(s.page.theme, 'SKY')
  s.reply({theme:'CITY', themeNight:true})
  assert.equal(s.backdrop()?.src, undefined)
  assert.equal(s.feed().children.some(row => ['theme','scene'].includes(row.type)), false)
})

test('offline daylight boundaries and a failed theme store cannot prevent startup', () => {
  const broken = {get() { throw Error('unavailable') }, set() { throw Error('unavailable') }}
  for (const [hour, expect] of [[5,'night'],[6,'day'],[17,'day'],[18,'night']]) {
    const s = setup(undefined, true, hour, broken)
    assert.equal(s.backdrop()?.src, '/common/sky/' + expect + '.png')
    s.page.setSkyMode('manual')
    assert.equal(s.page.skyMode, 'manual')
  }
  let delayed
  const s = setup(undefined, true, 12, {get({key, success}) { if (key === 'skyMode') delayed = success }})
  s.page.setSkyMode('manual')
  delayed('night')
  assert.equal(s.page.skyMode, 'manual')
})

test('battery-only cards collapse the fuel row and preserve a reported zero on vehicle change', () => {
  const s = setup()
  for (const fuel of ['27.8 L', undefined, null, '— L', 0]) {
    s.reply({data:{battery:'65%', fuel}})
    const row = s.feed().children.find(item => ['energy','batteryOnly'].includes(item.type))
    assert.equal(row.children[0].children[1].value, '65%')
    if (fuel === 0 || fuel === '27.8 L') assert.equal(row.children[1].children[1].value, String(fuel))
    else { assert.equal(row.type, 'batteryOnly'); assert.equal(row.children.length, 1) }
    s.page.refresh()
  }
})

for (const [width, height, shape, themed] of [
  [192, 490, 'pill-shaped', false], [212, 520, 'rect', false], [192, 490, undefined, false],
  [336, 480, 'rect', true], [480, 480, 'circle', true]
]) {
  test(`appearance follows the screen shape after late detection on ${width}x${height} ${shape}`, () => {
    let detected, reads = 0
    const s = setup({getInfo: ({success}) => { detected = success }}, false, 12,
      {get({key, success}) {
        if (key === 'garageControlLayout') return success(undefined)
        reads++
        success(JSON.stringify({mode:'manual', rise:780, set:1080}))
      }})
    const themeRow = () => s.feed().children.some(row => row.type === 'settings')
    const image = () => s.backdrop()?.src
    assert.equal(themeRow(), true)
    assert.equal(image(), undefined)
    detected({screenWidth:width, screenHeight:height, screenShape:shape})
    assert.equal(themeRow(), true)
    s.feed().children.find(row => row.type === 'settings').children[0].click()
    assert.ok(s.feed().children.some(row => row.type === 'settingsRefresh'))
    assert.equal(s.feed().children.some(row => row.type === 'scene'), themed)
    s.feed().children.find(row => row.type === 'back').children[0].click()
    assert.equal(reads, themed ? 3 : 0) // Scene, solar settings and downloaded artwork.
    assert.equal(image(), themed ? '/common/sky/night.png' : undefined)
    s.reply({data:{battery:'50%', vehicle:'纯电车'}})
    assert.ok(s.feed().children.some(row => row.type === 'batteryOnly'))
    assert.ok(s.feed().children.some(row => row.type === 'refresh'))
    s.page.prepare(slot.slot)
    s.reply({token:'test', label:slot.label})
    assert.equal(s.page.mode, 'confirm')
    assert.equal(image(), themed ? '/common/sky/night.png' : undefined)
  })
}

const pillIcons = {getInfo:({success}) => success({screenShape:'pill-shaped', screenWidth:192, screenHeight:490})}
const iconAction = {...slot, category:'WINDOW', controlState:'closed'}
for (const [layout, device] of [['square', undefined], ['standard single', {getInfo:({success}) => success({...square, screenHeight:396})}], ['capsule', pillIcons]]) {
  test(`${layout} maps bundled art by reported category and keeps unchanged rows mounted`, () => {
    const s = photoSession(device)
    // These are the companion's ControlCategory names. Keys and slot order deliberately carry no category information.
    const categories = ['WINDOW','FIND','LOCK','OTHER','CLIMATE','TRUNK','ENGINE','SUNROOF','future/category']
    const actions = categories.map((category, i) => ({...iconAction, category, slot:'s' + i, key:'opaque-' + i,
      controlName:category, bandIconPng:encoded}))
    const tiles = () => s.feed().children.filter(row => row.type === 'actionSingle' || row.type === 'actionpair').flatMap(row => row.children)
    function checkBadge(badge, filename) {
      assert.equal(badge.children.length, 1)
      const [image] = badge.children
      assert.equal(image.src, '/common/controls/local/' + filename + '.png')
      const packaged = readFileSync(path.join(__dirname, '../build', image.src))
      assert.equal(packaged.subarray(0, 8).toString('hex'), '89504e470d0a1a0a')
      if (filename.startsWith('open/')) assert.equal(badge.style.backgroundColor, '#192b3e')
      assert.equal(image.complete, undefined)
      assert.equal(image.error, undefined)
    }
    s.reply({actions})
    for (const [i, tile] of tiles().entries()) checkBadge(tile.children[0], i === 8 ? 'OTHER' : categories[i])
    const rows = s.visibleRows()
    assert.equal(s.poll().bandPhoto, layout === 'capsule' ? undefined : true)
    s.reply({actions})
    assert.equal(s.visibleRows(), rows)
    const rekeyed = actions.map((item, i) => ({...item, key:'new-' + (8 - i)}))
    s.poll(); s.reply({actions:rekeyed})
    assert.equal(s.visibleRows(), rows)
    const pending = rekeyed.map((item, i) => ({...item, controlState:i % 2 ? 'loading' : 'unknown'}))
    s.poll(); s.reply({actions:pending})
    for (const [i, tile] of tiles().entries()) {
      checkBadge(tile.children[0], 'day/' + (i === 8 ? 'OTHER' : categories[i]))
      assert.equal(tile.disabled, true)
    }
    const momentary = {...rekeyed[1], controlState:undefined, momentary:true}
    s.poll(); s.reply({actions:[momentary]})
    checkBadge(tiles()[0].children[0], 'FIND')
    const reversed = rekeyed.slice().reverse().map(item => ({...item, controlState:'open'}))
    s.poll(); s.reply({actions:reversed})
    const openArt = ['day/OTHER','open/SUNROOF','open/ENGINE','open/TRUNK','open/CLIMATE','day/OTHER','open/LOCK','day/FIND','open/WINDOW']
    for (const [i, tile] of tiles().entries()) checkBadge(tile.children[0], openArt[i])
    for (const [i, action] of reversed.entries()) {
      tiles()[i].click()
      assert.equal(s.sent.at(-1).key, action.key)
      assert.equal(s.sent.at(-1).slot, action.slot)
      s.reply({actions:reversed, token:'ticket', label:action.label})
      checkBadge(s.feed().children[0].children[1], openArt[i])
      s.page.cancel()
    }
    assert.equal(s.sent.some(request => 'bandIconKey' in request), false)
    assert.equal(s.writes.length, 0)
  })
}

test('standard screen stores only a complete verified theme, restores it offline and clears it on request', () => {
  const {crc32} = require('node:zlib')
  const loadImages = name => Object.fromEntries(['day','night'].map(part => [
    part, readFileSync(path.resolve(__dirname, '../../themes', name, 'vela-' + part + '.png'))
  ]))
  const images = loadImages('city')
  const offer = {key:'a'.repeat(64), title:'城市天际线', dayCrc:crc32(images.day), nightCrc:crc32(images.night)}
  const files = new Map(), saved = new Map()
  const store = {
    get({key, success}) { success(saved.get(key)) },
    set({key, value, success}) { saved.set(key, value); if (success) success() }
  }
  const file = {
    writeText({uri, success}) { files.set(uri, null); success() },
    writeArrayBuffer({uri, buffer, success}) { files.set(uri, Buffer.from(buffer)); success() },
    delete({uri, complete}) { files.delete(uri); complete() }
  }
  const s = setup(undefined, false, 12, store, file)
  assert.equal(s.sent[0].remoteThemes, true)
  s.reply({remoteTheme:offer, themeNight:false})
  assert.equal(s.page.remoteBackdrop, '')

  function transfer(target, count, night, artwork = images) {
    let chunks = 0
    for (let polls = 0; chunks < count && polls < count * 2; polls++) {
      const timer = s.timers.get(s.page.pollTimer)
      assert.ok(timer)
      s.advance(timer.delay); timer.fn()
      const request = s.sent.at(-1)
      if (request.type === 'status') {
        s.reply({remoteTheme:target, themeNight:night})
        continue
      }
      assert.equal(request.type, 'themeAsset')
      assert.equal(request.key, target.key)
      const image = artwork[request.part]
      const end = Math.min(image.length, request.offset + 1536)
      chunks++
      s.reply({type:'themeAsset', key:target.key, part:request.part, offset:request.offset,
        length:image.length, chunk:image.subarray(request.offset, end).toString('base64')})
      if (target === offer && chunks < count) assert.equal(s.page.remoteBackdrop, '')
    }
    assert.equal(chunks, count)
  }
  const dayChunks = Math.ceil(images.day.length / 1536)
  transfer(offer, dayChunks + Math.ceil(images.night.length / 1536), false)
  assert.equal(s.page.sceneLabel, '主题 · 城市天际线')
  assert.ok(s.page.remoteBackdrop.endsWith('-day.png'))
  assert.deepEqual(files.get(s.page.remoteBackdrop), images.day)
  assert.equal(s.backdrop().src, s.page.remoteBackdrop)

  const circuit = loadImages('circuit')
  const next = {key:'c'.repeat(64), title:'脉冲电路', scene:'circuit', dayCrc:crc32(circuit.day), nightCrc:crc32(circuit.night)}
  s.connection.onmessage({data:{v:1, type:'bandThemeChanged', theme:'SKY', bandThemeRevision:8}})
  assert.equal(s.page.pending.type, 'status')
  s.reply({theme:'SKY', bandThemeRevision:8, remoteTheme:next, themeNight:false})
  assert.equal(s.page.sceneLabel, '主题 · 城市天际线')
  transfer(next, Math.ceil(circuit.day.length / 1536) + Math.ceil(circuit.night.length / 1536), false, circuit)
  assert.equal(s.page.sceneLabel, '主题 · 脉冲电路')
  assert.deepEqual(files.get(s.page.remoteBackdrop), circuit.day)

  s.page.open()
  const bad = {...offer, key:'b'.repeat(64), dayCrc:0}
  s.reply({remoteTheme:bad, themeNight:true})
  const oldNight = s.page.remoteBackdrop
  assert.deepEqual(files.get(oldNight), circuit.night)
  transfer(bad, dayChunks, true)
  assert.equal(s.page.remoteBackdrop, oldNight)
  assert.equal(files.size, 2)

  const restarted = setup(undefined, false, 12, store, file)
  assert.equal(restarted.page.sceneLabel, '主题 · 脉冲电路')
  restarted.reply({remoteTheme:null})
  assert.equal(restarted.page.remoteBackdrop, '')
  assert.equal(restarted.page.sceneLabel, '主题 · 蓝天与星空')
  assert.equal(files.size, 0)
})

// Official models exercise measured dimensions through the compiled Vela template.
for (const [model, width, height, shape] of [
  ['Xiaomi Watch S1 Pro', 480, 480, 'circle'], ['Xiaomi Watch H1', 466, 466, 'circle'],
  ['Xiaomi Watch S3', 466, 466, 'circle'], ['Xiaomi Watch S4 / S4 Sport / 41 mm', 466, 466, 'circle'],
  ['Xiaomi Watch S5', 480, 480, 'circle'], ['REDMI Watch 4', 390, 450, 'rect'],
  ['REDMI Watch 5 / 6', 432, 514, 'rect'], ['Xiaomi Smart Band 8 Pro / 9 Pro', 336, 480, 'rect'],
  ['Xiaomi Smart Band 9', 192, 490, 'pill-shaped'], ['Xiaomi Smart Band 10', 212, 520, 'pill-shaped'],
  ['small rectangular firmware reports', 176, 220, 'rect'],
  ['REDMI Watch 4 without shape', 390, 450, undefined]
]) {
  test(model + ' scales its artwork and keeps photo and motion on supported shapes', () => {
    const s = setup({getInfo: ({success}) => success({screenWidth:width, screenHeight:height, screenShape:shape})})
    const pill = shape === 'pill-shaped'
    assert.equal(s.page.layout.supportsTheme, !pill)
    s.reply()
    const fallback = () => s.feed().children.find(row => row.type === 'carPhoto')
    if (pill) {
      assert.equal(s.backdrop(), undefined)
      assert.equal(s.motion(), undefined)
      assert.equal(fallback(), undefined)
      assert.equal(s.page.layout.single, true)
      return
    }
    assert.equal(s.backdrop().src, '/common/sky/day.png')
    assert.deepEqual([s.backdrop().style.width,s.backdrop().style.height], ['336px', Math.round(designWidth*height/width)+'px'])
    assert.equal(s.motion().src, '/common/motion/sky-day.png')
    assert.ok(s.motion().classes.includes('fx-sky-day'))
    assert.equal(fallback().children[0].src, '/common/default-vehicle.png')
    assert.equal(fallback().style.height, width < 280 ? '104px' : '128px')
    if (width < 280) assert.equal(s.page.layout.single, true)
    s.page.onHide()
    assert.equal(s.motion(), undefined)
  })
}

test('every cached theme selects its own animation rule and overlay artwork', () => {
  const distinct = new Set()
  for (const scene of ['ridge', 'city', 'coast', 'alpine', 'bamboo', 'prism', 'circuit', 'paw', 'flux',
    'origami', 'tape', 'hamster', 'enamel']) {
    const saved = JSON.stringify({key:'a'.repeat(64), title:'主题', scene, dayCrc:1, nightCrc:2, slot:0})
    const s = setup(undefined, false, 12, {get({key, success}) { success(key === 'garageThemeArt' ? saved : undefined) }, set() {}})
    assert.equal(s.motion().src, '/common/motion/' + scene + '.png')
    assert.ok(s.motion().classes.includes('fx-' + scene))
    const animation = compiledRule(s.page, 'fx-' + scene).animationName
    assert.ok(animation)
    distinct.add(animation)
  }
  assert.equal(distinct.size, 13)
})

test('a cached theme with a legacy generic scene still uses the correct motion', () => {
  const cached = JSON.stringify({key:'a'.repeat(64), title:'布丁的午后', scene:'generic', dayCrc:1, nightCrc:2, slot:0})
  const s = setup(undefined, false, 12, {get({key, success}) { success(key === 'garageThemeArt' ? cached : undefined) }, set() {}})
  assert.ok(s.motion().classes.includes('fx-paw'))
  assert.equal(s.motion().src, '/common/motion/paw.png')
})

test('an old companion keeps matching cached artwork visible and falls back if it selects a different preset', () => {
  const saved = JSON.stringify({key:'a'.repeat(64), title:'海岸灯塔', scene:'coast', dayCrc:1, nightCrc:2, slot:0})
  const s = setup(undefined, false, 12, {get({key,success}) { success(key === 'garageThemeArt' ? saved : undefined) }, set() {}})
  assert.equal(s.page.sceneLabel, '主题 · 海岸灯塔')
  s.reply({theme:'COAST'})
  assert.equal(s.page.sceneLabel, '主题 · 海岸灯塔')
  s.page.open()
  s.reply({theme:'CITY'})
  assert.equal(s.backdrop().src, '/common/sky/day.png')
  assert.equal(s.page.themeActive.key, 'a'.repeat(64))
  s.page.open()
  s.reply({remoteTheme:JSON.parse(saved)})
  assert.equal(s.page.sceneLabel, '主题 · 海岸灯塔')
})
