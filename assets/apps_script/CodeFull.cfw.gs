/**
 * DomainFront Relay + Full Tunnel + optional Cloudflare Worker exit.
 *
 * Drop-in replacement for assets/apps_script/CodeFull.gs that adds an
 * optional Cloudflare Worker hop on the apps_script-mode HTTP relay
 * path while leaving the full-tunnel path completely unchanged.
 *
 * This file is a fork-friendly merge of two upstream files:
 *   - assets/apps_script/CodeFull.gs   (tunnel + direct relay)
 *   - assets/apps_script/Code.cfw.gs   (Worker-fronted relay)
 *
 * Both upstream files are intentionally NOT modified. When upstream
 * changes either of them, re-sync into this file by:
 *   - Tunnel + relay sections → diff against CodeFull.gs
 *   - Worker forwarding section (clearly marked below) → replace
 *     wholesale from Code.cfw.gs
 *
 * Wire protocol with mhrv-rs is byte-identical to upstream:
 *   1. Single relay:  POST { k, m, u, h, b, ct, r }            → { s, h, b }
 *   2. Batch relay:   POST { k, q: [{m,u,h,b,ct,r}, ...] }     → { q: [...] }
 *   3. Tunnel:        POST { k, t, h, p, sid, d }              → { sid, d, eof }
 *   4. Tunnel batch:  POST { k, t:"batch", ops:[...] }         → { r: [...] }
 *
 * Worker behaviour is opt-in. If WORKER_URL is left empty / placeholder,
 * this file behaves exactly like upstream CodeFull.gs (UrlFetchApp does
 * the relay fetch directly inside Apps Script). If WORKER_URL is set to
 * a deployed Worker, the relay path forwards to it instead — saving GAS
 * UrlFetchApp quota and lowering per-fetch latency.
 *
 * Tunnel-mode is unaffected by WORKER_URL either way: tunnel ops always
 * go straight to TUNNEL_SERVER_URL. The Cloudflare Worker cannot speak
 * the tunnel protocol (no persistent TCP, no UDP ASSOCIATE), so this is
 * by design.
 *
 * The mhrv-rs client does not need to know whether the Worker is in
 * the path. Same script_id, same auth_key, same mode field — flipping
 * the Worker on/off is a pure server-side change.
 *
 * CHANGE THESE TO YOUR OWN VALUES!
 */

const AUTH_KEY = "CHANGE_ME_TO_A_STRONG_SECRET";
const TUNNEL_SERVER_URL = "https://YOUR_TUNNEL_NODE_URL";
const TUNNEL_AUTH_KEY = "YOUR_TUNNEL_AUTH_KEY";

// Optional Cloudflare Worker exit. Set to your deployed *.workers.dev URL
// (must include `https://`) to route the apps_script-mode relay path
// through the Worker. Leave empty (or as the placeholder) to use the
// upstream behaviour: UrlFetchApp.fetch directly from inside Apps Script.
//
// If set, the Worker must be deployed from assets/cloudflare/worker.js
// and its AUTH_KEY must equal this script's AUTH_KEY.
const WORKER_URL = "";

// Must match the Worker's MAX_BATCH_SIZE (default 40 in worker.js).
// Batches larger than this are split into multiple fetches; each chunk
// costs 1 GAS UrlFetchApp call.
const WORKER_BATCH_CHUNK = 40;

// Active-probing defense. When false (production default), bad AUTH_KEY
// requests get a decoy HTML page that looks like a placeholder Apps
// Script web app instead of the JSON `{"e":"unauthorized"}` body. This
// makes the deployment indistinguishable from a forgotten-but-public
// Apps Script project to active scanners.
const DIAGNOSTIC_MODE = false;

