/**
 * Port of `dev.nylo.plugin.env.MetroToolNaming` + the Metro command from
 * `dev.nylo.plugin.externaltools.MetroExternalToolSync.createTool`.
 */

/** Display label for the Metro before-run step, e.g. "Metro make:env Prod Staging". */
export function metroToolName(displayName: string): string {
  return `Metro make:env ${displayName}`;
}

/**
 * Args for `dart <args>` that run Nylo's Metro `make:env` for [envFileName].
 * The JetBrains tool uses `--file="<name>"` (quoted because it's a single parameter string);
 * as a process-arg array no quoting is needed.
 */
export function metroMakeEnvArgs(envFileName: string): string[] {
  return ['run', 'nylo_framework:main', 'make:env', `--file=${envFileName}`];
}
