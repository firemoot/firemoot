type Attr = string | ((event: Event) => void);

/**
 * Minimal hyperscript helper. `on*` keys attach listeners, `class` sets the
 * className, everything else is a plain attribute.
 */
export function h<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  attrs: Record<string, Attr> = {},
  children: (Node | string)[] = [],
): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs)) {
    if (typeof value === "function")
      node.addEventListener(key.replace(/^on/, "").toLowerCase(), value);
    else if (key === "class") node.className = value;
    else node.setAttribute(key, value);
  }
  for (const child of children) node.append(child);
  return node;
}

/** A titled card wrapper used across the dashboard. */
export function card(title: string, body: Node): HTMLElement {
  return h("section", { class: "card" }, [h("h2", { class: "card-title" }, [title]), body]);
}

/** A transient corner notification that removes itself. */
export function toast(message: string, kind: "ok" | "err" = "ok"): void {
  const node = h("div", { class: `toast toast-${kind}` }, [message]);
  document.body.append(node);
  setTimeout(() => node.remove(), 4000);
}
