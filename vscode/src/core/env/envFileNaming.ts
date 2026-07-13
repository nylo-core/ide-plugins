/**
 * Port of `dev.nylo.plugin.env.EnvFileNaming`.
 *
 * Converts an env-file suffix into a title-cased display name:
 *   null/blank -> "Default"; "dev" -> "Dev"; "prod.staging" -> "Prod Staging".
 * Only the first character of each dot-separated segment is upper-cased (so "prodQA" -> "ProdQA").
 */
export function envDisplayName(suffix: string | null | undefined): string {
  if (suffix == null || suffix.trim().length === 0) {
    return 'Default';
  }
  const name = suffix
    .split('.')
    .filter((segment) => segment.length > 0)
    .map((segment) => segment.charAt(0).toUpperCase() + segment.slice(1))
    .join(' ');
  return name.length === 0 ? 'Default' : name;
}
