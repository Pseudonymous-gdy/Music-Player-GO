const functions = require("firebase-functions");
const admin = require("firebase-admin");

admin.initializeApp();

/**
 * 读取转发配置，优先使用 functions config，其次环境变量
 * forward.server_url / forward.api_key / forward.timeout_ms
 */
function loadForwardConfig() {
  const cfg = functions.config().forward || {};
  const serverUrl = cfg.server_url || process.env.FORWARD_SERVER_URL;
  const apiKey = cfg.api_key || process.env.FORWARD_API_KEY;
  const timeoutMs = Number(cfg.timeout_ms || process.env.FORWARD_TIMEOUT_MS || 5000);
  return {serverUrl, apiKey, timeoutMs};
}

/**
 * Firestore 触发器：监听 user_behaviors 集合新增，并转发到个人服务器
 */
exports.forwardUserLogs = functions
  .region("us-central1")
  .firestore.document("user_behaviors/{docId}")
  .onCreate(async (snap, context) => {
    const {serverUrl, apiKey, timeoutMs} = loadForwardConfig();
    if (!serverUrl) {
      functions.logger.error("Missing forward.server_url (or FORWARD_SERVER_URL env). Skip forwarding.");
      return;
    }

    const data = snap.data() || {};
    const payload = {
      ...data,
      document_id: context.params.docId,
      firestore_event_time: context.timestamp,
      forwarded_at: new Date().toISOString(),
    };

    // 使用原生 fetch（Node 18+）
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);

    try {
      const res = await fetch(serverUrl, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          ...(apiKey ? {"Authorization": `Bearer ${apiKey}`} : {}),
        },
        body: JSON.stringify(payload),
        signal: controller.signal,
      });

      if (!res.ok) {
        const text = await res.text();
        functions.logger.error("Forwarding failed", {
          status: res.status,
          statusText: res.statusText,
          body: text?.slice(0, 500),
        });
        return;
      }

      functions.logger.info("Forwarded user_logs doc", {
        docId: context.params.docId,
        status: res.status,
      });
    } catch (err) {
      if (err.name === "AbortError") {
        functions.logger.error("Forwarding request timed out", {timeoutMs});
      } else {
        functions.logger.error("Forwarding error", err);
      }
    } finally {
      clearTimeout(timer);
    }
  });
