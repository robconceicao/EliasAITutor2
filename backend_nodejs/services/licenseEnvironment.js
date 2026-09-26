export function licenseBypass(env = process.env) {
  const requested = env.TEST_LICENSE_BYPASS === 'true';
  if (requested && env.APP_ENV !== 'homologation') throw new Error('TEST_LICENSE_BYPASS requires APP_ENV=homologation');
  return requested && env.APP_ENV === 'homologation';
}
