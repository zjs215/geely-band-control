module.exports = {
  cli: {
    optimizeCssAttr: false
  },
  postHook(config) {
    const current = config && config.optimization && config.optimization.minimizer && config.optimization.minimizer[0]
    if (current && typeof current.constructor === 'function') {
      config.optimization.minimizer = [
        new current.constructor({
          minimizerOptions: {
            module: true,
            mangle: false,
            compress: {}
          }
        })
      ]
    }
  }
}
