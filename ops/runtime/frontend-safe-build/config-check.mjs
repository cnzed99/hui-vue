// Resolve the actual current Vite config without building or publishing output.
import { pathToFileURL } from 'node:url';
const root = '/home/ubuntu/print-system/src/frontend';
if (process.cwd() !== root || process.env.NODE_OPTIONS !== undefined ||
    process.env.VITE_SOURCEMAP !== undefined || process.env.VITE_COMPRESS !== undefined) {
  throw new Error('Unexpected directory or external build override');
}
const { loadEnv, resolveConfig } = await import(pathToFileURL(`${root}/node_modules/vite/dist/node/index.js`));
const env = loadEnv('cloud', root);
const config = await resolveConfig({ mode: 'cloud' }, 'build', 'production');
const compressionPlugins = config.plugins.filter(p => /vite:compression|vite-plugin-compression/i.test(p.name));
const ok = config.mode === 'cloud' && config.build.sourcemap === false &&
  env.VITE_SOURCEMAP === 'false' && env.VITE_COMPRESS === 'none' &&
  config.build.minify === 'oxc' && compressionPlugins.length === 0;
console.log(JSON.stringify({ effective_mode: config.mode, effective_sourcemap: config.build.sourcemap,
  effective_compress: env.VITE_COMPRESS, effective_minify: config.build.minify,
  compression_plugin_count: compressionPlugins.length, external_overrides: false, config_verified: ok }));
if (!ok) throw new Error('Cloud mode contract failed');
