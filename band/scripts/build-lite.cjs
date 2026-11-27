const fs = require('fs')
const path = require('path')
const { execSync } = require('child_process')

const bandDir = path.resolve(__dirname, '..')
const stageRoot = path.join(bandDir, '.lite-build')
const stageDir = path.join(stageRoot, 'lite')
const srcDir = path.join(bandDir, 'src')
const stageSrc = path.join(stageDir, 'src')

fs.rmSync(stageRoot, { recursive: true, force: true })
fs.mkdirSync(stageSrc, { recursive: true })

fs.copyFileSync(path.join(bandDir, 'package.json'), path.join(stageDir, 'package.json'))
if (fs.existsSync(path.join(bandDir, 'sign'))) {
  fs.cpSync(path.join(bandDir, 'sign'), path.join(stageDir, 'sign'), { recursive: true })
}
if (fs.existsSync(path.join(bandDir, 'node_modules'))) {
  fs.symlinkSync(path.join(bandDir, 'node_modules'), path.join(stageDir, 'node_modules'), 'junction')
}

for (const file of ['manifest.json', 'app.ux']) {
  const source = path.join(srcDir, file)
  if (fs.existsSync(source)) fs.copyFileSync(source, path.join(stageSrc, file))
}
fs.mkdirSync(path.join(stageSrc, 'pages/garage'), { recursive: true })
fs.copyFileSync(path.join(srcDir, 'pages/garage/lite.ux'), path.join(stageSrc, 'pages/garage/index.ux'))

// Copy common scripts and icons without bundled theme wallpapers or settings icons.
const stageCommon = path.join(stageSrc, 'common')
fs.mkdirSync(stageCommon, { recursive: true })
for (const entry of fs.readdirSync(path.join(srcDir, 'common'), { withFileTypes: true })) {
  const from = path.join(srcDir, 'common', entry.name)
  const to = path.join(stageCommon, entry.name)
  if (entry.isFile()) {
    fs.copyFileSync(from, to)
    continue
  }
  if (entry.name === 'themes') continue
  fs.cpSync(from, to, {
    recursive: true,
    filter: item => {
      const base = path.basename(item)
      return base !== 'settings.png' && base !== 'back.png'
    }
  })
}

const aiotBin = path.join(bandDir, 'node_modules/.bin', process.platform === 'win32' ? 'aiot.cmd' : 'aiot')
execSync(`"${aiotBin}" build`, { cwd: stageDir, stdio: 'inherit' })

const manifest = JSON.parse(fs.readFileSync(path.join(srcDir, 'manifest.json'), 'utf8'))
const builtRpk = path.join(stageDir, 'dist', `${manifest.package}.debug.${manifest.versionName}.rpk`)
const outDist = path.join(bandDir, 'dist')
fs.mkdirSync(outDist, { recursive: true })
const outRpk = path.join(outDist, `${manifest.package}.lite.${manifest.versionName}.rpk`)
fs.copyFileSync(builtRpk, outRpk)

const outBuildJsDir = path.join(bandDir, 'build-lite/pages/garage')
fs.mkdirSync(outBuildJsDir, { recursive: true })
fs.copyFileSync(path.join(stageDir, 'build/pages/garage/index.js'), path.join(outBuildJsDir, 'index.js'))

fs.rmSync(stageRoot, { recursive: true, force: true })
console.log('Lite RPK built:', outRpk, fs.statSync(outRpk).size)
