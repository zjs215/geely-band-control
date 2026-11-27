import { rangeText, readSnapshot, writeSnapshot } from './snapshot.js'
import { actionIcon } from './action-icons.js'
import { DEFAULT_CAR_IMAGE, readCachedCarImage, saveCarImage } from './car-image.js'
import { persistControlIconCache, readCachedControlIcons, saveControlIcon } from './control-icons.js'
import { CLASSIC_THEME, THEME_IDS, THEME_NAMES, parseThemeStorage, readTheme, themeVariant,
  themeDelay, themeImage, themeTimeLabel } from './theme.js'
import { DEFAULT_LAYOUT, detectDeviceProfile, layoutClasses, layoutConfig, variantClass, watchControlsClasses, watchHomeClasses } from './layout.js'
import manifest from '../manifest.json'

const ACTIONS = [
  ['lock', '车锁'],
  ['climate', '空调'],
  ['window_vent', '窗微开'],
  ['trunk', '后备箱'],
  ['find', '寻车'],
  ['window_open', '窗全开'],
  ['heat', '快升温'],
  ['cool', '快降温'],
  ['purify', '净化'],
  ['defrost', '除霜'],
  ['sentry', '哨兵'],
  ['lynk_unlock', '功能1'], ['lynk_window', '功能2'],
  ['lynk_kjck', '功能3'], ['lynk_ccqk', '功能4']
]
const HOME_ACTIONS = 4
const STATUS_INTERVAL_MS = 12000
const CONNECT_WAIT_INTERVAL_MS = 400
const CONTROL_STATUS_INTERVAL_MS = 1500
const PHONE_CONFIRM_INTERVAL_MS = 2500
const TOAST_DURATION_MS = 2800
const PROTOCOL = 1
const MIN_PHONE_APP = '0.1.14'
const COMPACT_EXECUTE_ACK = 'compact_execute_ack'
const HELLO_TIMEOUT_MS = 2000
const READ_RETRY_MS = 500
const MAX_REPLY_BYTES = 2048
const EVENT_LOG_KEY = 'connection-events'
const EVENT_LOG_LIMIT = 20
const HAPTICS_KEY = 'control-haptics'
const THEME_LOCAL_KEY = 'theme-local'
const THEME_PHONE_KEY = 'theme-phone'
const RECT_TWO_PAGE_KEY = 'rect-two-page'
const CIRCLE_TWO_PAGE_KEY = 'circle-two-page'
const BAND_DISPLAY_FEATURE = 'band_display'
const FAILURE_PULSE_GAP_MS = 200
const CONTROLS_PULL_THRESHOLD = 50
const REQUEST_FEATURES = {
  refresh: 'refresh', prepare: 'confirm', execute: 'confirm', cancel: 'cancel', car_image: 'car_image',
  control_icon: 'control_icons'
}
const WINDOW_ACTIONS = ['window_vent', 'window_open']
const SWITCH_LABELS = { climate: '空调', heat: '升温', cool: '降温', purify: '净化', defrost: '除霜', sentry: '哨兵' }
const DEFAULT_VEHICLE_NAME = '我的座驾'
const NO_ACTION = {
  id: '', label: '', displayLabel: '', displayHint: '', labelToneStyle: '', style: '', tileStateStyle: '', icon: '', iconStyle: '', symbolStyle: '', labelStyle: '', hintStyle: '',
  watchOrbStyle: '', watchOrbSizeStyle: '', watchOrbDayStyle: '', watchOrbIconStyle: '', watchCellStyle: '',
  watchCellStateStyle: '', watchCellLabelStyle: '', watchCellCopyStyle: '', hint: ''
}

function normalizeVehicleName(value) {
  if (typeof value !== 'string') return DEFAULT_VEHICLE_NAME
  const trimmed = value.trim()
  return trimmed ? trimmed.slice(0, 24) : DEFAULT_VEHICLE_NAME
}

function versionParts(value) {
  const match = typeof value === 'string' && /^(\d+)\.(\d+)\.(\d+)$/.exec(value)
  return match ? match.slice(1).map(Number) : null
}

function compatibilityMessage(reply) {
  if (reply.v !== PROTOCOL || reply.protocol !== PROTOCOL) return '请将手机和手环更新到配套版本'
  const phone = versionParts(reply.phoneApp)
  if (!phone) return ''
  const band = versionParts(manifest.versionName)
  const minimum = versionParts(MIN_PHONE_APP)
  if (phone[0] > band[0]) return '请更新手环端到配套版本'
  if (phone[0] < band[0]) return '请更新手机端到配套版本'
  for (let i = 0; i < minimum.length; i++) {
    if (phone[i] < minimum[i]) return '请更新手机端到配套版本'
    if (phone[i] > minimum[i]) break
  }
  return ''
}

function exceedsReplyLimit(text) {
  if (text.length > MAX_REPLY_BYTES) return true
  let bytes = 0
  for (let i = 0; i < text.length; i++) {
    const unit = text.charCodeAt(i)
    if (unit < 0x80) bytes += 1
    else if (unit < 0x800) bytes += 2
    else {
      const next = text.charCodeAt(i + 1)
      if (unit >= 0xd800 && unit <= 0xdbff && next >= 0xdc00 && next <= 0xdfff) {
        bytes += 4
        i += 1
      } else bytes += 3
    }
    if (bytes > MAX_REPLY_BYTES) return true
  }
  return false
}

function isInt(value) {
  return typeof value === 'number' && isFinite(value) && Math.floor(value) === value
}

function isNonNegativeInt(value) {
  return isInt(value) && value >= 0
}

function validPercent(value) {
  return typeof value === 'number' && value >= 0 && value <= 100 ? value : null
}

function energyReading(km, percent) {
  if (typeof km === 'number' && km >= 0) return { value: String(km), unit: '\u00a0km' }
  if (percent !== null) return { value: String(percent), unit: '%' }
  return { value: '—', unit: '\u00a0km' }
}

function totalReading(data, electricPercent, fuelPercent) {
  const totalKm = rangeText(data.totalKm)
  if (totalKm !== '—') return { value: totalKm, unit: '\u00a0km' }
  const electricKm = rangeText(data.electricKm)
  const fuelKm = rangeText(data.fuelKm)
  if (electricKm !== '—' && fuelKm === '—') return { value: electricKm, unit: '\u00a0km' }
  if (electricPercent !== null && fuelPercent === null && fuelKm === '—') {
    return { value: String(electricPercent), unit: '%' }
  }
  return { value: '—', unit: '\u00a0km' }
}

function knownActions(ids) {
  if (!Array.isArray(ids)) return []
  return ids.filter((id, index) => ids.indexOf(id) === index && ACTIONS.some(action => action[0] === id))
}

function needsDirection(action, hasDirectionMap) {
  return action === 'lock' ? hasDirectionMap : WINDOW_ACTIONS.includes(action) || !!SWITCH_LABELS[action]
}

function readControlDirections(directions, lockState) {
  const result = {}
  if (directions) {
    for (const [action] of ACTIONS) {
      const direction = directions[action]
      if (action === 'lock' ? direction === 'lock' || direction === 'unlock'
        : needsDirection(action, true) && (direction === 'open' || direction === 'close')) {
        result[action] = direction
      }
    }
  } else if (lockState === 'locked' || lockState === 'unlocked') {
    result.lock = lockState === 'locked' ? 'unlock' : 'lock'
  }
  return result
}

function controlLabel(action, direction) {
  if (action === 'lock') {
    if (direction === 'lock') return '上锁'
    if (direction === 'unlock') return '解锁'
  }
  if (WINDOW_ACTIONS.includes(action) && direction === 'close') return '关窗'
  if (SWITCH_LABELS[action] && direction) return (direction === 'open' ? '开' : '关') + SWITCH_LABELS[action]
  return ACTIONS.find(item => item[0] === action)[1]
}

function readLynkControlLabels(brand, labels, mapped) {
  const result = {}
  if (brand !== 'LYNKCO' || !labels) return result
  for (const action of mapped) {
    if (action.indexOf('lynk_') !== 0 || typeof labels[action] !== 'string') continue
    const label = labels[action].trim()
    if (label && Array.from(label).length <= 3) result[action] = label
  }
  return result
}

function readControlStates(states, mapped) {
  const result = {}
  if (!states) return result
  for (const action of mapped) {
    if (states[action] === 'loading') result[action] = '加载中'
    else if (states[action] === 'unknown') result[action] = '状态未知'
  }
  return result
}

