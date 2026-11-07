module.exports = {
  apps: [{
    name: 'indoor',
    script: '/opt/apps/indoor/server/server.js',
    cwd: '/opt/apps/indoor',
    time: true,
    env: {
      NODE_ENV: 'production',
      PORT: 3001,
      APP_URL: 'https://player.arcotech.api.br',
      SELF_HOSTED: 'true',
      SELF_HOSTED_SIGNUP_PLAN: 'enterprise',
      ST_HIDE_SPONSOR: 'true',
      PLUGINS_ENABLED: 'true',
      HIDE_BILLING: 'true'
    }
  }]
};
