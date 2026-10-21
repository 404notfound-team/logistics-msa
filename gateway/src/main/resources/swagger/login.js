/* local Swagger only. Credentials and tokens never enter browser storage. */
const logisticsSwaggerLogin = (() => {
  let token = null;
  let username = "";
  let expiry = 0;
  let timer;
  let pending;
  let generation = 0;
  let message = "로그인하면 모든 서비스 API에 인증이 자동 적용됩니다.";

  const publicPaths = new Set(["/api/users/signin", "/api/users/signup"]);
  const ui = () => window.ui;
  const authenticatedApi = (url) =>
    url.origin === window.location.origin && url.pathname.startsWith("/api/") && !publicPaths.has(url.pathname);

  function render() {
    const form = document.getElementById("swagger-login");
    if (!form) return;
    form.querySelector("[role=status]").textContent = token ? `${username} 로그인됨 · 서비스 선택 후 바로 실행하세요.` : message;
    form.querySelector("[type=submit]").disabled = Boolean(pending);
    form.querySelector("[type=submit]").textContent = pending ? "로그인 중…" : "로그인";
    form.querySelector("[data-logout]").hidden = !token && !pending;
  }

  function clear(text = "로그아웃되었습니다.") {
    generation += 1;
    pending?.abort();
    pending = null;
    token = null;
    username = "";
    expiry = 0;
    clearTimeout(timer);
    message = text;
    const form = document.getElementById("swagger-login");
    if (form) form.elements.password.value = "";
    render();
  }

  function logout(text) {
    clear(text);
    ui()?.authActions.logout(["bearerAuth"]);
  }

  function authorize() {
    if (token && ui()?.specSelectors.securityDefinitions()?.get("bearerAuth")) {
      ui().preauthorizeApiKey("bearerAuth", token);
    }
  }

  function accept(data, name) {
    if (typeof data?.access_token !== "string" || !(data.expires_in > 0)) {
      throw new Error("로그인 응답을 확인할 수 없습니다. 다시 로그인하세요.");
    }
    token = data.access_token;
    username = name;
    expiry = Date.now() + data.expires_in * 1000;
    clearTimeout(timer);
    timer = setTimeout(() => logout("로그인이 만료되었습니다. 다시 로그인하세요."), data.expires_in * 1000);
    authorize();
    render();
  }

  async function signin(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const name = form.elements.username.value.trim();
    const password = form.elements.password.value;
    logout("로그인 중입니다.");
    const version = generation;
    const controller = new AbortController();
    pending = controller;
    render();
    try {
      const response = await fetch("/api/users/signin", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username: name, password }),
        signal: controller.signal,
        credentials: "same-origin",
        redirect: "error",
      });
      const body = await response.json();
      if (version !== generation) return;
      if (!response.ok) throw new Error(body.message || "로그인하지 못했습니다. 입력 내용을 확인하세요.");
      accept(body.data, name);
    } catch (error) {
      if (version === generation && error.name !== "AbortError") {
        message = error instanceof TypeError ? "서버에 연결하지 못했습니다. 잠시 후 다시 시도하세요." : error.message;
      }
    } finally {
      if (version === generation) {
        form.elements.password.value = "";
        pending = null;
      }
      render();
    }
  }

  function complete() {
    if (!document.getElementById("swagger-login")) {
      const style = document.createElement("style");
      style.textContent = `
        #swagger-login{font-family:sans-serif;max-width:1460px;margin:20px auto;padding:16px 20px;background:#f3f7f5;border:1px solid #c4d8ce;box-sizing:border-box}
        #swagger-login .fields{display:flex;align-items:end;gap:12px;flex-wrap:wrap}
        #swagger-login label{display:grid;gap:6px;font-size:14px;font-weight:600;color:#26352d}
        #swagger-login input{font:inherit;padding:9px 10px;border:1px solid #84968c;border-radius:4px;min-width:180px;box-sizing:border-box}
        #swagger-login button{font:600 14px sans-serif;padding:10px 16px;border:1px solid #28774e;border-radius:4px;background:#28774e;color:white;cursor:pointer}
        #swagger-login button[data-logout]{background:white;color:#245e41}
        #swagger-login button:disabled{opacity:.65;cursor:wait}
        #swagger-login [role=status]{font-size:14px;line-height:1.5;margin:12px 0 0;color:#26352d}
        #swagger-login :focus-visible{outline:2px solid #1b6241;outline-offset:2px}
        @media(max-width:600px){#swagger-login{margin:12px}#swagger-login label{width:100%}#swagger-login input{width:100%}}
      `;
      document.head.append(style);
      const form = document.createElement("form");
      form.id = "swagger-login";
      form.setAttribute("aria-label", "API 로그인");
      form.innerHTML = `<div class="fields"><label>아이디<input name="username" autocomplete="username" required></label><label>비밀번호<input name="password" type="password" autocomplete="off" required></label><button type="submit">로그인</button><button type="button" data-logout hidden>로그아웃</button></div><p role="status" aria-live="polite"></p>`;
      form.addEventListener("submit", signin);
      form.querySelector("[data-logout]").addEventListener("click", () => logout());
      document.getElementById("swagger-ui").before(form);
    }
    authorize();
    render();
  }

  function request(request) {
    const url = new URL(request.url, window.location.href);
    request.headers ||= {};
    if (url.origin === window.location.origin && url.pathname === "/api/users/signin" && request.method?.toUpperCase() === "POST") {
      const version = ++generation;
      pending?.abort();
      pending = null;
      render();
      // 이 요청의 응답만 반영한다. 로그아웃·새 로그인 뒤 도착한 응답은 무시한다.
      request.responseInterceptor = (result) => {
        if (version === generation && result.status === 200) {
          const raw = result.obj || result.body || result.text || result.data;
          const body = typeof raw === "string" ? JSON.parse(raw) : raw;
          const payload = JSON.parse(atob(body.data.access_token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/")));
          accept(body.data, payload.sub);
        }
        return result;
      };
    }
    if (!authenticatedApi(url)) {
      for (const name of Object.keys(request.headers)) {
        if (name.toLowerCase() === "authorization") delete request.headers[name];
      }
    } else {
      if (token && Date.now() >= expiry) {
        logout("로그인이 만료되었습니다. 다시 로그인하세요.");
        for (const name of Object.keys(request.headers)) {
          if (name.toLowerCase() === "authorization") delete request.headers[name];
        }
      }
      if (token) {
        for (const name of Object.keys(request.headers)) {
          if (name.toLowerCase() === "authorization") delete request.headers[name];
        }
        request.headers.Authorization = `Bearer ${token}`;
      }
    }
    return request;
  }

  function plugin() {
    return { statePlugins: { auth: { wrapActions: {
      logout: (original) => (...args) => {
        if (token || pending) clear();
        else generation += 1;
        return original(...args);
      },
    } } } };
  }

  return { complete, request, plugin };
})();
