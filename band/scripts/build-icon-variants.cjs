const crypto = require('node:crypto')
const fs = require('node:fs')
const path = require('node:path')
const { execFileSync } = require('node:child_process')

const output = process.argv[2]
if (!output) throw new Error('Usage: node build-icon-variants.cjs OUTPUT_DIRECTORY')

const band = path.resolve(__dirname, '..')
const destination = path.resolve(output)
const cache = path.resolve(band, '../.local')
fs.mkdirSync(cache, { recursive: true })
const stage = fs.mkdtempSync(path.join(cache, 'vela-icon-build-'))
const manifest = require('../src/manifest.json')
const toolkit = require.resolve('aiot-toolkit/lib/bin.js')
const checksums = []

try {
  fs.cpSync(path.join(band, 'src'), path.join(stage, 'src'), { recursive: true })
  fs.cpSync(path.join(band, 'sign'), path.join(stage, 'sign'), { recursive: true })
  fs.copyFileSync(path.join(band, 'package.json'), path.join(stage, 'package.json'))
  fs.mkdirSync(path.join(stage, 'scripts'))
  fs.copyFileSync(path.join(__dirname, 'build-lite.cjs'), path.join(stage, 'scripts/build-lite.cjs'))
  fs.symlinkSync(path.join(band, 'node_modules'), path.join(stage, 'node_modules'), 'junction')

  const build = (...args) => execFileSync(process.execPath, args, { cwd: stage, stdio: 'inherit' })
  for (const variant of ['mono', 'coral', 'cobalt']) {
    fs.copyFileSync(path.join(band, 'launcher-icons', variant + '.png'), path.join(stage, 'src/common/icon.png'))
    build(toolkit, 'build')
    build(path.join(stage, 'scripts/build-lite.cjs'))

    const variantDir = path.join(destination, variant)
    fs.mkdirSync(variantDir, { recursive: true })
    for (const [type, fileName] of [
      ['debug', 'wrist-car-control.rpk'],
      ['lite', 'wrist-car-control-lite.rpk']
    ]) {
      const file = path.join(stage, 'dist', [manifest.package, type, manifest.versionName, 'rpk'].join('.'))
      const data = fs.readFileSync(file)
      fs.copyFileSync(file, path.join(variantDir, fileName))
      const hash = crypto.createHash('sha256').update(data).digest('hex')
      checksums.push(hash + '  ' + variant + '/' + fileName)
    }
  }
  fs.writeFileSync(path.join(destination, 'SHA256SUMS.txt'), checksums.join('\n') + '\n')
} finally {
  if (path.dirname(stage) !== cache) throw new Error('Unexpected Vela staging directory')
  fs.rmSync(stage, { recursive: true, force: true })
}
