import "./style.css";

import * as api from "./api";
import { renderDashboard } from "./dashboard";
import { h } from "./dom";

const mount = document.getElementById("app");
if (!mount) throw new Error("missing #app element");
const root: HTMLElement = mount;

async function showDashboard(): Promise<void> {
  root.replaceChildren(h("div", { class: "loading" }, ["Loading dashboard…"]));
  try {
    await renderDashboard(root);
  } catch (error) {
    if (error instanceof api.ApiError && error.status === 401) {
      showLogin();
      return;
    }
    root.replaceChildren(h("div", { class: "loading" }, [`Error: ${(error as Error).message}`]));
  }
}

function showLogin(error?: string): void {
  const password = h("input", {
    type: "password",
    placeholder: "Admin password",
    autocomplete: "current-password",
  }) as HTMLInputElement;
  const message = h("div", { class: "form-error" }, error ? [error] : []);
  const form = h("form", { class: "login" }, [
    h("h1", {}, ["Firemoot admin"]),
    h("p", { class: "muted" }, ["Sign in with the configured admin password."]),
    password,
    h("button", { class: "btn", type: "submit" }, ["Sign in"]),
    message,
  ]);
  form.addEventListener("submit", (event) => {
    event.preventDefault();
    message.textContent = "";
    void (async () => {
      if (await api.login(password.value)) {
        await showDashboard();
      } else {
        message.textContent = "Incorrect password.";
        password.value = "";
        password.focus();
      }
    })();
  });
  root.replaceChildren(h("div", { class: "login-wrap" }, [form]));
  password.focus();
}

async function boot(): Promise<void> {
  if (await api.hasSession()) await showDashboard();
  else showLogin();
}

void boot();
