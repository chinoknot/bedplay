// Builds android/app/src/main/assets/dist/xCloudPlayer.min.js from the unmodified
// `xbox-xcloud-player` npm package (MIT, (c) Jim Kroon / unknownskl), with one small change:
// the "screen size" the player declares to the console is read from window.__dims and
// "custom resolution" is only enabled if window.__customRes is set. Without this the console
// picks 2560x1440 (~28 Mbit/s), which low-end TV sticks cannot decode smoothly.
//
//   cd web && npm install && npm run build
const esbuild = require('esbuild');
const fs = require('fs');
const path = require('path');

const D = '(window.__dims||[1920,1080])';
const dimensionsPatch = {
  name: 'bedplay-dimensions',
  setup(build) {
    build.onLoad({ filter: /Channel[\\/]Message\.js$/ }, (args) => {
      let s = fs.readFileSync(args.path, 'utf8');
      const before = s;
      s = s
        .replace("'horizontal': 1920", `'horizontal': ${D}[0]`)
        .replace("'vertical': 1080", `'vertical': ${D}[1]`)
        .replace("'preferredWidth': 1920", `'preferredWidth': ${D}[0]`)
        .replace("'preferredHeight': 1080", `'preferredHeight': ${D}[1]`)
        .replace("'safeAreaRight': 1920", `'safeAreaRight': ${D}[0]`)
        .replace("'safeAreaBottom': 1080", `'safeAreaBottom': ${D}[1]`)
        .replace("'supportsCustomResolution': true", "'supportsCustomResolution': !!window.__customRes");
      if (s === before) throw new Error('player patch did not apply: library layout changed?');
      return { contents: s, loader: 'js' };
    });
  },
};

esbuild.build({
  entryPoints: [path.join(__dirname, 'node_modules/xbox-xcloud-player/dist/Library.js')],
  outfile: path.join(__dirname, '../android/app/src/main/assets/dist/xCloudPlayer.min.js'),
  bundle: true,
  minify: true,
  format: 'iife',
  globalName: 'xCloudPlayer',
  platform: 'browser',
  target: 'es2017',
  plugins: [dimensionsPatch],
  banner: { js: '/*! xbox-xcloud-player 0.2.11 (MIT) (c) Jim Kroon - modified by BedPlay: see web/build.js */' },
}).then(() => console.log('built xCloudPlayer.min.js')).catch(() => process.exit(1));