// Connection-level + IP-leak request headers we strip before forwarding
// (whether to UrlFetchApp directly or to the Worker). Superset of the
// upstream Code.cfw.gs list — adding the X-Forwarded-* / Forwarded /
// Via family is strictly safer for IP-leak prevention and doesn't break
// anything on the Worker path.
const SKIP_HEADERS = {
  host: 1, connection: 1, "content-length": 1,
  "transfer-encoding": 1, "proxy-connection": 1, "proxy-authorization": 1,
  "priority": 1, te: 1,
  "x-forwarded-for": 1, "x-forwarded-host": 1, "x-forwarded-proto": 1,
  "x-forwarded-port": 1, "x-real-ip": 1, "forwarded": 1, "via": 1,
};

// Methods we consider safe to replay if `UrlFetchApp.fetchAll()` raises
// on the direct path. GET/HEAD/OPTIONS are idempotent per RFC 9110;
// POST/PUT/PATCH/DELETE can have side-effects so we surface the error
// instead of silently re-firing them.
const SAFE_REPLAY_METHODS = { GET: 1, HEAD: 1, OPTIONS: 1 };

// HTML body for the bad-auth decoy. Mimics a minimal Apps Script-style
// placeholder page — no proxy-shaped JSON, nothing distinctive enough
// for a probe to fingerprint as a tunnel endpoint.
const DECOY_HTML =
  '<!DOCTYPE html><html><head><title>Web App</title></head>' +
  '<body><p>The script completed but did not return anything.</p>' +
  '</body></html>';

// Edge DNS cache. Plain UDP/53 queries normally traverse the full
// client → GAS → tunnel-node → public resolver path; the trans-Atlantic
// round-trip dominates first-hop latency. When ENABLE_EDGE_DNS_CACHE
// is true, _doTunnelBatch intercepts udp_open ops with port=53, serves
// the reply from CacheService on a hit, or does its own DoH lookup on
// a miss from inside Google's network. Cache hits never reach the
// tunnel-node.
const ENABLE_EDGE_DNS_CACHE = true;

const EDGE_DNS_RESOLVERS = [
  "https://1.1.1.1/dns-query",
  "https://dns.google/dns-query",
  "https://dns.quad9.net/dns-query",
];

const EDGE_DNS_MIN_TTL_S = 30;
const EDGE_DNS_MAX_TTL_S = 21600;
const EDGE_DNS_NEG_TTL_S = 45;
const EDGE_DNS_CACHE_PREFIX = "edns:";
const EDGE_DNS_MAX_KEY_LEN = 240;
const EDGE_DNS_REFUSE_QTYPES = { 255: 1 };

function _decoyOrError(jsonBody) {
  if (DIAGNOSTIC_MODE) return _json(jsonBody);
  return ContentService
    .createTextOutput(DECOY_HTML)
    .setMimeType(ContentService.MimeType.HTML);
}

// Returns true when WORKER_URL points at a real, deployed Worker.
// Empty / placeholder / missing scheme all fall through to the direct
// UrlFetchApp path — i.e. upstream CodeFull.gs behaviour.
function _useWorker() {
  return typeof WORKER_URL === "string"
    && WORKER_URL.length > 0
    && WORKER_URL.indexOf("://") > 0
    && WORKER_URL.indexOf("CHANGE_ME") === -1;
}

// ========================== Entry point ==========================

function doPost(e) {
  try {
    var req = JSON.parse(e.postData.contents);
    if (req.k !== AUTH_KEY) return _decoyOrError({ e: "unauthorized" });

    // Tunnel mode — never goes through the Worker.
    if (req.t) return _doTunnel(req);

    // Batch relay mode
    if (Array.isArray(req.q)) return _doBatch(req.q);

    // Single relay mode
    return _doSingle(req);
  } catch (err) {
    return _decoyOrError({ e: String(err) });
  }
}

// `doGet` is what active scanners hit first. ContentService keeps this
// indistinguishable from a forgotten static-HTML web app.
function doGet(e) {
  return ContentService
    .createTextOutput(DECOY_HTML)
    .setMimeType(ContentService.MimeType.HTML);
}

