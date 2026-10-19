const { defineConfig } = require('cypress')

module.exports = defineConfig({
  e2e: {
    specPattern: 'tests/e2e/**/*.cy.{js,jsx,ts,tsx}',
    supportFile: false
  }
})