function actionRows(ids, available, linked, controlPending, directions, profile = DEFAULT_LAYOUT,
  labels = {}, day = false, states = {}, shortRect = false, syncedIcons = null) {
  const columns = layoutConfig(profile).columns
  const watchSize = columns === 1 ? 'single'
    : ids.length <= 1 ? 'hero' : ids.length === 2 ? 'pair' : ids.length === 3 ? 'trio' : 'grid'
  const watchTileClass = base => base + ' ' + base + '-' + watchSize
  const rect = profile === 'rect'
  function tile(id, solo) {
    const label = labels[id] || controlLabel(id, directions[id])
    const path = actionIcon(id, directions[id], labels[id])
    const synced = syncedIcons && typeof syncedIcons[id] === 'string' ? syncedIcons[id] : ''
    const icon = synced || (day ? path.replace('/common/images/', '/common/day-icons/') : path)
    const isAvailable = !states[id] && available.indexOf(id) >= 0
    const isActive = isAvailable && (directions[id] === 'close' || (id === 'lock' && directions[id] === 'lock'))
    const size = solo ? ' full-tile' : ''
    const state = isAvailable ? 'ready' : linked ? 'paused' : 'unassigned'
    const isLoading = states[id] === '加载中'
    let hint = ''
    if (!linked) hint = '待连接'
    else if (states[id]) hint = states[id]
    else if (!isAvailable && !controlPending) hint = '暂不可用'
    const displayLabel = isLoading ? '加载中' : label
    const displayHint = isLoading ? '' : hint
    const labelToneStyle = isLoading
      ? (day ? ' watch-cell-label-loading-day' : ' watch-cell-label-loading')
      : (day ? ' theme-ink-day' : '')
    return {
      id, label, displayLabel, displayHint, labelToneStyle, icon, hint,
      style: variantClass('tile ' + state + size, profile) + (rect ? ' tile-rect' : ''),
      tileStateStyle: day
        ? (isAvailable ? (isActive ? ' tile-active-day' : '') : ' tile-paused-day')
        : (isAvailable ? (isActive ? ' tile-active' : '') : ' tile-paused'),
      iconStyle: variantClass(isAvailable ? 'tile-icon' : 'tile-icon muted-icon', profile) + (rect ? ' tile-icon-rect' : ''),
      symbolStyle: variantClass(isAvailable ? 'tile-symbol symbol-ready' : 'tile-symbol', profile)
        + (rect ? ' tile-symbol-rect' : ''),
      labelStyle: variantClass('tile-label', profile),
      copyStyle: variantClass('tile-copy', profile) + (rect ? ' tile-copy-rect' : ''),
      hintStyle: variantClass('tile-hint', profile),
      watchOrbStyle: isAvailable
        ? (isActive ? 'watch-orb watch-orb-ready watch-orb-active' : 'watch-orb watch-orb-ready')
        : linked ? 'watch-orb watch-orb-paused' : 'watch-orb',
      watchOrbSizeStyle: 'watch-orb-' + watchSize,
      watchOrbDayStyle: day
        ? (isAvailable ? (isActive ? ' watch-orb-active-day' : ' watch-orb-ready-day') : ' watch-orb-paused-day')
        : '',
      watchOrbIconStyle: watchTileClass('watch-orb-icon') + (isAvailable ? '' : ' muted-icon'),
      watchCellStyle: watchTileClass('watch-cell') + (rect ? ' watch-cell-rect' : '')
        + (shortRect ? ' watch-cell-rect-short' : ''),
      watchCellStateStyle: day
        ? (isAvailable ? (isActive ? ' watch-cell-active-day' : ' watch-cell-ready-day') : ' watch-cell-paused-day')
        : (isAvailable ? (isActive ? ' watch-cell-active' : ' watch-cell-ready') : ' watch-cell-paused'),
      watchCellLabelStyle: watchTileClass('watch-cell-label'),
      watchCellCopyStyle: watchTileClass('watch-control-copy')
    }
  }

  const rows = []
  if (columns === 1) {
    for (let i = 0; i < ids.length; i += 1) {
      rows.push({ id: ids[i], first: tile(ids[i], true), second: NO_ACTION, third: NO_ACTION })
    }
    return rows
  }
  if (ids.length === 3) {
    rows.push({ id: ids[0], first: tile(ids[0], false), second: tile(ids[1], false), third: tile(ids[2], false) })
    return rows
  }
  for (let i = 0; i < ids.length; i += 2) {
    const second = ids[i + 1]
    rows.push({
      id: ids[i],
      first: tile(ids[i], !second),
      second: second ? tile(second, false) : NO_ACTION,
      third: NO_ACTION
    })
  }
  return rows
}