// ========================== Tunnel mode ==========================
// Verbatim from upstream CodeFull.gs. Tunnel ops never touch the Worker.

function _doTunnel(req) {
  if (req.t === "batch") {
    return _doTunnelBatch(req);
  }

  var payload = { k: TUNNEL_AUTH_KEY };
  switch (req.t) {
    case "connect":
      payload.op = "connect";
      payload.host = req.h;
      payload.port = req.p;
      break;
    case "connect_data":
      payload.op = "connect_data";
      payload.host = req.h;
      payload.port = req.p;
      if (req.d) payload.data = req.d;
      break;
    case "data":
      payload.op = "data";
      payload.sid = req.sid;
      if (req.d) payload.data = req.d;
      break;
    case "close":
      payload.op = "close";
      payload.sid = req.sid;
      break;
    default:
      return _json({ e: "unknown tunnel op: " + req.t, code: "UNSUPPORTED_OP" });
  }

  var resp = UrlFetchApp.fetch(TUNNEL_SERVER_URL + "/tunnel", {
    method: "post",
    contentType: "application/json",
    payload: JSON.stringify(payload),
    muteHttpExceptions: true,
    followRedirects: true,
  });

  if (resp.getResponseCode() !== 200) {
    return _json({ e: "tunnel node HTTP " + resp.getResponseCode() });
  }

  return ContentService.createTextOutput(resp.getContentText())
    .setMimeType(ContentService.MimeType.JSON);
}

function _doTunnelBatch(req) {
  var ops = (req && req.ops) || [];

  if (!ENABLE_EDGE_DNS_CACHE) {
    return _doTunnelBatchForward(ops);
  }

  var results = new Array(ops.length);
  var forwardOps = [];
  var forwardIdx = [];

  for (var i = 0; i < ops.length; i++) {
    var op = ops[i];
    if (op && op.op === "udp_open" && op.port === 53 && op.d) {
      var synth = _edgeDnsTry(op);
      if (synth) {
        results[i] = synth;
        continue;
      }
    }
    forwardOps.push(op);
    forwardIdx.push(i);
  }

  if (forwardOps.length === 0) {
    return _json({ r: results });
  }

  if (forwardOps.length === ops.length) {
    return _doTunnelBatchForward(ops);
  }

  var resp = _doTunnelBatchFetch(forwardOps);
  if (resp.error) return _json({ e: resp.error });
  if (resp.r.length !== forwardOps.length) {
    return _json({ e: "tunnel batch length mismatch" });
  }
  return _json({ r: _spliceTunnelResults(forwardIdx, resp.r, results) });
}

function _doTunnelBatchForward(ops) {
  var resp = UrlFetchApp.fetch(TUNNEL_SERVER_URL + "/tunnel/batch", {
    method: "post",
    contentType: "application/json",
    payload: JSON.stringify({ k: TUNNEL_AUTH_KEY, ops: ops }),
    muteHttpExceptions: true,
    followRedirects: true,
  });
  if (resp.getResponseCode() !== 200) {
    return _json({ e: "tunnel batch HTTP " + resp.getResponseCode() });
  }
  return ContentService.createTextOutput(resp.getContentText())
    .setMimeType(ContentService.MimeType.JSON);
}

function _doTunnelBatchFetch(ops) {
  var resp = UrlFetchApp.fetch(TUNNEL_SERVER_URL + "/tunnel/batch", {
    method: "post",
    contentType: "application/json",
    payload: JSON.stringify({ k: TUNNEL_AUTH_KEY, ops: ops }),
    muteHttpExceptions: true,
    followRedirects: true,
  });
  if (resp.getResponseCode() !== 200) {
    return { error: "tunnel batch HTTP " + resp.getResponseCode() };
  }
  try {
    var parsed = JSON.parse(resp.getContentText());
    return { r: (parsed && parsed.r) || [] };
  } catch (err) {
    return { error: "tunnel batch parse error" };
  }
}

