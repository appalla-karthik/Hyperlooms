const byId = (id) => document.getElementById(id);
const set = (id, value) => { byId(id).textContent = value; };
const date = (value) => value ? new Date(value).toLocaleString() : "Never";

function renderGPU(gpu) {
  const row = document.createElement("div");
  row.className = "gpu";
  const info = document.createElement("div");
  const name = document.createElement("div");
  name.className = "gpu-name";
  name.textContent = gpu.model;
  const id = document.createElement("div");
  id.className = "gpu-id";
  id.textContent = gpu.uuid;
  const spec = document.createElement("div");
  spec.className = "gpu-spec";
  spec.textContent = `${(gpu.vramBytes / 1073741824).toFixed(1)} GiB VRAM · Driver ${gpu.driverVersion} · CUDA ${gpu.cudaVersion}`;
  info.append(name, id, spec);
  const badge = document.createElement("span");
  badge.className = "gpu-status";
  badge.textContent = "DETECTED";
  row.append(info, badge);
  return row;
}

function render(data) {
  const connection = data.connection.replaceAll("_", " ");
  set("connection-label", connection === "not configured" ? "Not configured" : connection.charAt(0).toUpperCase() + connection.slice(1));
  byId("connection-badge").className = `status-badge ${data.connection === "disconnected" ? "disconnected" : data.connection === "not_configured" ? "not-configured" : ""}`;
  set("agent-id", data.agentId);
  set("agent-version", `Version ${data.version}`);
  set("node-id", data.nodeId || "Not enrolled");
  set("control-plane", data.controlPlane || "Not configured");
  set("readiness", data.connection === "not_configured" ? "Local service only" : data.ready ? "Connected" : "Not ready");
  set("last-connected", date(data.lastConnected));
  set("heartbeat-detail", date(data.lastHeartbeat));
  set("last-heartbeat", data.lastHeartbeat ? `Heartbeat ${date(data.lastHeartbeat)}` : "No heartbeat yet");

  const list = byId("gpu-list");
  list.replaceChildren();
  const gpus = Array.isArray(data.gpus) ? data.gpus : [];
  set("gpu-count", `${gpus.length} detected`);
  if (gpus.length) {
    gpus.forEach((gpu) => list.append(renderGPU(gpu)));
  } else {
    const empty = document.createElement("p");
    empty.className = "empty";
    empty.textContent = "No supported NVIDIA GPU detected. The agent needs a compatible driver and nvidia-smi on PATH.";
    list.append(empty);
  }

  const diagnostic = byId("diagnostic");
  diagnostic.hidden = !data.lastError && !data.hardwareError;
  set("diagnostic-message", [data.lastError, data.hardwareError].filter(Boolean).join(" · "));
  const events = byId("event-list");
  events.replaceChildren();
  const recent = Array.isArray(data.events) ? data.events.slice(-8).reverse() : [];
  if (!recent.length) {
    const empty = document.createElement("p");
    empty.className = "empty";
    empty.textContent = "No events yet.";
    events.append(empty);
  } else {
    recent.forEach((event) => {
      const row = document.createElement("div");
      row.className = "event";
      const kind = document.createElement("span");
      kind.className = "event-kind";
      kind.textContent = event.kind;
      const message = document.createElement("span");
      message.className = "event-message";
      message.textContent = event.message;
      const when = document.createElement("time");
      when.className = "event-time";
      when.dateTime = event.at;
      when.textContent = date(event.at);
      row.append(kind, message, when);
      events.append(row);
    });
  }
  set("next-message", data.connection === "not_configured"
    ? "This agent is running locally and checking your GPU, but it is not enrolled. An operator must issue an invite, bind your client certificate, and provide connection settings before a secure session can start."
    : data.connection === "connected"
      ? "Secure reporting is active. Your GPU still needs independent verification before it can accept a workload."
      : "The agent is retrying its outbound secure connection. Check the diagnostic above, certificate paths, network reachability, and NVIDIA discovery.");
}

async function refresh() {
  try {
    const response = await fetch("/api/status", { cache: "no-store" });
    if (!response.ok) throw new Error(`Status request failed (${response.status})`);
    render(await response.json());
  } catch {
    set("connection-label", "Console offline");
    byId("connection-badge").className = "status-badge disconnected";
  }
}

refresh();
setInterval(refresh, 3000);