/** Build the QuickApp garage page component definition (full or lite variant). */
export function createGaragePage(options = {}) {
  const LITE = !!options.lite
  const INITIAL_LAYOUT = LITE ? 'rect' : DEFAULT_LAYOUT
  return {
  private: {
    layoutProfile: INITIAL_LAYOUT,
    ui: layoutClasses(INITIAL_LAYOUT),
    watchHomeSize: 'standard',
    watchHomeUi: watchHomeClasses('standard', INITIAL_LAYOUT),
    watchControlsUi: watchControlsClasses(INITIAL_LAYOUT),
    watchPage: 0,
    homeLayoutReady: false,
    controlsPullText: '',
    controlsPullOffset: 0,
    controlsPullProgress: 0,
    refreshing: false,
    vehicleName: DEFAULT_VEHICLE_NAME,
    brand: 'GALAXY',
    carImageSrc: '',
    displayRows: [],
    mode: '',
    modeLabel: '待连接',
    showTotalRange: false,
    total: '—',
    totalUnit: '\u00a0km',
    electric: '—',
    electricUnit: '\u00a0km',
    fuel: '—',
    fuelUnit: '\u00a0km',
    electricPercent: null,
    fuelPercent: null,
    motion: '等待车况',
    updated: '等待车况',
    emptyLabel: '连接手机后显示已映射车控',
    compatibilityNotice: '',
    settingsOpen: false,
    themeEditorOpen: false,
    themeEditorError: '',
    themeDraftChoice: 0,
    themeDraftMode: 0,
    themeTimesExpanded: false,
    themeDraftDay: '06:00',
    themeDraftNight: '18:00',
    themeNames: ['跟随手机'].concat(THEME_NAMES),
    themeModes: ['自动日夜', '固定日景', '固定夜景'],
    rangeBreakdown: [
      { id: 'electric', value: '—\u00a0km', percent: null },
      { id: 'fuel', value: '—\u00a0km', percent: null }
    ],
    themeArt: '',
    solidTheme: false,
    themeNight: false,
    themeSummary: '跟随手机 · 经典',
    themePhoneSummary: '经典',
    diagnosticLines: [],
    hapticsEnabled: true,
    rectTwoPage: false,
    circleTwoPage: true,
    toastText: '',
    busy: false,
    phoneConfirmation: false,
    controlPending: false,
    confirmTitle: '',
    confirmIcon: ''
  },

  computed: {
    isCircularShell() {
      return this.isCircularProfile()
    },
    useTwoPageShell() {
      return this.isTwoPageShell()
    },
    twoPageChecked() {
      return this.isCircularProfile() ? this.circleTwoPage : this.rectTwoPage
    },
    canChangeDisplay() {
      return !LITE && (this.layoutProfile === 'rect' || this.layoutProfile === 'capsule'
        || this.layoutProfile === 'circle' || this.layoutProfile === 'square')
    },
    singleCircleRangeText() {
      const parts = []
      if (this.total !== '—') parts.push(this.total + this.totalUnit)
      if (this.electric !== '—') parts.push('电 ' + this.electric + this.electricUnit)
      if (this.fuel !== '—') parts.push('油 ' + this.fuel + this.fuelUnit)
      return parts.join(' · ')
    },
    canEditTheme() {
      return !LITE && this.layoutProfile !== 'capsule'
    },
    displayCarImage() {
      return this.carImageSrc || DEFAULT_CAR_IMAGE
    },
    hasThemeSurface() {
      return LITE ? this.solidTheme : !!this.themeArt
    },
    themeDay() {
      return this.hasThemeSurface && !this.themeNight
    },
    themeInk() {
      return this.themeDay ? ' theme-ink-day' : ''
    },
    themeMuted() {
      return this.themeDay ? ' theme-muted-day' : ''
    },
    themeControl() {
      return this.themeDay ? ' theme-control-day' : ''
    },
    themeStage() {
      return this.hasThemeSurface ? (this.themeDay ? ' theme-stage-day' : ' theme-stage-night') : ''
    },
    themeIconPath() {
      return this.themeDay ? '/common/day-icons/' : '/common/images/'
    },
    themeConfirmIcon() {
      if (this.confirmIcon.indexOf('internal://') === 0 || this.confirmIcon.indexOf('data:') === 0) return this.confirmIcon
      const windowGlyph = this.confirmIcon.match(/\/(window_vent|window_open)\.png$/)
      if (windowGlyph) {
        return '/common/confirm-icons/' + (this.themeDay ? 'day/' : 'night/') + windowGlyph[1] + '.png'
      }
      return this.themeDay ? this.confirmIcon.replace('/common/images/', '/common/day-icons/') : this.confirmIcon
    },
    themePanelClass() {
      return this.hasThemeSurface ? (this.themeNight ? ' theme-panel-night' : ' theme-panel-day') : ''
    },
    themeTileClass() {
      return this.hasThemeSurface ? (this.themeNight ? ' theme-tile-night' : ' theme-tile-day') : ''
    },
    themeTileIconClass() {
      return this.hasThemeSurface ? ' theme-tile-icon' : ''
    },
    controlsPullTransform() {
      return JSON.stringify({ translateY: this.controlsPullOffset + 'px' })
    },
    refreshText() {
      if (this.phoneConfirmation) return '请在手机确认'
      if (this.controlPending) return '同步中…'
      if (this.refreshing) return '正在刷新…'
      if (this.busy) return '请稍候…'
      return this.updated
    }
  },

  onInit() {
    this.available = []
    this.mapped = []
    this.controlNotice = ''
    this.blockedControls = {}
    this.cachedVehicle = false
    this.controlDirectionsSupported = false
    this.controlDirections = {}
    this.lynkControlLabels = {}
    this.controlStates = {}
    this.rowsKey = ''
    this.confirmAction = ''
    this.confirmToken = ''
    this.connection = null
    this.linkOpenState = 0
    this.linkGeneration = 0
    this.handshakeComplete = false
    this.phoneApp = ''
    this.protocol = null
    this.features = null
    this.pending = null
    this.startTimer = null
    this.openTimer = null
    this.requestTimer = null
    this.retryTimer = null
    this.confirmTimer = null
    this.pollTimer = null
    this.reconnectTimer = null
    this.reconnectAttempt = 0
    this.statusOnly = false
    this.receiptShowing = false
    this.interruptedExecution = false
    this.toastTimer = null
    this.focusTimer = null
    this.sequence = 0
    this.ready = false
    this.visible = false
    this.destroyed = false
    this.linked = false
    this.hasReply = false
    this.reportedLinkIssue = false
    this.storedSnapshot = null
    this.snapshotChecked = false
    this.events = []
    this.eventStorage = null
    this.loadingEventLog = false
    this.loadedEventLog = false
    this.savingEventLog = false
    this.eventLogDirty = false
    this.hapticsLoaded = false
    this.rectTwoPageLoaded = false
    this.rectDisplayTimer = null
    this.circleTwoPageLoaded = false
    this.circleDisplayTimer = null
    this.displayRevision = 0
    this.displayPending = false
    this.deferredDisplay = null
    this.vibrator = undefined
    this.failurePulseTimer = null
    this.carImageHash = ''
    this.carImageUri = ''
    this.carImageFromCache = false
    this.failedCarImageHash = ''
    this.carImageCacheLoading = false
    this.carImageChecked = false
    this.cachedCarImage = null
    this.imageSync = null
    this.controlIconHashes = {}
    this.controlIconUris = {}
    this.controlIconFiles = {}
    this.controlIconOrder = []
    this.failedControlIconHashes = {}
    this.iconSyncQueue = []
    this.iconSync = null
    this.controlIconsLoading = false
    this.layoutDetected = false
    this.layoutResolved = false
    this.shortRect = false
    this.layoutReadyTimer = LITE ? null : setTimeout(() => {
      this.homeLayoutReady = true
      this.scheduleFocusActive()
    }, 450)
    this.localTheme = CLASSIC_THEME
    this.phoneTheme = CLASSIC_THEME
    this.followPhoneTheme = true
    this.themeTimer = null
    this.failedThemeArt = ''
    this.localThemeSaved = false
    this.phoneThemeReceived = false
    this.detectLayout()
    this.updateRangeBreakdown()
    this.loadThemes()
    this.loadSnapshot()
    this.loadCarImageCache()
  },

  loadControlIconCache() {
    if (this.controlIconsLoading || this.destroyed) return
    this.controlIconsLoading = true
    readCachedControlIcons(cached => {
      this.controlIconsLoading = false
      if (this.destroyed) return
      const hashes = Object.keys(cached.files)
      for (let i = 0; i < hashes.length; i++) {
        const hash = hashes[i]
        if (!this.failedControlIconHashes[hash] && !this.controlIconFiles[hash]) {
          this.controlIconFiles[hash] = cached.files[hash]
          if (this.controlIconOrder.indexOf(hash) < 0) this.controlIconOrder.push(hash)
        }
      }
      if (cached.brand === this.brand) {
        const actions = Object.keys(cached.actions)
        for (let i = 0; i < actions.length; i++) {
          const action = actions[i]
          const hash = cached.actions[action]
          const uri = this.controlIconFiles[hash]
          if (uri && !this.controlIconHashes[action]) {
            this.controlIconHashes[action] = hash
            this.controlIconUris[action] = uri
          }
        }
        this.updateRows()
      }
    }, () => {
      this.controlIconsLoading = false
    })
  },

  onControlIconError(action) {
    if (!action) return
    const uri = this.controlIconUris[action]
    const hash = this.controlIconHashes[action]
    if (!uri) return
    if (hash) {
      this.failedControlIconHashes[hash] = true
      delete this.controlIconFiles[hash]
      const idx = this.controlIconOrder.indexOf(hash)
      if (idx >= 0) this.controlIconOrder.splice(idx, 1)
    }
    const keys = Object.keys(this.controlIconHashes)
    for (let i = 0; i < keys.length; i++) {
      const id = keys[i]
      if (this.controlIconHashes[id] === hash || id === action) {
        delete this.controlIconHashes[id]
        delete this.controlIconUris[id]
      }
    }
    persistControlIconCache(this.brand, this.controlIconHashes, this.controlIconFiles, this.controlIconOrder)
    this.updateRows()
  },

  loadCarImageCache() {
    if (this.carImageHash || this.carImageCacheLoading || this.destroyed) return
    this.carImageCacheLoading = true
    readCachedCarImage((uri, hash, brand) => {
      this.carImageCacheLoading = false
      this.carImageChecked = true
      if (this.destroyed) return
      this.cachedCarImage = { uri, hash, brand }
      this.resolveCarImageCache()
      this.updateHomeLayoutReady()
    }, () => {
      this.carImageCacheLoading = false
      this.carImageChecked = true
      this.resolveCarImageCache()
      this.updateHomeLayoutReady()
    })
  },

  resolveCarImageCache() {
    if (this.destroyed || this.carImageHash) return
    const cached = this.cachedCarImage
    if (cached && cached.brand === this.brand && this.failedCarImageHash !== cached.hash) {
      this.carImageHash = cached.hash
      this.carImageUri = cached.uri
      this.carImageSrc = cached.uri
      this.carImageFromCache = true
      return
    }
    if ((this.snapshotChecked || this.hasReply) && this.carImageChecked && !this.carImageSrc) {
      this.carImageSrc = this.defaultCarImage()
    }
  },

  onCarImageError() {
    const fallback = this.defaultCarImage()
    if (!this.carImageSrc || this.carImageSrc === fallback) return
    if (this.carImageFromCache) {
      // A missing cache file can be recovered from the next status reply, even if its hash has not changed.
      this.cachedCarImage = null
      this.carImageUri = ''
      this.carImageHash = ''
      this.carImageFromCache = false
    } else if (this.carImageHash) this.failedCarImageHash = this.carImageHash
    this.carImageSrc = fallback
  },

  detectLayout() {
    if (this.layoutDetected) return
    this.layoutDetected = true
    detectDeviceProfile((profile, watchHomeSize, shortRect) => {
      if (this.destroyed) return
      this.applyLayoutProfile(profile, watchHomeSize, shortRect)
      this.layoutResolved = true
      this.updateHomeLayoutReady()
    })
  },

  applyLayoutProfile(profile, watchHomeSize, shortRect = false) {
    const nextSize = watchHomeSize || 'standard'
    const resolved = profile || INITIAL_LAYOUT
    const next = LITE ? (resolved === 'capsule' ? 'capsule' : 'rect') : resolved
    if (this.watchHomeSize !== nextSize || this.layoutProfile !== next || this.shortRect !== shortRect) {
      this.shortRect = shortRect
      this.watchHomeSize = nextSize
      this.watchHomeUi = watchHomeClasses(nextSize, next, shortRect)
      this.watchControlsUi = watchControlsClasses(next, shortRect)
    }
    if (this.layoutProfile === next) {
      if (!LITE && this.isCircularProfile() && !this.circleTwoPageLoaded) this.loadCircleTwoPageSetting()
      else if (this.canSyncBandDisplay() && !this.rectTwoPageLoaded) this.loadRectTwoPageSetting()
      if (LITE) this.updateRows()
      return
    }
    this.layoutProfile = next
    this.ui = layoutClasses(next)
    this.updateRows()
    this.refreshTheme()
    if (!LITE && this.isCircularProfile()) this.loadCircleTwoPageSetting()
    else if (this.canSyncBandDisplay()) this.loadRectTwoPageSetting()
    if (this.isTwoPageShell()) this.focusWatchSwiper()
    else this.scheduleFocusActive()
  },

  isCircularProfile() {
    return this.layoutProfile === 'circle' || this.layoutProfile === 'square'
  },

  canSyncBandDisplay() {
    return this.layoutProfile === 'rect' || this.layoutProfile === 'capsule'
  },

  updateHomeLayoutReady() {
    if (!this.layoutResolved || !this.snapshotChecked || !this.carImageChecked) return
    if (this.canSyncBandDisplay() && !this.rectTwoPageLoaded) return
    if (!LITE && this.isCircularProfile() && !this.circleTwoPageLoaded) return
    clearTimeout(this.layoutReadyTimer)
    this.homeLayoutReady = true
    this.scheduleFocusActive()
  },

  isTwoPageShell() {
    if (this.isCircularProfile()) return this.circleTwoPage
    return (LITE || this.canSyncBandDisplay()) && this.rectTwoPage
  },

  focusElement(id) {
    try {
      const target = this.$element && this.$element(id)
      if (target && typeof target.focus === 'function') {
        target.focus({ focus: true })
        return true
      }
    } catch (_) {
      // Focus routing is optional across Vela hosts.
    }
    return false
  },

  scheduleFocusActive() {
    if (!this.visible || this.destroyed) return
    const targetId = this.confirmTitle ? ''
      : !LITE && this.settingsOpen ? (this.themeEditorOpen ? 'themeEditorScroll' : 'settingsScroll')
        : !this.homeLayoutReady ? ''
          : this.isTwoPageShell() ? (LITE ? '' : 'watchSwiper') : (!LITE && this.isCircularProfile() ? 'circleSingleShell' : (LITE ? '' : 'homeScroll'))
    clearTimeout(this.focusTimer)
    this.focusTimer = null
    if (!targetId) return
    this.focusElement(targetId)
    this.focusTimer = setTimeout(() => {
      this.focusTimer = null
      if (this.visible && !this.destroyed) this.focusElement(targetId)
    }, 60)
  },

  focusWatchSwiper() {
    if (!this.isTwoPageShell() || this.confirmTitle || this.settingsOpen || !this.homeLayoutReady) return
    this.scheduleFocusActive()
  },

  onReady() {
    this.ready = true
    this.startSession()
  },

  onShow() {
    this.visible = true
    this.refreshTheme()
    this.reportedLinkIssue = false
    this.reconnectAttempt = 0
    this.statusOnly = this.hasReply || this.interruptedExecution
    if (this.interruptedExecution) {
      this.interruptedExecution = false
      this.showExecutionReceipt('unknown')
    }
    this.startSession()
    this.focusWatchSwiper()
  },

  startSession() {
    if (!this.ready || !this.visible || this.startTimer !== null
        || this.pollTimer !== null || this.pending) return
    // Let the first frame render before initializing optional native services.
    this.startTimer = setTimeout(() => {
      this.startTimer = null
      if (!this.visible) return
      if (!this.hasReply && this.storedSnapshot === null && !this.snapshotChecked) this.loadSnapshot()
      if (!this.carImageHash && !this.carImageSrc) this.loadCarImageCache()
      if (!Object.keys(this.controlIconUris).length) this.loadControlIconCache()
      if (!LITE) this.loadEventLog()
      this.loadHapticsSetting()
      this.loadRectTwoPageSetting()
      this.syncStatus()
    }, 100)
  },

  scheduleStatus() {
    clearTimeout(this.pollTimer)
    this.pollTimer = null
    if (!this.visible || this.pending || this.confirmTitle || this.openTimer !== null || this.reconnectTimer !== null
        || this.compatibilityNotice) return
    this.pollTimer = setTimeout(() => {
      this.pollTimer = null
      this.syncStatus()
    }, !this.handshakeComplete ? CONNECT_WAIT_INTERVAL_MS
      : this.phoneConfirmation ? PHONE_CONFIRM_INTERVAL_MS
      : this.controlPending ? CONTROL_STATUS_INTERVAL_MS : STATUS_INTERVAL_MS)
  },

  cancelReconnect() {
    clearTimeout(this.reconnectTimer)
    this.reconnectTimer = null
  },

  scheduleReconnect() {
    clearTimeout(this.pollTimer)
    this.pollTimer = null
    if (!this.visible || this.reconnectTimer !== null) return
    const attempt = this.reconnectAttempt
    this.reconnectAttempt = Math.min(5, attempt + 1)
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null
      this.syncStatus()
    }, attempt === 0 ? 0 : 1000 * Math.pow(2, attempt - 1))
  },

  loadSnapshot() {
    readSnapshot((snapshot, value) => {
      if (this.destroyed || this.hasReply || this.compatibilityNotice) return
      const brand = this.normalizeBrand(snapshot.brand)
      if (this.brand !== brand) {
        this.brand = brand
        this.carImageHash = ''
        this.carImageUri = ''
        this.carImageFromCache = false
        this.failedCarImageHash = ''
        this.carImageSrc = ''
      }
      if (typeof snapshot.vehicleName === 'string' && snapshot.vehicleName.trim()) {
        this.vehicleName = normalizeVehicleName(snapshot.vehicleName)
      }
      this.showVehicle(snapshot, true)
      this.storedSnapshot = value
      this.modeLabel = '离线缓存'
      this.mapped = knownActions(snapshot.mapped).slice(0, HOME_ACTIONS)
      this.lynkControlLabels = readLynkControlLabels(this.brand, snapshot.controlLabels, this.mapped)
      this.updateRows()
      this.snapshotChecked = true
      this.resolveCarImageCache()
      this.updateHomeLayoutReady()
    }, () => {
      this.snapshotChecked = true
      this.resolveCarImageCache()
      this.updateHomeLayoutReady()
    })
  },

  loadEventLog() {
    if (LITE || this.loadedEventLog || this.loadingEventLog) return
    this.loadingEventLog = true
    const loaded = value => {
      let history
      try {
        history = JSON.parse(value)
      } catch (_) {
        history = []
      }
      if (Array.isArray(history)) {
        const entries = history.filter(entry => entry && typeof entry.ts === 'number'
          && typeof entry.event === 'string')
        this.events = entries.concat(this.events).slice(-EVENT_LOG_LIMIT)
      }
      this.loadingEventLog = false
      this.loadedEventLog = true
      if (this.settingsOpen) this.updateDiagnosticLines()
      this.saveEventLog()
    }
    try {
      this.eventStorage = require('@system.storage')
      this.eventStorage.get({ key: EVENT_LOG_KEY, success: loaded, fail: () => loaded('') })
    } catch (_) {
      loaded('')
    }
  },

  recordEvent(event, request, detail = '') {
    if (LITE) return
    this.events.push({
      ts: Date.now(), event, id: request ? request.id : '', type: request ? request.type : '',
      action: request && request.action ? request.action : '', detail
    })
    if (this.events.length > EVENT_LOG_LIMIT) this.events.shift()
    this.eventLogDirty = true
    if (this.settingsOpen) this.updateDiagnosticLines()
    this.saveEventLog()
  },

  saveEventLog() {
    if (!this.eventStorage || !this.loadedEventLog || this.savingEventLog || !this.eventLogDirty) return
    this.eventLogDirty = false
    this.savingEventLog = true
    const saved = () => {
      this.savingEventLog = false
      this.saveEventLog()
    }
    try {
      this.eventStorage.set({ key: EVENT_LOG_KEY, value: JSON.stringify(this.events), complete: saved })
    } catch (_) {
      saved()
    }
  },

  updateDiagnosticLines() {
    this.diagnosticLines = this.events.slice(-3).reverse().map(entry => {
      const time = new Date(entry.ts).toTimeString().slice(0, 8)
      const summary = [time, entry.event, entry.type, entry.detail].filter(Boolean).join(' ')
      return entry.id ? summary + '\n' + entry.id : summary
    })
  },

  openSettings() {
    if (LITE || (this.busy && (!this.pending || this.pending.type !== 'hello')) || this.confirmTitle) return
    this.clearControlsPull()
    this.settingsOpen = true
    this.updateDiagnosticLines()
    this.scheduleFocusActive()
  },

  closeSettings() {
    this.settingsOpen = false
    this.scheduleFocusActive()
  },

  loadThemes() {
    try {
      this.themeStorage = require('@system.storage')
      if (!LITE) {
        this.themeStorage.get({ key: THEME_LOCAL_KEY, success: value => {
          if (this.destroyed || this.localThemeSaved) return
          const saved = parseThemeStorage(value)
          const theme = saved && readTheme(saved.theme)
          if (!theme) return
          this.followPhoneTheme = saved.followPhone !== false
          this.localTheme = theme
          this.refreshTheme()
        } })
      }
      this.themeStorage.get({ key: THEME_PHONE_KEY, success: value => {
        if (this.destroyed || this.phoneThemeReceived) return
        const saved = readTheme(parseThemeStorage(value))
        if (!saved) return
        this.phoneTheme = saved
        this.refreshTheme()
      } })
    } catch (_) {
      // Older hosts can still use the in-memory settings.
    }
  },

  storeTheme(key, value) {
    try {
      this.themeStorage.set({ key, value: JSON.stringify(value) })
    } catch (_) {
      // Keep the theme active for the current session.
    }
  },

  receiveTheme(value) {
    const next = readTheme(value)
    if (!next) return
    const changed = !this.phoneThemeReceived || JSON.stringify(next) !== JSON.stringify(this.phoneTheme)
    this.phoneThemeReceived = true
    if (!changed) return
    this.phoneTheme = next
    this.storeTheme(THEME_PHONE_KEY, next)
    this.refreshTheme()
  },

  refreshTheme(now = new Date()) {
    clearTimeout(this.themeTimer)
    this.themeTimer = null
    const followsPhone = LITE || !this.canEditTheme || this.followPhoneTheme
    const config = followsPhone ? this.phoneTheme : this.localTheme
    const variant = themeVariant(config, now)
    if (LITE) {
      this.themeArt = ''
      this.solidTheme = config.themeId !== 'classic'
    } else {
      const asset = themeImage(config, variant, this.layoutProfile)
      this.themeArt = asset === this.failedThemeArt ? '' : asset
    }
    this.themeNight = variant === 'night'
    this.updateRows()
    const name = THEME_NAMES[THEME_IDS.indexOf(config.themeId)]
    const detail = config.themeId === 'classic' ? name : name + ' · ' + (this.themeNight ? '夜景' : '日景')
    this.themeSummary = (followsPhone ? '跟随手机 · ' : '') + detail
    const phoneName = THEME_NAMES[THEME_IDS.indexOf(this.phoneTheme.themeId)]
    this.themePhoneSummary = '手机主题 · ' + phoneName
    const wait = themeDelay(config, now, variant)
    if (this.visible && wait > 0) this.themeTimer = setTimeout(() => this.refreshTheme(), wait)
  },

  onThemeImageError() {
    this.failedThemeArt = this.themeArt
    this.themeArt = ''
    this.updateRows()
  },

  openThemeEditor() {
    if (!this.canEditTheme) return
    this.themeDraftChoice = this.followPhoneTheme ? 0 : THEME_IDS.indexOf(this.localTheme.themeId) + 1
    this.themeDraftMode = this.localTheme.mode === 'fixed' ? (this.localTheme.variant === 'night' ? 2 : 1) : 0
    this.themeTimesExpanded = false
    this.themeDraftDay = themeTimeLabel(this.localTheme.dayStartMinute)
    this.themeDraftNight = themeTimeLabel(this.localTheme.nightStartMinute)
    this.themeEditorError = ''
    this.themeEditorOpen = true
    this.scheduleFocusActive()
  },

  closeThemeEditor() {
    this.themeEditorOpen = false
    this.scheduleFocusActive()
  },

  changeThemeChoice(event) {
    const index = Number(event.newSelected)
    if (isInt(index) && index >= 0 && index <= THEME_IDS.length) this.themeDraftChoice = index
    this.themeTimesExpanded = false
    this.themeEditorError = ''
  },

  changeThemeMode(event) {
    const index = Number(event.newSelected)
    if (isInt(index) && index >= 0 && index < this.themeModes.length) this.themeDraftMode = index
    this.themeTimesExpanded = false
    this.themeEditorError = ''
  },

  toggleThemeTimes() {
    this.themeTimesExpanded = !this.themeTimesExpanded
  },

  changeThemeDay(event) {
    this.changeThemeTime(event, 'themeDraftDay')
  },

  changeThemeNight(event) {
    this.changeThemeTime(event, 'themeDraftNight')
  },

  changeThemeTime(event, field) {
    const hour = Number(event.hour)
    const minute = Number(event.minute)
    if (isInt(hour) && hour >= 0 && hour < 24 && isInt(minute) && minute >= 0 && minute < 60) {
      this[field] = themeTimeLabel(hour * 60 + minute)
      this.themeEditorError = ''
    }
  },

  saveThemeEditor() {
    const follow = this.themeDraftChoice === 0
    if (!follow) {
      const toMinute = value => Number(value.slice(0, 2)) * 60 + Number(value.slice(3))
      const next = this.themeDraftChoice === 1 ? CLASSIC_THEME : readTheme({
        themeId: THEME_IDS[this.themeDraftChoice - 1], mode: this.themeDraftMode === 0 ? 'schedule' : 'fixed',
        variant: this.themeDraftMode === 2 ? 'night' : 'day', dayStartMinute: toMinute(this.themeDraftDay),
        nightStartMinute: toMinute(this.themeDraftNight)
      })
      if (!next) {
        this.themeEditorError = '日景和夜景开始时间需不同'
        return
      }
      this.localTheme = next
    }
    this.followPhoneTheme = follow
    this.localThemeSaved = true
    this.storeTheme(THEME_LOCAL_KEY, { followPhone: this.followPhoneTheme, theme: this.localTheme })
    this.refreshTheme()
    this.closeThemeEditor()
  },

  loadHapticsSetting() {
    if (this.hapticsLoaded) return
    const loaded = value => {
      if (this.hapticsLoaded) return
      this.hapticsEnabled = value !== 'off'
      this.hapticsLoaded = true
    }
    try {
      const storage = this.eventStorage || this.themeStorage || require('@system.storage')
      storage.get({ key: HAPTICS_KEY, success: loaded, fail: () => loaded('') })
    } catch (_) {
      loaded('')
    }
  },

  receiveHaptics(enabled) {
    if (!LITE || typeof enabled !== 'boolean') return
    const changed = !this.hapticsLoaded || this.hapticsEnabled !== enabled
    this.hapticsLoaded = true
    this.hapticsEnabled = enabled
    if (!enabled) this.clearFailurePulse()
    if (!changed) return
    try {
      const storage = this.eventStorage || this.themeStorage || require('@system.storage')
      storage.set({ key: HAPTICS_KEY, value: enabled ? 'on' : 'off' })
    } catch (_) {
      // Keep the synced haptics setting active for this session.
    }
  },

  changeHaptics(event) {
    if (LITE) return
    const enabled = event.checked === true
    if (this.hapticsLoaded && this.hapticsEnabled === enabled) return
    this.hapticsLoaded = true
    this.hapticsEnabled = enabled
    if (!enabled) this.clearFailurePulse()
    try {
      this.eventStorage.set({ key: HAPTICS_KEY, value: enabled ? 'on' : 'off' })
    } catch (_) {
      // The setting still applies while this page is open.
    }
  },

  loadRectTwoPageSetting() {
    if (this.rectTwoPageLoaded || this.rectDisplayTimer !== null) return
    const loaded = value => {
      if (this.rectTwoPageLoaded || this.destroyed) return
      clearTimeout(this.rectDisplayTimer)
      this.rectDisplayTimer = null
      const saved = parseThemeStorage(value)
      if (saved && typeof saved.twoPage === 'boolean' && isNonNegativeInt(saved.revision)) {
        this.displayRevision = saved.revision
        this.displayPending = LITE ? false : saved.pending === true
        this.applyTwoPage(saved.twoPage)
      } else if (value === 'on' || value === 'off') {
        this.displayPending = !LITE
        this.applyTwoPage(value === 'on')
      }
      this.rectTwoPageLoaded = true
      const deferred = this.deferredDisplay
      this.deferredDisplay = null
      if (deferred) this.receiveBandDisplay(deferred)
      this.updateHomeLayoutReady()
    }
    this.rectDisplayTimer = setTimeout(() => loaded(''), 300)
    try {
      const storage = this.eventStorage || require('@system.storage')
      storage.get({ key: RECT_TWO_PAGE_KEY, success: loaded, fail: () => loaded('') })
    } catch (_) {
      loaded('')
    }
  },

  changeRectTwoPage(event) {
    if (!this.canSyncBandDisplay() || LITE) return
    const enabled = event.checked === true
    if (this.rectTwoPageLoaded && this.rectTwoPage === enabled) return
    clearTimeout(this.rectDisplayTimer)
    this.rectDisplayTimer = null
    this.rectTwoPageLoaded = true
    this.displayPending = true
    this.applyTwoPage(enabled)
    this.updateHomeLayoutReady()
    this.saveBandDisplay()
    if (this.linked && !this.pending && this.features && this.features.includes(BAND_DISPLAY_FEATURE)) this.syncStatus()
  },

  loadCircleTwoPageSetting() {
    if (this.circleTwoPageLoaded || this.circleDisplayTimer !== null) return
    const loaded = value => {
      if (this.circleTwoPageLoaded || this.destroyed) return
      clearTimeout(this.circleDisplayTimer)
      this.circleDisplayTimer = null
      const enabled = value !== 'off'
      this.circleTwoPageLoaded = true
      if (this.circleTwoPage !== enabled) {
        this.circleTwoPage = enabled
        if (!enabled) {
          this.clearControlsPull()
          this.watchPage = 0
        }
        this.updateRows()
      }
      this.updateHomeLayoutReady()
      if (this.isTwoPageShell()) this.focusWatchSwiper()
      else this.scheduleFocusActive()
    }
    this.circleDisplayTimer = setTimeout(() => loaded(''), 300)
    try {
      const storage = this.eventStorage || require('@system.storage')
      storage.get({ key: CIRCLE_TWO_PAGE_KEY, success: loaded, fail: () => loaded('') })
    } catch (_) {
      loaded('')
    }
  },

  changeTwoPage(event) {
    if (LITE || !this.canChangeDisplay) return
    if (this.isCircularProfile()) {
      const enabled = event.checked === true
      if (this.circleTwoPageLoaded && this.circleTwoPage === enabled) return
      clearTimeout(this.circleDisplayTimer)
      this.circleDisplayTimer = null
      this.circleTwoPageLoaded = true
      this.circleTwoPage = enabled
      if (!enabled) {
        this.clearControlsPull()
        this.watchPage = 0
      }
      this.updateRows()
      this.updateHomeLayoutReady()
      if (this.isTwoPageShell()) this.focusWatchSwiper()
      else this.scheduleFocusActive()
      try {
        const storage = this.eventStorage || this.themeStorage || require('@system.storage')
        storage.set({ key: CIRCLE_TWO_PAGE_KEY, value: enabled ? 'on' : 'off' })
      } catch (_) {
        // The setting still applies while this page is open.
      }
      return
    }
    this.changeRectTwoPage(event)
  },

  applyTwoPage(enabled) {
    if (this.rectTwoPage === enabled) return
    this.rectTwoPage = enabled
    if (!enabled) {
      this.clearControlsPull()
      this.watchPage = 0
    }
    this.updateRows()
    if (this.isTwoPageShell()) this.focusWatchSwiper()
    else this.scheduleFocusActive()
  },

  saveBandDisplay() {
    this.storeTheme(RECT_TWO_PAGE_KEY, { twoPage: this.rectTwoPage, revision: this.displayRevision, pending: this.displayPending })
  },

  receiveBandDisplay(value, sent) {
    if ((!LITE && !this.canSyncBandDisplay())
        || !value || typeof value.twoPage !== 'boolean' || !isNonNegativeInt(value.revision)) return
    if (!this.rectTwoPageLoaded) {
      this.deferredDisplay = value
      return
    }
    if (LITE) {
      if (value.revision > 0 && value.revision < this.displayRevision) return
      if (value.revision === this.displayRevision && value.twoPage === this.rectTwoPage) return
      this.displayPending = false
      this.displayRevision = value.revision
      this.applyTwoPage(value.twoPage)
      this.saveBandDisplay()
      return
    }
    const revision = this.displayRevision
    if (value.revision === 0 && revision > 0) {
      this.displayRevision = 0
      this.displayPending = true
      this.saveBandDisplay()
      return
    }
    if (value.revision < revision) return
    if (this.displayPending) {
      if (!sent && value.revision === revision) return
      // If the user changes the switch again while its previous value is in flight, keep the newer edit.
      if (sent && sent.twoPage !== this.rectTwoPage && (value.revision === revision || value.twoPage === sent.twoPage)) {
        this.displayRevision = value.revision
        this.saveBandDisplay()
        return
      }
    }
    if (!this.displayPending && value.revision === revision && value.twoPage === this.rectTwoPage) return
    this.displayPending = false
    this.displayRevision = value.revision
    this.applyTwoPage(value.twoPage)
    this.saveBandDisplay()
  },

  vibrateShort() {
    if (!this.visible || !this.hapticsLoaded || !this.hapticsEnabled) return false
    try {
      if (this.vibrator === undefined) {
        const vibrator = require('@system.vibrator')
        this.vibrator = vibrator && typeof vibrator.vibrate === 'function' ? vibrator : null
      }
      if (!this.vibrator) return false
      this.vibrator.vibrate({ mode: 'short' })
      return true
    } catch (_) {
      this.vibrator = null
      return false
    }
  },

  vibrateFailure() {
    if (this.failurePulseTimer !== null || !this.vibrateShort()) return
    this.failurePulseTimer = setTimeout(() => {
      this.failurePulseTimer = null
      this.vibrateShort()
    }, FAILURE_PULSE_GAP_MS)
  },

  clearFailurePulse() {
    clearTimeout(this.failurePulseTimer)
    this.failurePulseTimer = null
  },

  onHide() {
    this.stopSession()
  },

  stopSession() {
    this.clearControlsPull()
    clearTimeout(this.focusTimer)
    this.focusTimer = null
    clearTimeout(this.themeTimer)
    this.themeTimer = null
    if (!this.visible) return
    this.visible = false
    clearTimeout(this.startTimer)
    this.startTimer = null
    clearTimeout(this.pollTimer)
    this.pollTimer = null
    this.clearToast()
    this.clearFailurePulse()
    this.disconnect('', 'hidden')
  },

  onDestroy() {
    this.destroyed = true
    clearTimeout(this.focusTimer)
    this.focusTimer = null
    clearTimeout(this.layoutReadyTimer)
    clearTimeout(this.rectDisplayTimer)
    clearTimeout(this.circleDisplayTimer)
    this.stopSession()
    if (this.connection) {
      this.connection.onopen = null
      this.connection.onclose = null
      this.connection.onerror = null
      this.connection.onmessage = null
    }
  },

  onBackPress() {
    if (this.confirmTitle) {
      this.cancel()
      return true
    }
    if (!LITE && this.settingsOpen) {
      if (this.themeEditorOpen) {
        this.closeThemeEditor()
        return true
      }
      this.closeSettings()
      return true
    }
    return false
  },

  onOverlaySwipe(event) {
    if (event && event.direction === 'right') {
      this.onBackPress()
    }
  },

  syncStatus() {
    this.refreshTheme()
    if (this.connect(false)) this.request('status')
    else if (!this.connection) this.scheduleReconnect()
    else this.scheduleStatus()
  },

  connect(manual) {
    if (this.compatibilityNotice) {
      if (manual) this.showToast(this.compatibilityNotice)
      return false
    }
    if (!this.connection) {
      try {
        const interconnect = require('@system.interconnect')
        const connection = interconnect.instance()
        this.connection = connection
        connection.onmessage = message => {
          if (message) this.receive(message.data)
        }
        connection.onclose = () => {
          this.linkOpenState = 0
          if (this.visible) this.disconnect('手机连接已断开', 'closed')
        }
        connection.onerror = () => {
          this.linkOpenState = 0
          if (this.visible) this.disconnect('请检查手机上的手环通信服务', 'link_error')
        }
        connection.onopen = () => {
          this.linkOpenState = 1
          if (!this.visible || this.pending || this.confirmTitle) return
          this.cancelReconnect()
          if (this.handshakeComplete) this.syncStatus()
          else this.startHello()
        }
      } catch (error) {
        this.connection = null
        this.linkOpenState = 0
        console.warn('腕上车控通信初始化失败', error && error.message)
        if (manual || !this.reportedLinkIssue) {
          this.reportedLinkIssue = true
          this.recordEvent('disconnect', null, 'init_failed')
          this.showToast('当前固件未能启动手机通信')
        }
        return false
      }
    }
    if (this.handshakeComplete) return true
    if (!this.visible || this.pending || this.openTimer !== null) return false

    // The singleton can already be open when the page registers its callbacks.
    const connection = this.connection
    if (typeof connection.getReadyState === 'function') {
      const timer = setTimeout(() => this.startHello(), 500)
      this.openTimer = timer
      try {
        connection.getReadyState({
          success: state => {
            if (this.openTimer !== timer) return
            const status = state && typeof state.status === 'number' ? state.status : null
            if (status === 1) {
              this.linkOpenState = 1
              this.startHello()
            } else if (status !== null) {
              this.linkOpenState = status
              clearTimeout(this.openTimer)
              this.openTimer = null
              if (manual) this.showToast('请先确认手机已连接手环')
              else this.scheduleStatus()
            }
          },
          fail: () => {
            if (this.openTimer === timer) this.startHello()
          }
        })
      } catch (_) {
        this.startHello()
      }
    } else {
      this.startHello()
    }
    return false
  },

  startHello() {
    clearTimeout(this.openTimer)
    this.openTimer = null
    if (!this.pending && !this.handshakeComplete && !this.compatibilityNotice) this.request('hello')
  },

  finishHello(reply) {
    this.clearRequest()
    if (reply) {
      this.phoneApp = reply.phoneApp || ''
      this.protocol = reply.protocol
      this.features = Array.isArray(reply.features) ? reply.features.slice() : []
      this.compatibilityNotice = compatibilityMessage(reply)
      if (this.compatibilityNotice) {
        this.modeLabel = '版本不符'
        this.showToast(this.compatibilityNotice)
        return
      }
    }
    this.handshakeComplete = true
    this.syncStatus()
  },

  supportsRequest(type) {
    if (type === 'status' || type === 'hello') return true
    const feature = REQUEST_FEATURES[type]
    return this.features === null ? typeof feature === 'string' : this.features.indexOf(feature || type) >= 0
  },

  showToast(message) {
    if (!this.visible || !message) return
    this.clearToast()
    this.toastText = message
    this.toastTimer = setTimeout(() => this.clearToast(), TOAST_DURATION_MS)
  },

  clearToast() {
    clearTimeout(this.toastTimer)
    this.toastTimer = null
    this.toastText = ''
    this.receiptShowing = false
  },

  showExecutionReceipt(status, message) {
    const text = status === 'dispatched' ? '指令已发送'
      : status === 'unknown' ? (message ? message + '，' : '') + '结果待核对，请在' + this.brandName() + ' App 查看'
        : typeof message === 'string' ? message : ''
    this.showToast(text)
    this.receiptShowing = !!text
    if (status === 'dispatched') this.vibrateShort()
    else this.vibrateFailure()
  },

  toggleRange() {
    if (this.confirmTitle) return
    this.showTotalRange = !this.showTotalRange
  },

  onWatchPageChange(event) {
    if (event && (event.index === 0 || event.index === 1)) {
      const changed = this.watchPage !== event.index
      this.watchPage = event.index
      if (changed) {
        this.clearControlsPull()
        this.vibrateShort()
      }
      this.focusWatchSwiper()
    }
  },

  setWatchPage(index) {
    if (this.confirmTitle) return
    if (index === 0 || index === 1) {
      this.clearControlsPull()
      this.watchPage = index
      try {
        const swiper = this.$element && this.$element('watchSwiper')
        if (swiper && typeof swiper.swipeTo === 'function') swiper.swipeTo({ index })
      } catch (_) {
        // Fallback relies on index binding.
      }
      this.focusWatchSwiper()
    }
  },

  startControlsPull(event) {
    this.clearControlsPull()
    if (this.watchPage !== 1 || this.busy || this.controlPending || this.confirmTitle || !event.touches || event.touches.length !== 1) return
    const touch = event.touches[0]
    this.controlsPullStart = { id: touch.identifier, x: touch.clientX, y: touch.clientY }
  },

  controlsPullDistance(touch) {
    const start = this.controlsPullStart
    if (!start || !touch || touch.identifier !== start.id) return 0
    const distance = start.y - touch.clientY
    return distance > Math.abs(touch.clientX - start.x) ? distance : 0
  },

  moveControlsPull(event) {
    if (!this.controlsPullStart) return
    if (!event.touches || event.touches.length !== 1) {
      this.clearControlsPull()
      return
    }
    const distance = this.controlsPullDistance(event.touches[0])
    this.controlsPullOffset = -Math.min(20, Math.round(distance * 0.25))
    this.controlsPullProgress = Math.min(100, Math.round(distance * 100 / CONTROLS_PULL_THRESHOLD))
    if (distance >= CONTROLS_PULL_THRESHOLD && !this.controlsPullPulsed) {
      this.controlsPullPulsed = true
      this.vibrateShort()
    }
    this.controlsPullText = distance >= CONTROLS_PULL_THRESHOLD ? '松手刷新' : distance >= 12 ? '上拉刷新' : ''
  },

  endControlsPull(event) {
    const distance = this.controlsPullDistance(event.changedTouches && event.changedTouches[0])
    this.clearControlsPull()
    if (this.watchPage === 1 && !this.settingsOpen && distance >= CONTROLS_PULL_THRESHOLD) this.refresh()
  },

  clearControlsPull() {
    this.controlsPullStart = null
    this.controlsPullText = ''
    this.controlsPullOffset = 0
    this.controlsPullProgress = 0
    this.controlsPullPulsed = false
  },

  refresh() {
    if (this.busy || this.confirmTitle) return
    if (this.controlPending) {
      this.showToast(this.controlNotice || '正在同步车况')
      this.syncStatus()
      return
    }
    if (!this.connect(true)) return
    if (this.supportsRequest('refresh')) this.showToast('正在刷新车况…')
    this.request('refresh')
  },

  select(action) {
    if (this.busy || this.confirmTitle) return
    if (!this.linked) {
      this.showToast('请先连接手机')
      this.vibrateFailure()
      return
    }
    if (this.controlPending) {
      this.showToast(this.controlNotice || '正在同步车况')
      this.vibrateFailure()
      this.syncStatus()
      return
    }
    if (this.mapped.indexOf(action) < 0) {
      this.showToast('请在手机核对' + this.brandName() + '小组件配置')
      this.vibrateFailure()
      return
    }
    if (this.controlStates[action]) {
      this.showToast(this.controlStates[action])
      return
    }
    if (this.mode === 'live' && !this.available.includes(action)
        && (needsDirection(action, this.controlDirectionsSupported) || action === 'lock' && this.cachedVehicle)) {
      const reason = this.blockedControls[action]
      const notice = this.controlDirectionsSupported || action === 'lock'
        ? this.controlNotice || (typeof reason === 'string' && reason) || '请刷新车况，核对按钮状态'
        : '请更新手机端后使用此项车控'
      this.showToast(notice)
      this.vibrateFailure()
      return
    }
    this.clearToast()
    if (this.supportsRequest('prepare')) this.vibrateShort()
    this.request('prepare', action)
  },

  execute() {
    if (!this.confirmToken) return
    const action = this.confirmAction
    const token = this.confirmToken
    this.clearConfirmation()
    this.vibrateShort()
    this.showToast(this.mode === 'demo' ? '正在执行演示…' : '正在发送命令…')
    this.request('execute', action, token)
  },

  cancel() {
    const token = this.confirmToken
    this.clearConfirmation()
    if (token) this.showToast('已取消操作')
    if (token && this.linked && this.connection && this.supportsRequest('cancel')) {
      this.sendRequest({ v: PROTOCOL, id: this.nextId(), type: 'cancel', token })
    }
    this.scheduleStatus()
  },

  clearConfirmation() {
    clearTimeout(this.confirmTimer)
    this.confirmTimer = null
    this.confirmTitle = ''
    this.confirmIcon = ''
    this.confirmAction = ''
    this.confirmToken = ''
    this.scheduleFocusActive()
  },

  request(type, action, token) {
    if (!this.connection || !this.visible || this.confirmTitle) return
    if (type !== 'hello' && !this.handshakeComplete) return
    if (!this.supportsRequest(type)) {
      this.showToast('手机端不支持此功能，请更新到配套版本')
      if (type === 'prepare' || type === 'execute') this.vibrateFailure()
      return
    }
    if (this.pending && ((this.pending.type !== 'status' && this.pending.type !== 'car_image' && this.pending.type !== 'control_icon')
        || type === 'status' || type === 'car_image' || type === 'control_icon')) return
    if (this.pending) this.clearRequest()
    clearTimeout(this.pollTimer)
    this.pollTimer = null
    const id = this.nextId()
    const request = { v: PROTOCOL, id, type }
    if (type === 'hello') {
      request.bandApp = manifest.versionName
      request.minPhoneApp = MIN_PHONE_APP
    }
    if (type === 'status' && !LITE && this.canSyncBandDisplay() && this.displayPending
        && this.features && this.features.includes(BAND_DISPLAY_FEATURE)) {
      request.bandDisplay = { twoPage: this.rectTwoPage, revision: this.displayRevision }
    }
    if (type === 'status' && this.supportsRequest('control_icon')) {
      request.themeDay = this.themeDay
    }
    if (type === 'car_image' && this.imageSync) {
      request.hash = this.imageSync.hash
      request.index = this.imageSync.chunks.length
    }
    if (type === 'control_icon' && this.iconSync) {
      request.hash = this.iconSync.hash
      request.index = this.iconSync.chunks.length
    }
    if (action) request.action = action
    if (token) request.token = token
    if (type === 'execute' && this.features && this.features.includes(COMPACT_EXECUTE_ACK)) request.compactAck = true
    if (type === 'prepare') {
      request.brand = this.brand
      if (this.controlDirections[action]) request.controlDirection = this.controlDirections[action]
    }
    this.busy = type !== 'status' && type !== 'car_image' && type !== 'control_icon'
    this.refreshing = type === 'refresh'
    this.pending = request
    this.requestTimer = setTimeout(() => {
      if (this.pending !== request) return
      this.recordEvent('timeout', request)
      if (type === 'hello') this.finishHello()
      else this.disconnect('手机回复超时，请检查连接', 'timeout')
    }, type === 'hello' ? HELLO_TIMEOUT_MS : type === 'refresh' ? 13000 : 8000)
    this.sendRequest(request)
  },

  sendRequest(request, retried = false) {
    const generation = this.linkGeneration
    const oneWay = request.type === 'cancel'
    let failed = false
    const fail = () => {
      if (failed || generation !== this.linkGeneration
          || (oneWay ? !this.visible : this.pending !== request)) return
      failed = true
      this.recordEvent('send_failed', request)
      if (request.type === 'hello') {
        if (this.linkOpenState === 1) this.finishHello()
        else this.disconnect('', 'connect_wait', request)
      } else if (!retried && (request.type === 'status' || request.type === 'refresh')) {
        this.retryTimer = setTimeout(() => {
          this.retryTimer = null
          if (this.pending === request) this.sendRequest(request, true)
        }, READ_RETRY_MS)
      } else {
        this.disconnect('发送失败，请检查手机连接', 'send_failed', request)
      }
    }
    try {
      this.recordEvent('send', request, retried ? 'retry' : '')
      this.connection.send({ data: request, fail })
    } catch (_) {
      fail()
    }
  },

  nextId() {
    this.sequence += 1
    return Date.now().toString(36) + '_' + this.sequence.toString(36)
  },

  normalizeBrand(value) {
    return value === 'LYNKCO' || value === 'GEELY' || value === 'ZEEKR' ? value : 'GALAXY'
  },

  brandName() {
    if (this.brand === 'ZEEKR') return '极氪'
    return this.brand === 'LYNKCO' ? '领克' : this.brand === 'GEELY' ? '吉利汽车' : '银河'
  },

  defaultCarImage() {
    return this.brand === 'LYNKCO' || this.brand === 'ZEEKR' ? '' : DEFAULT_CAR_IMAGE
  },

  applyBrand(value) {
    const brand = this.normalizeBrand(value)
    if (brand === this.brand) return
    this.brand = brand
    this.clearConfirmation()
    this.carImageUri = ""
    this.carImageFromCache = false
    this.failedCarImageHash = ""
    this.mapped = []
    this.available = []
    this.controlDirections = {}
    this.lynkControlLabels = {}
    this.controlStates = {}
    this.imageSync = null
    this.carImageHash = ''
    this.carImageSrc = this.defaultCarImage()
    this.controlIconHashes = {}
    this.controlIconUris = {}
    this.failedControlIconHashes = {}
    this.iconSyncQueue = []
    this.iconSync = null
    this.storedSnapshot = null
    writeSnapshot("", () => {})
    this.vehicleName = DEFAULT_VEHICLE_NAME
    this.showVehicle({}, true)
    this.updateRows()
  },

  receive(raw) {
    if (!this.visible) return
    const request = this.pending
    let reply
    try {
      const text = typeof raw === 'string' ? raw : JSON.stringify(raw)
      if (typeof text !== 'string') return
      if (exceedsReplyLimit(text)) {
        this.recordEvent('drop', request, 'too_large')
        return
      }
      if (!request) return
      reply = typeof raw === 'string' ? JSON.parse(text) : raw
    } catch (_) {
      return
    }
    if (!reply || reply.id !== request.id) return
    if (reply.v === PROTOCOL && reply.type === 'reply' && typeof reply.error === 'string' && reply.error) {
      const error = reply.error
      const detail = error === 'too_large' || error === 'internal' ? error : 'unknown_error'
      const message = detail === 'too_large' ? '手机回复超过 2048 B'
        : detail === 'internal' ? '手机端内部错误' : '手机返回未知错误'
      this.recordEvent('recv', request, detail)
      this.disconnect(message, 'reply_error')
      return
    }
    if (request.type === 'hello') {
      if (reply.type === 'hello-reply' && typeof reply.v === 'number' && typeof reply.protocol === 'number') {
        this.recordEvent('recv', request, 'hello-reply')
        this.applyBrand(reply.brand)
        this.receiveBandDisplay(reply.bandDisplay)
        this.finishHello(reply)
        if (!this.compatibilityNotice) {
          this.receiveTheme(reply.theme)
          this.receiveHaptics(reply.haptics)
        }
      }
      return
    }
    if (request.type === 'execute' && reply.v === PROTOCOL && reply.type === 'execute_ack'
        && ['dispatched', 'unknown', 'blocked', 'phone_confirm'].includes(reply.status)) {
      this.recordEvent('recv', request, 'execute_ack:' + reply.status)
      this.clearRequest()
      this.clearConfirmation()
      this.controlPending = true
      this.controlNotice = ''
      this.available = []
      this.statusOnly = true
      this.updateRows()
      this.showExecutionReceipt(reply.status, reply.status === 'unknown' ? '' : reply.message)
      this.syncStatus()
      return
    }
    if (request.type === 'car_image') {
      if (reply.v !== PROTOCOL || reply.type !== 'car_image_reply') return
      this.clearRequest()
      if (!this.imageSync || reply.hash !== this.imageSync.hash || reply.index !== this.imageSync.chunks.length
          || typeof reply.total !== 'number' || reply.total <= 0 || typeof reply.data !== 'string' || !reply.data) {
        this.imageSync = null
        this.scheduleStatus()
        return
      }
      this.imageSync.chunks.push(reply.data)
      if (this.imageSync.chunks.length < reply.total) {
        this.request('car_image')
        return
      }
      const completedHash = this.imageSync.hash
      const base64Text = this.imageSync.chunks.join('')
      this.imageSync = null
      const imageBrand = this.brand
      saveCarImage(completedHash, base64Text, this.carImageUri, (uri, savedHash) => {
        if (!this.visible || imageBrand !== this.brand) return
        this.carImageHash = savedHash
        this.carImageUri = uri
        this.carImageSrc = uri
        this.carImageFromCache = false
      }, imageBrand)
      if (this.startNextControlIconSync()) return
      this.scheduleStatus()
      return
    }
    if (request.type === 'control_icon') {
      if (reply.v !== PROTOCOL || reply.type !== 'control_icon_reply') return
      this.clearRequest()
      if (!this.iconSync || reply.hash !== this.iconSync.hash || reply.index !== this.iconSync.chunks.length
          || typeof reply.total !== 'number' || reply.total <= 0 || typeof reply.data !== 'string' || !reply.data) {
        this.iconSync = null
        if (this.startNextControlIconSync()) return
        this.scheduleStatus()
        return
      }
      this.iconSync.chunks.push(reply.data)
      if (this.iconSync.chunks.length < reply.total) {
        this.request('control_icon')
        return
      }
      const completedHash = this.iconSync.hash
      const base64Text = this.iconSync.chunks.join('')
      this.iconSync = null
      const iconBrand = this.brand
      saveControlIcon(completedHash, base64Text, {
        brand: iconBrand,
        actions: this.controlIconHashes,
        files: this.controlIconFiles,
        order: this.controlIconOrder
      }, (uri, savedHash) => {
        if (this.destroyed || iconBrand !== this.brand) return
        this.controlIconFiles[savedHash] = uri
        let updated = false
        for (let i = 0; i < this.mapped.length; i++) {
          const id = this.mapped[i]
          if (this.controlIconHashes[id] === savedHash || (this.iconSyncTargets && this.iconSyncTargets[id] === savedHash)) {
            this.controlIconHashes[id] = savedHash
            this.controlIconUris[id] = uri
            if (this.confirmTitle && this.confirmAction === id) this.confirmIcon = uri
            updated = true
          }
        }
        persistControlIconCache(this.brand, this.controlIconHashes, this.controlIconFiles, this.controlIconOrder)
        if (updated) this.updateRows()
      })
      if (this.startNextControlIconSync()) return
      this.scheduleStatus()
      return
    }
    if (reply.v !== PROTOCOL || reply.type !== 'reply') return
    if (reply.mode !== 'demo' && reply.mode !== 'live') return
    this.recordEvent('recv', request)
    this.reconnectAttempt = 0
    this.applyBrand(reply.brand)
    this.receiveTheme(reply.theme)
    this.receiveHaptics(reply.haptics)
    this.receiveBandDisplay(reply.bandDisplay, request.bandDisplay)
    const firstReply = !this.linked || this.mode !== reply.mode
    this.clearRequest()
    this.linked = true
    this.hasReply = true
    this.reportedLinkIssue = false
    this.mode = reply.mode
    this.modeLabel = this.mode === 'demo' ? '演示' : '已连接'
    this.phoneConfirmation = reply.phoneConfirmation === true
    const previousNotice = this.controlNotice
    const wasControlPending = this.controlPending
    this.controlNotice = typeof reply.controlNotice === 'string' ? reply.controlNotice : ''
    this.blockedControls = reply.blocked && typeof reply.blocked === 'object' ? reply.blocked : {}
    this.controlPending = reply.controlPending === true || this.phoneConfirmation
    if (this.phoneConfirmation) this.controlNotice = '请在手机确认' + this.brandName() + '操作'
    const forBrand = ids => knownActions(ids).filter(id => this.brand === 'LYNKCO' ? id.indexOf('lynk_') === 0 : id.indexOf('lynk_') !== 0)
    const available = forBrand(reply.available)
    const mapped = (Array.isArray(reply.mapped) ? forBrand(reply.mapped) : available).slice(0, HOME_ACTIONS)
    const partialUpdate = this.controlPending && !firstReply && mapped.length < this.mapped.length
      && mapped.every(action => this.mapped.indexOf(action) >= 0)
    if (!partialUpdate) this.mapped = mapped
    this.lynkControlLabels = readLynkControlLabels(this.brand, reply.controlLabels, this.mapped)
    this.controlStates = readControlStates(reply.controlStates, this.mapped)
    const reportedDirections = reply.controlDirections
    this.controlDirectionsSupported = !!reportedDirections && typeof reportedDirections === 'object'
      && !Array.isArray(reportedDirections)
    const cachedVehicle = reply.vehicleCached === true
    this.cachedVehicle = cachedVehicle
    const directions = cachedVehicle ? {} : readControlDirections(
      this.controlDirectionsSupported ? reportedDirections : null, reply.lockState)
    this.available = this.controlPending ? [] : available.filter(action => this.mapped.includes(action) && !this.controlStates[action]
      && (this.mode === 'demo' || directions[action]
        || !needsDirection(action, this.controlDirectionsSupported) && !(action === 'lock' && cachedVehicle)))
    this.controlDirections = this.controlPending && !firstReply && !cachedVehicle
      ? Object.assign({}, this.controlDirections, directions) : directions
    this.emptyLabel = '请在手机配置' + this.brandName() + '卡片'
    this.syncActionIconMetadata(reply.actionIcons)
    this.updateRows()
    if (typeof reply.vehicleName === 'string') {
      this.vehicleName = normalizeVehicleName(reply.vehicleName)
    }
    const vehicle = reply.vehicle || {}
    this.showVehicle(vehicle, cachedVehicle)
    if (this.mode === 'live') {
      const snapshot = vehicle.updatedAt > 0
        ? JSON.stringify(Object.assign({}, vehicle, { brand: this.brand, mapped: this.mapped,
          vehicleName: this.vehicleName, controlLabels: this.lynkControlLabels }))
        : ''
      if (snapshot !== this.storedSnapshot) {
        this.storedSnapshot = snapshot
        writeSnapshot(snapshot, () => {
          if (this.storedSnapshot === snapshot) this.storedSnapshot = null
        })
      }
    }

    const directionChanged = request.controlDirection && request.controlDirection !== directions[request.action]
    const allowVoice = !this.statusOnly
    if (request.type === 'status') this.statusOnly = false
    if (request.type === 'status' && allowVoice && this.brand === 'LYNKCO'
        && (reply.voiceAction === 'lynk_unlock' || reply.voiceAction === 'lynk_window')
        && this.available.indexOf(reply.voiceAction) >= 0 && !this.confirmTitle) {
      this.select(reply.voiceAction)
      return
    }
    if (request.type === 'prepare' && reply.status === 'confirm' && reply.action === request.action && !directionChanged
        && typeof reply.token === 'string' && reply.token.length === 36
        && typeof reply.message === 'string' && reply.message) {
      this.clearToast()
      this.confirmAction = request.action
      this.confirmToken = reply.token
      this.confirmIcon = this.controlIconUris[request.action] || actionIcon(request.action, directions[request.action], this.lynkControlLabels[request.action])
      this.confirmTitle = reply.message
      this.confirmTimer = setTimeout(() => {
        this.cancel()
        this.showToast('确认已过期，请重新选择')
        this.vibrateFailure()
      }, 13000)
    } else if (request.type === 'execute' && this.mode === 'live') {
      this.showExecutionReceipt(reply.status, reply.status === 'unknown' ? '' : reply.message)
    } else if (request.type !== 'status') {
      const dispatched = request.type === 'execute' && reply.status === 'dispatched'
      let message = reply.message
      if (this.controlPending && (this.phoneConfirmation || reply.status !== 'dispatched')) {
        message = this.controlNotice || '正在同步车况'
      } else if (dispatched && this.mode === 'live') {
        message = '指令已发送'
      } else if (reply.status === 'confirm' && directionChanged) {
        message = '按钮状态已变化，请重新选择'
      }
      this.showToast(typeof message === 'string' ? message : '')
      if (dispatched) this.vibrateShort()
      else if (request.type === 'prepare' || request.type === 'execute') this.vibrateFailure()
    } else if ((!this.receiptShowing || this.phoneConfirmation)
        && (firstReply || previousNotice !== this.controlNotice || wasControlPending !== this.controlPending)) {
      this.clearToast()
      if (!this.controlPending || this.phoneConfirmation) this.showToast(this.controlNotice)
    }
    const imageHash = typeof reply.carImageHash === 'string' ? reply.carImageHash : ''
    const imageChunks = typeof reply.carImageChunks === 'number' ? reply.carImageChunks : 0
    if (request.type === 'status' && this.supportsRequest('car_image') && !this.controlPending && !this.confirmTitle
        && imageHash && imageChunks > 0 && imageHash !== this.carImageHash && imageHash !== this.failedCarImageHash) {
      if (!this.imageSync || this.imageSync.hash !== imageHash) {
        this.imageSync = { hash: imageHash, total: imageChunks, chunks: [] }
      }
      this.request('car_image')
      return
    }
    if (request.type === 'status' && this.supportsRequest('control_icon') && !this.controlPending && !this.confirmTitle
        && this.startNextControlIconSync()) {
      return
    }
    this.scheduleStatus()
  },

  syncActionIconMetadata(rawIcons) {
    if (!rawIcons || typeof rawIcons !== 'object' || Array.isArray(rawIcons)) return
    if (!this.iconSyncTargets) this.iconSyncTargets = {}
    const queue = []
    const seenHashes = {}
    let cacheUpdated = false
    for (let i = 0; i < this.mapped.length; i++) {
      const action = this.mapped[i]
      const entry = rawIcons[action]
      if (!Array.isArray(entry) || typeof entry[0] !== 'string' || typeof entry[1] !== 'number') continue
      const hash = entry[0]
      const chunks = entry[1]
      if (!hash || chunks <= 0) continue
      this.iconSyncTargets[action] = hash
      const cachedUri = this.controlIconFiles[hash]
      if (cachedUri) {
        if (this.controlIconHashes[action] !== hash || this.controlIconUris[action] !== cachedUri) {
          this.controlIconHashes[action] = hash
          this.controlIconUris[action] = cachedUri
          cacheUpdated = true
        }
      } else if (!this.failedControlIconHashes[hash] && !seenHashes[hash]) {
        seenHashes[hash] = true
        queue.push({ action, hash, total: chunks, chunks: [] })
      }
    }
    if (cacheUpdated) {
      persistControlIconCache(this.brand, this.controlIconHashes, this.controlIconFiles, this.controlIconOrder)
    }
    if (queue.length > 0 || !this.iconSync) {
      this.iconSyncQueue = queue
    }
  },

  startNextControlIconSync() {
    if (!this.visible || !this.linked || !this.supportsRequest('control_icon') || this.iconSync) return false
    while (this.iconSyncQueue && this.iconSyncQueue.length > 0) {
      const next = this.iconSyncQueue.shift()
      if (!next || !next.hash || this.controlIconFiles[next.hash] || this.failedControlIconHashes[next.hash]) continue
      this.iconSync = next
      this.request('control_icon')
      return true
    }
    return false
  },

  showVehicle(data, cached) {
    this.electricPercent = validPercent(data.electricPercent)
    this.fuelPercent = validPercent(data.fuelPercent)
    const total = totalReading(data, this.electricPercent, this.fuelPercent)
    const electric = energyReading(data.electricKm, this.electricPercent)
    const fuel = energyReading(data.fuelKm, this.fuelPercent)
    this.total = total.value
    this.totalUnit = total.unit
    this.electric = electric.value
    this.electricUnit = electric.unit
    this.fuel = fuel.value
    this.fuelUnit = fuel.unit
    const motion = typeof data.motion === 'string' ? data.motion.trim() : ''
    this.motion = this.brand === 'LYNKCO' && ['', '状态未知', '未知', '--', '—'].includes(motion)
      ? '' : motion || '状态未知'
    this.updated = data.updatedLabel ? (cached ? '上次 ' : '') + data.updatedLabel : '等待车况'
    this.updateRangeBreakdown()
  },

  updateRangeBreakdown() {
    const bothMissing = this.electric === '—' && this.fuel === '—'
    const readings = []
    if (bothMissing || this.electric !== '—') {
      readings.push({ id: 'electric', value: this.electric + this.electricUnit, percent: this.electricPercent })
    }
    if (bothMissing || this.fuel !== '—') {
      readings.push({ id: 'fuel', value: this.fuel + this.fuelUnit, percent: this.fuelPercent })
    }
    this.rangeBreakdown = readings
  },

  updateRows() {
    const key = [this.layoutProfile, this.shortRect, this.linked, this.mapped.join(), this.available.join(), this.controlPending, this.themeDay,
      this.mapped.map(action => this.controlDirections[action] || '').join(),
      this.mapped.map(action => this.lynkControlLabels[action] || '').join(),
      this.mapped.map(action => this.controlStates[action] || '').join(),
      this.mapped.map(action => this.controlIconUris[action] || '').join()].join('|')
    if (key === this.rowsKey) return
    this.rowsKey = key
    this.displayRows = actionRows(
      this.mapped, this.available, this.linked, this.controlPending, this.controlDirections,
      this.layoutProfile, this.lynkControlLabels, this.themeDay, this.controlStates, this.shortRect,
      this.controlIconUris)
  },

  clearRequest() {
    clearTimeout(this.requestTimer)
    this.requestTimer = null
    clearTimeout(this.retryTimer)
    this.retryTimer = null
    this.pending = null
    this.busy = false
    this.refreshing = false
  },

  disconnect(message, reason, request = this.pending) {
    const waitingForControl = this.pending && this.pending.type === 'execute'
    const controlInterrupted = this.confirmTitle || (request
      && (request.type === 'prepare' || request.type === 'execute' || request.type === 'cancel'))
    const replyError = reason === 'reply_error'
    const report = replyError || waitingForControl || this.linked || !this.reportedLinkIssue
    if (this.visible || this.linked || request) this.recordEvent('disconnect', request, reason)
    this.clearRequest()
    this.clearConfirmation()
    this.imageSync = null
    this.iconSync = null
    this.iconSyncQueue = []
    this.linkGeneration += 1
    clearTimeout(this.openTimer)
    this.openTimer = null
    if (!replyError) this.linkOpenState = 0
    this.handshakeComplete = false
    this.phoneApp = ''
    this.protocol = null
    this.features = null
    this.compatibilityNotice = ''
    this.linked = false
    this.phoneConfirmation = false
    this.controlPending = false
    this.cachedVehicle = false
    this.blockedControls = {}
    this.controlStates = {}
    this.statusOnly = true
    this.controlDirectionsSupported = false
    this.controlDirections = {}
    this.modeLabel = this.mode === 'demo' ? '演示离线' : '离线'
    this.emptyLabel = '连接手机后显示已映射车控'
    this.available = []
    this.updateRows()
    if (!this.visible && waitingForControl) this.interruptedExecution = true
    if (controlInterrupted && !waitingForControl && reason !== 'hidden') this.vibrateFailure()
    if (report && this.visible && (message || waitingForControl)) {
      this.reportedLinkIssue = true
      if (waitingForControl) this.showExecutionReceipt('unknown', replyError ? message : '')
      else if (!this.receiptShowing) this.showToast(message)
    }
    if (!this.visible) this.cancelReconnect()
    else if (replyError) this.scheduleStatus()
    else this.scheduleReconnect()
  }
  }
}
