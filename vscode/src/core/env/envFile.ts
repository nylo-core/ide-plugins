/** Port of `dev.nylo.plugin.env.EnvFile` (the data shape; IDE-specific ids are dropped). */
export interface EnvFile {
  /** Absolute path to the env file. */
  filePath: string;
  /** Bare file name, e.g. `.env.prod`. */
  fileName: string;
  /** Suffix after `.env.`, e.g. `prod.staging`; `null` for a bare `.env`. */
  suffix: string | null;
  /** Title-cased display name, e.g. `Prod Staging` / `Default`. */
  displayName: string;
}
