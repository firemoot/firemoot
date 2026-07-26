/**
 * The version named in unsupported-surface errors. Kept as a constant rather than
 * read from package.json so the build stays a plain `tsc` with no JSON emit;
 * bump it with the package version.
 */
export const COMPAT_VERSION = "0.0.0";

const GUIDE = "https://firemoot.com/guide/stream-compat";

/**
 * Thrown when an app reaches for a part of `stream-chat` this adapter does not
 * implement. The policy is deliberate: an unimplemented method **throws** rather
 * than silently doing nothing, so a migration surfaces its gaps during the first
 * test run instead of as data that quietly never arrived. The handful of genuine
 * no-ops are enumerated in the compatibility table and documented where they sit.
 */
export class FiremootCompatError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "FiremootCompatError";
  }
}

/** Signals a `stream-chat` surface with no Firemoot equivalent. */
export function unsupported(surface: string, detail?: string): never {
  throw new FiremootCompatError(
    `${surface} is not supported by @firemoot/stream-compat (v${COMPAT_VERSION})` +
      `${detail ? `: ${detail}` : ""}; see the compatibility table at ${GUIDE}`,
  );
}

/** A method that only exists to fail loudly - it never returns. */
export type UnsupportedMethod = (...args: unknown[]) => never;

/** Types the stub surface installed by [[installUnsupported]] from its name list. */
export type UnsupportedMethods<Names extends readonly string[]> = {
  [K in Names[number]]: UnsupportedMethod;
};

/**
 * Installs a throwing stub for each named method on `prototype`.
 *
 * These are defined rather than hand-written so the list stays readable and
 * auditable against `stream-chat`'s API, and non-enumerable so they do not show
 * up in `Object.keys`/spreads of an instance. A `Proxy` would have covered the
 * whole surface automatically, but it would also answer every property probe -
 * including `then`, which would make instances look thenable and break `await`.
 */
export function installUnsupported(
  prototype: object,
  owner: string,
  names: readonly string[],
): void {
  for (const name of names) {
    Object.defineProperty(prototype, name, {
      value: (): never => unsupported(`${owner}.${name}()`),
      writable: true,
      configurable: true,
      enumerable: false,
    });
  }
}
