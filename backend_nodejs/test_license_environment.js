import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { licenseBypass } from './services/licenseEnvironment.js';
test('explicit server environment is required; production cannot bypass', () => {
  assert.equal(licenseBypass({}), false);
  assert.equal(licenseBypass({APP_ENV:'homologation',TEST_LICENSE_BYPASS:'true'}), true);
  assert.equal(licenseBypass({APP_ENV:'homologation',TEST_LICENSE_BYPASS:'false'}), false);
  assert.throws(() => licenseBypass({APP_ENV:'production',TEST_LICENSE_BYPASS:'true'}));
});
test('test server bypasses commercial metering; default server denies missing token', () => {
  for (const bypass of [true, false]) {
    const source = [
      "import assert from 'node:assert/strict';",
      "import { checkTadeuQuota, consumeTadeuUsage } from './services/tadeuMetering.js';",
      "globalThis.fetch = () => { throw new Error('unexpected billing call'); };",
      "if (process.env.TEST_LICENSE_BYPASS === 'true') {",
      "for (const token of [null, 'old-token']) {",
      "assert.equal(await checkTadeuQuota(token), null);",
      "assert.equal(await consumeTadeuUsage({token}), null); }",
      "} else { await assert.rejects(checkTadeuQuota(null), /Ative sua licença/); }",
    ].join('\n');
    const child=spawnSync(process.execPath,['--input-type=module','-e',source],{cwd:import.meta.dirname,env:{...process.env,APP_ENV:bypass?'homologation':'production',TEST_LICENSE_BYPASS:String(bypass)},encoding:'utf8'});
    assert.equal(child.status,0,child.stderr);
  }
});
