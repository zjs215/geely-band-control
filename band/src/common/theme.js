const SCENES = [
  ['classic', '经典'], ['sky', '蓝天与星空'], ['ridge', '山脊营地'], ['city', '城市天际线'],
  ['coast', '海岸灯塔'], ['alpine', '雪山映湖']
]
export const THEME_IDS = SCENES.map(scene => scene[0])
export const THEME_NAMES = SCENES.map(scene => scene[1])
export const CLASSIC_THEME = {
  themeId: 'classic', mode: 'schedule', variant: 'day', dayStartMinute: 360, nightStartMinute: 1080
}

const MINUTES_PER_DAY = 1440

export function readTheme(value) {
  if (!value || typeof value !== 'object' || THEME_IDS.indexOf(value.themeId) < 0
      || (value.mode !== 'schedule' && value.mode !== 'fixed')
      || (value.variant !== 'day' && value.variant !== 'night')
      || !validMinute(value.dayStartMinute) || !validMinute(value.nightStartMinute)
      || (value.mode === 'schedule' && value.dayStartMinute === value.nightStartMinute)) return null
  const { themeId, mode, variant, dayStartMinute, nightStartMinute } = value
  return { themeId, mode, variant, dayStartMinute, nightStartMinute }
}

export function parseThemeStorage(value) {
  try { return JSON.parse(value) } catch (_) { return null }
}

export function themeVariant(config, now) {
  if (config.mode === 'fixed') return config.variant
  const elapsed = (localMinute(now) - config.dayStartMinute + MINUTES_PER_DAY) % MINUTES_PER_DAY
  const duration = (config.nightStartMinute - config.dayStartMinute + MINUTES_PER_DAY) % MINUTES_PER_DAY
  return elapsed < duration ? 'day' : 'night'
}

export function themeDelay(config, now, variant) {
  if (config.themeId === 'classic' || config.mode === 'fixed') return 0
  const next = variant === 'day' ? config.nightStartMinute : config.dayStartMinute
  const minutes = (next - localMinute(now) + MINUTES_PER_DAY) % MINUTES_PER_DAY
  return Math.max(1, minutes * 60000 - now.getSeconds() * 1000 - now.getMilliseconds())
}

export function themeImage(config, variant, profile) {
  if (config.themeId === 'classic') return ''
  const shape = profile === 'circle' || profile === 'square' ? 'square' : profile
  return '/common/themes/' + shape + '/' + config.themeId + '-' + variant + '.jpg'
}

export function themeTimeLabel(minute) {
  const pad = value => value < 10 ? '0' + value : String(value)
  return pad(Math.floor(minute / 60)) + ':' + pad(minute % 60)
}

function localMinute(now) {
  return now.getHours() * 60 + now.getMinutes()
}

function validMinute(value) {
  return typeof value === 'number' && isFinite(value) && Math.floor(value) === value && value >= 0 && value < MINUTES_PER_DAY
}
