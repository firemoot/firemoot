export type Handler<T> = (payload: T) => void;

/**
 * A tiny strongly-typed event emitter. Handlers are stored as `(payload: never)`
 * so a handler for any specific event is assignable on the way in (parameters are
 * contravariant) without resorting to `any`; the cast back happens only at emit.
 */
export class TypedEmitter<Events> {
  private readonly handlers = new Map<keyof Events, Set<Handler<never>>>();

  on<K extends keyof Events>(type: K, handler: Handler<Events[K]>): () => void {
    let set = this.handlers.get(type);
    if (!set) {
      set = new Set();
      this.handlers.set(type, set);
    }
    set.add(handler as Handler<never>);
    return () => this.off(type, handler);
  }

  once<K extends keyof Events>(type: K, handler: Handler<Events[K]>): () => void {
    const off = this.on(type, (payload) => {
      off();
      handler(payload);
    });
    return off;
  }

  off<K extends keyof Events>(type: K, handler: Handler<Events[K]>): void {
    this.handlers.get(type)?.delete(handler as Handler<never>);
  }

  emit<K extends keyof Events>(type: K, payload: Events[K]): void {
    const set = this.handlers.get(type);
    if (!set) return;
    for (const handler of [...set]) (handler as Handler<Events[K]>)(payload);
  }

  removeAllListeners(): void {
    this.handlers.clear();
  }
}
