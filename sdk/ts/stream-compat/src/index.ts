/**
 * @firemoot/stream-compat - the getstream.io `stream-chat` API surface over
 * Firemoot.
 *
 * An app written against `stream-chat` switches backends by changing
 * configuration only: point `baseURL` at your Firemoot server and swap the
 * key/secret. Either alias the import in your bundler
 * (`resolve.alias['stream-chat'] = '@firemoot/stream-compat'`) or change the
 * import specifier - see the stream-compat guide.
 *
 * `StreamChat` is deliberately the exported name so the alias needs no shim.
 * `FiremootStreamCompat` is the same class under an unambiguous name.
 */
export * from "./client.js";
export * from "./channel.js";
export * from "./events.js";
export * from "./errors.js";
export * from "./message.js";
export * from "./transport.js";
export * from "./webhook.js";

import { StreamChat } from "./client.js";

/** The same class as {@link StreamChat}, named for when the alias would confuse. */
export { StreamChat as FiremootStreamCompat };

export default StreamChat;