function _spliceTunnelResults(forwardIdx, forwardedResults, allResults) {
  for (var j = 0; j < forwardIdx.length; j++) {
    allResults[forwardIdx[j]] = forwardedResults[j];
  }
  return allResults;
}

// ========================== HTTP relay mode ==========================
//
// Two paths share these entry points. When _useWorker() is true, the
// request is forwarded to the Cloudflare Worker (lifted from
// Code.cfw.gs). Otherwise the upstream CodeFull.gs direct path runs.
//
// The branch is at the top of each entry function so the rest of each
// function body is byte-equivalent to upstream — this minimises the
// diff against CodeFull.gs whenever upstream evolves the direct path.

function _doSingle(req) {
  if (_useWorker()) return _doSingleViaWorker(req);

  if (!req.u || typeof req.u !== "string" || !req.u.match(/^https?:\/\//i)) {
    return _json({ e: "bad url" });
  }
  var opts = _buildOpts(req);
  var resp = UrlFetchApp.fetch(req.u, opts);
  return _json({
    s: resp.getResponseCode(),
    h: _respHeaders(resp),
    b: Utilities.base64Encode(resp.getContent()),
  });
}

function _doBatch(items) {
  if (_useWorker()) return _doBatchViaWorker(items);

  var fetchArgs = [];
  var fetchIndex = [];
  var fetchMethods = [];
  var errorMap = {};
  for (var i = 0; i < items.length; i++) {
    var item = items[i];
    if (!item || typeof item !== "object") {
      errorMap[i] = "bad item";
      continue;
    }
    if (!item.u || typeof item.u !== "string" || !item.u.match(/^https?:\/\//i)) {
      errorMap[i] = "bad url";
      continue;
    }
    try {
      var opts = _buildOpts(item);
      opts.url = item.u;
      fetchArgs.push(opts);
      fetchIndex.push(i);
      fetchMethods.push(String(item.m || "GET").toUpperCase());
    } catch (buildErr) {
      errorMap[i] = String(buildErr);
    }
  }

  var responses = [];
  if (fetchArgs.length > 0) {
    try {
      responses = UrlFetchApp.fetchAll(fetchArgs);
    } catch (fetchAllErr) {
      responses = [];
      for (var j = 0; j < fetchArgs.length; j++) {
        try {
          if (!SAFE_REPLAY_METHODS[fetchMethods[j]]) {
            errorMap[fetchIndex[j]] =
              "batch fetchAll failed; unsafe method not replayed";
            responses[j] = null;
            continue;
          }
          var fallbackReq = fetchArgs[j];
          var fallbackUrl = fallbackReq.url;
          var fallbackOpts = {};
          for (var key in fallbackReq) {
            if (
              Object.prototype.hasOwnProperty.call(fallbackReq, key) &&
              key !== "url"
            ) {
              fallbackOpts[key] = fallbackReq[key];
            }
          }
          responses[j] = UrlFetchApp.fetch(fallbackUrl, fallbackOpts);
        } catch (singleErr) {
          errorMap[fetchIndex[j]] = String(singleErr);
          responses[j] = null;
        }
      }
    }
  }

  var results = [];
  var rIdx = 0;
  for (var i = 0; i < items.length; i++) {
    if (Object.prototype.hasOwnProperty.call(errorMap, i)) {
      results.push({ e: errorMap[i] });
    } else {
      var resp = responses[rIdx++];
      if (!resp) {
        results.push({ e: "fetch failed" });
      } else {
        results.push({
          s: resp.getResponseCode(),
          h: _respHeaders(resp),
          b: Utilities.base64Encode(resp.getContent()),
        });
      }
    }
  }
  return _json({ q: results });
}

// ─────────────────────────────────────────────────────────────────────
// Worker forwarding — BEGIN
//
// Block lifted from upstream assets/apps_script/Code.cfw.gs. Re-sync
// wholesale from upstream when that file changes; do not interleave
// edits with the rest of this file. The functions below depend only on
// AUTH_KEY, WORKER_URL, WORKER_BATCH_CHUNK, SKIP_HEADERS, and _json —
// all defined above.
// ─────────────────────────────────────────────────────────────────────

function _scrubHeaders(rawHeaders) {
  var out = {};
  if (rawHeaders && typeof rawHeaders === "object") {
    for (var k in rawHeaders) {
      if (rawHeaders.hasOwnProperty(k) && !SKIP_HEADERS[k.toLowerCase()]) {
        out[k] = rawHeaders[k];
      }
    }
  }
  return out;
}

function _normalizeItem(item) {
  return {
    u: item.u,
    m: (item.m || "GET").toUpperCase(),
    h: _scrubHeaders(item.h),
    b: item.b || null,
    ct: item.ct || null,
    r: item.r !== false,
  };
}

function _workerFetchOptions(payload) {
  return {
    url: WORKER_URL,
    method: "post",
    contentType: "application/json",
    payload: JSON.stringify(payload),
    muteHttpExceptions: true,
    followRedirects: true,
    validateHttpsCertificates: true,
  };
}

function _doSingleViaWorker(req) {
  if (!req.u || typeof req.u !== "string" || !req.u.match(/^https?:\/\//i)) {
    return _json({ e: "bad url" });
  }

  var item = _normalizeItem(req);
  var envelope = {
    k: AUTH_KEY,
    u: item.u,
    m: item.m,
    h: item.h,
    b: item.b,
    ct: item.ct,
    r: item.r,
  };
  var opts = _workerFetchOptions(envelope);
  var resp;
  try {
    resp = UrlFetchApp.fetch(opts.url, opts);
  } catch (err) {
    return _json({ e: "worker unreachable: " + String(err) });
  }
  return _json(_parseWorkerJson(resp));
}

function _doBatchViaWorker(items) {
  var validItems = [];
  var errorMap = {};

  for (var i = 0; i < items.length; i++) {
    var item = items[i];
    if (!item || typeof item !== "object") {
      errorMap[i] = "bad item";
      continue;
    }
    if (!item.u || typeof item.u !== "string" || !item.u.match(/^https?:\/\//i)) {
      errorMap[i] = "bad url";
      continue;
    }
    validItems.push(_normalizeItem(item));
  }

  var workerResults = [];
  if (validItems.length > 0) {
    var chunks = [];
    for (var c = 0; c < validItems.length; c += WORKER_BATCH_CHUNK) {
      chunks.push(validItems.slice(c, c + WORKER_BATCH_CHUNK));
    }

    var fetchOpts = chunks.map(function(chunk) {
      return _workerFetchOptions({ k: AUTH_KEY, q: chunk });
    });

    var responses;
    try {
      if (fetchOpts.length === 1) {
        responses = [UrlFetchApp.fetch(fetchOpts[0].url, fetchOpts[0])];
      } else {
        responses = UrlFetchApp.fetchAll(fetchOpts);
      }
    } catch (err) {
      var unreachable = { e: "worker unreachable: " + String(err) };
      for (var u = 0; u < validItems.length; u++) workerResults.push(unreachable);
      responses = null;
    }

    for (var r = 0; responses && r < responses.length; r++) {
      var parsed = _parseWorkerJson(responses[r]);
      if (parsed && Array.isArray(parsed.q)) {
        for (var k = 0; k < parsed.q.length; k++) {
          workerResults.push(parsed.q[k]);
        }
      } else {
        var slotErr = (parsed && parsed.e)
          ? { e: parsed.e }
          : { e: "worker batch failure" };
        for (var s = 0; s < chunks[r].length; s++) workerResults.push(slotErr);
      }
    }
  }

  var results = [];
  var wi = 0;
  for (var j = 0; j < items.length; j++) {
    if (errorMap.hasOwnProperty(j)) {
      results.push({ e: errorMap[j] });
    } else {
      results.push(workerResults[wi++] || { e: "missing worker response" });
    }
  }
  return _json({ q: results });
}

function _parseWorkerJson(resp) {
  var code = resp.getResponseCode();
  var text = resp.getContentText();
  try {
    return JSON.parse(text);
  } catch (err) {
    return { e: "worker " + code + ": " + (text.length > 200 ? text.substring(0, 200) + "…" : text) };
  }
}

// ─────────────────────────────────────────────────────────────────────
// Worker forwarding — END
// ─────────────────────────────────────────────────────────────────────

// ========================== Common helpers ==========================
// Used by the direct (non-Worker) relay path only.

function _buildOpts(req) {
  var opts = {
    method: (req.m || "GET").toLowerCase(),
    muteHttpExceptions: true,
    followRedirects: req.r !== false,
    validateHttpsCertificates: true,
    escaping: false,
  };
  if (req.h && typeof req.h === "object") {
    var headers = {};
    for (var k in req.h) {
      if (req.h.hasOwnProperty(k) && !SKIP_HEADERS[k.toLowerCase()]) {
        headers[k] = req.h[k];
      }
    }
    opts.headers = headers;
  }
  if (req.b) {
    opts.payload = Utilities.base64Decode(req.b);
    if (req.ct) opts.contentType = req.ct;
  }
  return opts;
}

function _respHeaders(resp) {
  try {
    if (typeof resp.getAllHeaders === "function") {
      return resp.getAllHeaders();
    }
  } catch (err) {}
  return resp.getHeaders();
}

function _json(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(
    ContentService.MimeType.JSON
  );
}

// ========================== Edge DNS helpers ==========================
// Verbatim from upstream CodeFull.gs.

function _edgeDnsTry(op) {
  try {
    var bytes = Utilities.base64Decode(op.d);
    if (!bytes || bytes.length < 12) return null;

    var q = _dnsParseQuestion(bytes);
    if (!q) return null;
    if (EDGE_DNS_REFUSE_QTYPES[q.qtype]) return null;

    var key = EDGE_DNS_CACHE_PREFIX + q.qtype + ":" + q.qname;
    if (key.length > EDGE_DNS_MAX_KEY_LEN) return null;
    var cache = CacheService.getScriptCache();

    var stored = null;
    try { stored = cache.get(key); } catch (_) {}
    if (stored) {
      try {
        var hit = Utilities.base64Decode(stored);
        if (hit && hit.length >= 12) {
          var rewritten = _dnsRewriteTxid(hit, q.txid);
          return {
            sid: "edns-cache",
            pkts: [Utilities.base64Encode(rewritten)],
            eof: true,
          };
        }
      } catch (_) { /* corrupt cache entry — fall through to DoH */ }
    }

    for (var i = 0; i < EDGE_DNS_RESOLVERS.length; i++) {
      var reply = _edgeDnsDoh(EDGE_DNS_RESOLVERS[i], bytes);
      if (!reply) continue;

      var rcode = reply[3] & 0x0F;
      var ttl;
      if (rcode === 2 || rcode === 3) {
        ttl = EDGE_DNS_NEG_TTL_S;
      } else {
        var minTtl = _dnsMinTtl(reply);
        ttl = (minTtl === null) ? EDGE_DNS_NEG_TTL_S : minTtl;
        if (ttl < EDGE_DNS_MIN_TTL_S) ttl = EDGE_DNS_MIN_TTL_S;
        if (ttl > EDGE_DNS_MAX_TTL_S) ttl = EDGE_DNS_MAX_TTL_S;
      }

      try {
        cache.put(key, Utilities.base64Encode(reply), ttl);
      } catch (_) {
        // >100KB value or transient quota — still return the live answer.
      }

      var fixed = _dnsRewriteTxid(reply, q.txid);
      return {
        sid: "edns-doh",
        pkts: [Utilities.base64Encode(fixed)],
        eof: true,
      };
    }
    return null;
  } catch (err) {
    return null;
  }
}

function _edgeDnsDoh(url, queryBytes) {
  try {
    var dns = Utilities.base64EncodeWebSafe(queryBytes).replace(/=+$/, "");
    var resp = UrlFetchApp.fetch(url + "?dns=" + dns, {
      method: "get",
      muteHttpExceptions: true,
      followRedirects: true,
      headers: { accept: "application/dns-message" },
    });
    if (resp.getResponseCode() !== 200) return null;
    var body = resp.getContent();
    if (!body || body.length < 12) return null;
    return body;
  } catch (err) {
    return null;
  }
}

function _dnsParseQuestion(bytes) {
  if (bytes.length < 12) return null;
  var qdcount = ((bytes[4] & 0xFF) << 8) | (bytes[5] & 0xFF);
  if (qdcount !== 1) return null;

  var off = 12;
  var labels = [];
  var nameLen = 0;
  while (off < bytes.length) {
    var len = bytes[off] & 0xFF;
    if (len === 0) { off++; break; }
    if ((len & 0xC0) !== 0) return null;
    if (len > 63) return null;
    off++;
    if (off + len > bytes.length) return null;
    var label = "";
    for (var i = 0; i < len; i++) {
      var c = bytes[off + i] & 0xFF;
      if (c >= 0x41 && c <= 0x5A) c += 0x20;
      label += String.fromCharCode(c);
    }
    labels.push(label);
    off += len;
    nameLen += len + 1;
    if (nameLen > 255) return null;
  }
  if (off + 4 > bytes.length) return null;
  var qtype = ((bytes[off] & 0xFF) << 8) | (bytes[off + 1] & 0xFF);

  return {
    txid: ((bytes[0] & 0xFF) << 8) | (bytes[1] & 0xFF),
    qname: labels.join("."),
    qtype: qtype,
  };
}

function _dnsMinTtl(bytes) {
  if (bytes.length < 12) return null;
  var qdcount = ((bytes[4] & 0xFF) << 8) | (bytes[5] & 0xFF);
  var ancount = ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
  var nscount = ((bytes[8] & 0xFF) << 8) | (bytes[9] & 0xFF);

  var off = 12;
  for (var q = 0; q < qdcount; q++) {
    off = _dnsSkipName(bytes, off);
    if (off < 0 || off + 4 > bytes.length) return null;
    off += 4;
  }

  var min = null;
  var rrTotal = ancount + nscount;
  for (var r = 0; r < rrTotal; r++) {
    off = _dnsSkipName(bytes, off);
    if (off < 0 || off + 10 > bytes.length) return null;
    var ttl = ((bytes[off + 4] & 0xFF) * 0x1000000)
            + (((bytes[off + 5] & 0xFF) << 16)
            |  ((bytes[off + 6] & 0xFF) << 8)
            |   (bytes[off + 7] & 0xFF));
    if (ttl < 0 || ttl > 0x7FFFFFFF) ttl = 0;
    if (min === null || ttl < min) min = ttl;
    var rdlen = ((bytes[off + 8] & 0xFF) << 8) | (bytes[off + 9] & 0xFF);
    off += 10 + rdlen;
    if (off > bytes.length) return null;
  }
  return min;
}

function _dnsSkipName(bytes, off) {
  while (off < bytes.length) {
    var len = bytes[off] & 0xFF;
    if (len === 0) return off + 1;
    if ((len & 0xC0) === 0xC0) {
      if (off + 2 > bytes.length) return -1;
      return off + 2;
    }
    if ((len & 0xC0) !== 0) return -1;
    if (len > 63) return -1;
    off += 1 + len;
  }
  return -1;
}

function _dnsRewriteTxid(bytes, txid) {
  var out = [];
  for (var i = 0; i < bytes.length; i++) out.push(bytes[i]);
  var hi = (txid >> 8) & 0xFF;
  var lo = txid & 0xFF;
  out[0] = hi > 127 ? hi - 256 : hi;
  out[1] = lo > 127 ? lo - 256 : lo;
  return out;
}
