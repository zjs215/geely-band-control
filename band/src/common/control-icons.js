import { decodeBase64 } from './car-image.js'

const CACHE_KEY = 'control-icon-cache'
const MAX_CACHED_FILES = 24

function emptyState(brand = 'GALAXY') {
  return { brand, actions: {}, files: {}, order: [] }
}

export function readCachedControlIcons(onRead, onMissing) {
  let finished = false
  const miss = () => {
    if (finished) return
    finished = true
    if (typeof onMissing === 'function') onMissing()
  }
  try {
    const storage = require('@system.storage')
    storage.get({
      key: CACHE_KEY,
      success: value => {
        if (!value) {
          miss()
          return
        }
        try {
          const parsed = JSON.parse(value)
          if (parsed && typeof parsed === 'object') {
            const state = emptyState(typeof parsed.brand === 'string' && parsed.brand ? parsed.brand : 'GALAXY')
            const rawFiles = parsed.files && typeof parsed.files === 'object' ? parsed.files : {}
            const rawActions = parsed.actions && typeof parsed.actions === 'object' ? parsed.actions : {}
            const rawOrder = Array.isArray(parsed.order) ? parsed.order : Object.keys(rawFiles)
            for (let i = 0; i < rawOrder.length; i++) {
              const hash = rawOrder[i]
              const uri = typeof rawFiles[hash] === 'string' ? rawFiles[hash] : ''
              if (typeof hash === 'string' && hash && uri && !state.files[hash]) {
                state.files[hash] = uri
                state.order.push(hash)
              }
            }
            const fileHashes = Object.keys(rawFiles)
            for (let i = 0; i < fileHashes.length; i++) {
              const hash = fileHashes[i]
              const uri = typeof rawFiles[hash] === 'string' ? rawFiles[hash] : ''
              if (hash && uri && !state.files[hash]) {
                state.files[hash] = uri
                state.order.push(hash)
              }
            }
            const actionKeys = Object.keys(rawActions)
            for (let i = 0; i < actionKeys.length; i++) {
              const action = actionKeys[i]
              const hash = rawActions[action]
              if (typeof hash === 'string' && state.files[hash]) {
                state.actions[action] = hash
              }
            }
            finished = true
            onRead(state)
            return
          }
        } catch (_) {
          // Ignore malformed cache.
        }
        miss()
      },
      fail: miss
    })
  } catch (_) {
    miss()
  }
}

export function persistControlIconCache(brand, actions, files, order) {
  try {
    const storage = require('@system.storage')
    const persistedFiles = {}
    const persistedOrder = []
    const persistedActions = {}
    const keys = Array.isArray(order) ? order : Object.keys(files || {})
    for (let i = 0; i < keys.length; i++) {
      const hash = keys[i]
      const uri = files && typeof files[hash] === 'string' ? files[hash] : ''
      if (uri && uri.indexOf('internal://') === 0) {
        persistedFiles[hash] = uri
        persistedOrder.push(hash)
      }
    }
    const actionKeys = Object.keys(actions || {})
    for (let i = 0; i < actionKeys.length; i++) {
      const action = actionKeys[i]
      const hash = actions[action]
      if (typeof hash === 'string' && persistedFiles[hash]) {
        persistedActions[action] = hash
      }
    }
    storage.set({
      key: CACHE_KEY,
      value: JSON.stringify({
        brand: brand || 'GALAXY',
        actions: persistedActions,
        files: persistedFiles,
        order: persistedOrder
      })
    })
  } catch (_) {
    // Cache persistence is optional.
  }
}

function pruneOldIcons(actions, files, order, fileApi) {
  const activeHashes = {}
  const actionKeys = Object.keys(actions || {})
  for (let i = 0; i < actionKeys.length; i++) {
    const h = actions[actionKeys[i]]
    if (h) activeHashes[h] = true
  }
  while (order.length > MAX_CACHED_FILES) {
    let victimIndex = -1
    for (let i = 0; i < order.length; i++) {
      if (!activeHashes[order[i]]) {
        victimIndex = i
        break
      }
    }
    if (victimIndex < 0) victimIndex = 0
    const victimHash = order.splice(victimIndex, 1)[0]
    const victimUri = files[victimHash]
    delete files[victimHash]
    if (fileApi && victimUri && victimUri.indexOf('internal://') === 0 && typeof fileApi.delete === 'function') {
      try {
        fileApi.delete({ uri: victimUri })
      } catch (_) {
        // Ignore cleanup failures.
      }
    }
  }
}

export function saveControlIcon(hash, base64Text, cacheState, onSaved) {
  const bytes = decodeBase64(base64Text)
  if (!bytes.length) return
  const targetUri = 'internal://files/cicon_' + hash + '.png'
  const fallbackDataUri = 'data:image/png;base64,' + base64Text
  const recordUri = (uri, fileApi) => {
    const files = cacheState.files
    const order = cacheState.order
    files[hash] = uri
    const existingIndex = order.indexOf(hash)
    if (existingIndex >= 0) order.splice(existingIndex, 1)
    order.push(hash)
    pruneOldIcons(cacheState.actions, files, order, fileApi)
    persistControlIconCache(cacheState.brand, cacheState.actions, files, order)
    onSaved(uri, hash)
  }
  try {
    const file = require('@system.file')
    if (file && typeof file.writeArrayBuffer === 'function') {
      file.writeArrayBuffer({
        uri: targetUri,
        buffer: bytes,
        success: () => recordUri(targetUri, file),
        fail: () => onSaved(fallbackDataUri, hash)
      })
      return
    }
  } catch (_) {
    // File API unavailable; fall back to inline data URI for current session.
  }
  onSaved(fallbackDataUri, hash)
}
