"use strict";

// BFSI RAG Assistant web UI. All server text is inserted with textContent - never as HTML.

const $ = (id) => document.getElementById(id);
const KEY_STORE = "rag.apiKey";
const MAX_HISTORY = 3;

const state = {
    key: "",
    role: null,
    history: [],          // [{question, answer}] - earlier turns, for follow-up questions
    busy: false,
};

const FLAG_STYLE = {
    "injection-blocked": "bad",
    "uncited-answer-withheld": "bad",
    "degraded-to-extractive": "warn",
    "low-confidence": "warn",
    "not-in-sources": "warn",
    "citations-repaired": "warn",
    "follow-up-rewritten": "info",
};
const FLAG_TEXT = {
    "injection-blocked": "Instruction change blocked",
    "uncited-answer-withheld": "Uncited answer withheld",
    "degraded-to-extractive": "Model unavailable - quoted sources instead",
    "low-confidence": "Nothing relevant found",
    "not-in-sources": "Not covered by the documents",
    "citations-repaired": "Invalid citation removed",
    "follow-up-rewritten": "Follow-up understood in context",
};

function store(key, value) {
    try {
        if (value) sessionStorage.setItem(key, value); else sessionStorage.removeItem(key);
    } catch (e) { /* storage unavailable - the key just will not survive a reload */ }
}

function load(key) {
    try { return sessionStorage.getItem(key) || ""; } catch (e) { return ""; }
}

function el(tag, cls, text) {
    const n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text !== undefined && text !== null) n.textContent = text;
    return n;
}

async function api(path, options = {}) {
    const headers = Object.assign({}, options.headers || {}, { "X-API-Key": state.key });
    const res = await fetch(path, Object.assign({}, options, { headers }));
    if (!res.ok) {
        let message = res.status + " " + res.statusText;
        try {
            const body = await res.json();
            if (body && body.error) message = body.error;
        } catch (e) { /* not JSON */ }
        const err = new Error(message);
        err.status = res.status;
        throw err;
    }
    return res;
}

// ---- connection -----------------------------------------------------------

async function connect(key) {
    state.key = key.trim();
    $("who").textContent = "";
    if (!state.key) return;
    try {
        const me = await (await api("/api/me")).json();
        state.role = me.role;
        store(KEY_STORE, state.key);
        $("who").textContent = "Connected as " + me.name + " (" + me.role.toLowerCase() + ")";
        $("admin").hidden = me.role !== "ADMIN";
        await refreshStatus();
        if (me.role === "ADMIN") await refreshDocs();
    } catch (e) {
        state.role = null;
        store(KEY_STORE, "");
        $("admin").hidden = true;
        $("who").textContent = e.status === 401 ? "That key was not accepted." : "Could not connect: " + e.message;
    }
}

async function refreshStatus() {
    const box = $("status");
    try {
        const s = await (await api("/api/stats")).json();
        box.replaceChildren(
            el("span", "chip", s.documents + " documents"),
            el("span", "chip", s.chunks + " chunks"),
            el("span", "chip", s.semanticSearch ? "hybrid search" : "keyword search"),
            el("span", "chip", s.model),
            el("span", "chip " + (s.auditChainIntact ? "ok" : "bad"),
                s.auditChainIntact ? "audit chain intact (" + s.auditEntries + ")" : "AUDIT CHAIN BROKEN"));
    } catch (e) {
        box.replaceChildren();
    }
}

// ---- asking -----------------------------------------------------------------

function renderText(container, text, sourcesList) {
    // "[2]" becomes a button that highlights source 2; everything else stays plain text.
    container.replaceChildren();
    const parts = text.split(/(\[\d{1,2}\])/g);
    for (const part of parts) {
        const m = /^\[(\d{1,2})\]$/.exec(part);
        if (m && sourcesList) {
            const n = m[1];
            const b = el("button", "cite", n);
            b.type = "button";
            b.title = "Show source " + n;
            b.setAttribute("aria-label", "Source " + n);
            b.addEventListener("click", () => highlight(sourcesList, n));
            container.append(b);
        } else if (part) {
            container.append(document.createTextNode(part));
        }
    }
}

function highlight(list, n) {
    for (const li of list.children) {
        const on = li.dataset.n === n;
        li.classList.toggle("lit", on);
        if (on) li.scrollIntoView({ block: "nearest", behavior: "smooth" });
    }
}

function renderAnswer(turn, answer) {
    const a = turn.querySelector(".a");
    const text = turn.querySelector(".text");
    const sources = turn.querySelector(".sources");
    const flags = turn.querySelector(".flags");
    const interpreted = turn.querySelector(".interpreted");

    text.classList.remove("draft");
    a.classList.toggle("refused", !answer.grounded && !answer.flags.includes("injection-blocked"));
    a.classList.toggle("blocked", answer.flags.includes("injection-blocked"));

    sources.replaceChildren();
    for (const c of answer.citations) {
        const li = el("li", "source");
        li.dataset.n = String(c.number);
        li.append(el("b", null, "[" + c.number + "]"), el("span", null, c.document));
        const where = " - " + c.section + (c.page ? ", page " + c.page : "") + " - relevance " + c.score.toFixed(2);
        li.append(el("span", "where", where), el("p", "excerpt", c.excerpt));
        sources.append(li);
    }
    renderText(text, answer.answer, answer.citations.length ? sources : null);

    flags.replaceChildren();
    for (const f of answer.flags) {
        const key = f.startsWith("pii-redacted") ? "pii" : f;
        const label = key === "pii" ? "Personal data masked: " + f.split(":")[1].split("+").join(", ")
            : (FLAG_TEXT[f] || f);
        flags.append(el("span", "flag " + (key === "pii" ? "warn" : (FLAG_STYLE[f] || "")), label));
    }

    if (answer.interpretedAs) {
        interpreted.hidden = false;
        interpreted.textContent = "Understood as: " + answer.interpretedAs;
    }
    turn.querySelector(".meta").textContent = answer.model + " - " + answer.latencyMs + " ms - audit #" + answer.auditSeq;
}

function parseSse(buffer, onEvent) {
    // Returns the unconsumed tail. Events are separated by a blank line.
    const events = buffer.replace(/\r\n/g, "\n").split("\n\n");
    const tail = events.pop();
    for (const block of events) {
        let name = "message";
        const data = [];
        for (const line of block.split("\n")) {
            if (line.startsWith("event:")) name = line.slice(6).trim();
            else if (line.startsWith("data:")) data.push(line.slice(5));
        }
        if (data.length) onEvent(name, data.join("\n"));
    }
    return tail;
}

async function ask(question) {
    if (state.busy || !question.trim()) return;
    if (!state.key) {
        $("who").textContent = "Enter an API key first.";
        $("api-key").focus();
        return;
    }
    state.busy = true;
    $("ask-btn").disabled = true;
    $("empty").hidden = true;

    const turn = $("answer-tpl").content.firstElementChild.cloneNode(true);
    turn.querySelector(".q").textContent = question;
    const text = turn.querySelector(".text");
    text.classList.add("draft");
    text.textContent = "";
    $("thread").append(turn);
    turn.scrollIntoView({ block: "end", behavior: "smooth" });

    let draft = "";
    let final = null;
    try {
        const res = await api("/api/ask/stream", {
            method: "POST",
            headers: { "Content-Type": "application/json", "Accept": "text/event-stream" },
            body: JSON.stringify({ question, history: state.history.slice(-MAX_HISTORY) }),
        });
        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buffer = "";
        for (;;) {
            const { value, done } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });
            buffer = parseSse(buffer, (name, data) => {
                const payload = JSON.parse(data);
                if (name === "delta") {
                    draft += payload.text;
                    text.textContent = draft;
                } else if (name === "answer") {
                    final = payload;
                } else if (name === "error") {
                    throw new Error(payload.error);
                }
            });
        }
        if (!final) throw new Error("The connection closed before the answer was complete.");
        renderAnswer(turn, final);
        // Show and remember the question as the server processed it (personal data masked), so the
        // raw text is never sent again with later follow-ups.
        turn.querySelector(".q").textContent = final.question;
        state.history.push({ question: final.interpretedAs || final.question, answer: final.answer });
        state.history = state.history.slice(-MAX_HISTORY);
        refreshStatus();
    } catch (e) {
        text.classList.remove("draft");
        text.textContent = e.status === 429 ? "Too many questions - please wait a minute." : "Error: " + e.message;
        turn.querySelector(".a").classList.add("blocked");
    } finally {
        state.busy = false;
        $("ask-btn").disabled = false;
        turn.scrollIntoView({ block: "end", behavior: "smooth" });
    }
}

// ---- admin ------------------------------------------------------------------

async function refreshDocs() {
    const list = $("docs");
    try {
        const docs = await (await api("/api/documents")).json();
        list.replaceChildren();
        for (const d of docs) {
            const li = el("li");
            const info = el("div");
            info.append(el("span", "title", d.title),
                el("span", "src", d.id + " - " + d.source + (d.uploadedBy !== "system" ? " by " + d.uploadedBy : "")));
            const del = el("button", null, "Delete");
            del.type = "button";
            del.setAttribute("aria-label", "Delete " + d.title);
            del.addEventListener("click", async () => {
                if (!confirm("Delete \"" + d.title + "\" from the index?")) return;
                try {
                    await api("/api/documents/" + encodeURIComponent(d.id), { method: "DELETE" });
                    await refreshDocs();
                    refreshStatus();
                } catch (e) {
                    alert("Could not delete: " + e.message);
                }
            });
            li.append(info, del);
            list.append(li);
        }
    } catch (e) {
        list.replaceChildren(el("li", null, "Could not load documents: " + e.message));
    }
}

async function upload(ev) {
    ev.preventDefault();
    const msg = $("upload-msg");
    const file = $("upload-file").files[0];
    if (!file) return;
    const form = new FormData();
    form.append("file", file);
    const id = $("upload-id").value.trim();
    if (id) form.append("id", id);
    msg.className = "msg";
    msg.textContent = "Uploading " + file.name + "...";
    try {
        const r = await (await api("/api/documents/upload", { method: "POST", body: form })).json();
        msg.className = "msg ok";
        msg.textContent = "Indexed \"" + r.title + "\" as " + r.id + " (" + r.chunks + " chunks"
            + (r.piiMasked.length ? "; masked " + r.piiMasked.join(", ") : "") + ").";
        $("upload-form").reset();
        await refreshDocs();
        refreshStatus();
    } catch (e) {
        msg.className = "msg bad";
        msg.textContent = e.message;
    }
}

async function verifyAudit() {
    const msg = $("audit-msg");
    msg.className = "msg";
    msg.textContent = "Verifying...";
    try {
        const v = await (await api("/api/audit/verify")).json();
        const head = await (await api("/api/audit/head")).json();
        msg.className = "msg " + (v.brokenAt < 0 ? "ok" : "bad");
        msg.textContent = v.brokenAt < 0
            ? "Intact: " + v.entries + " entries. Head #" + head.seq + " " + head.hash.slice(0, 12) + "..."
            : "BROKEN: " + v.reason;
    } catch (e) {
        msg.className = "msg bad";
        msg.textContent = e.message;
    }
}

// ---- wiring -----------------------------------------------------------------

document.addEventListener("DOMContentLoaded", () => {
    $("key-form").addEventListener("submit", (ev) => {
        ev.preventDefault();
        connect($("api-key").value);
    });
    $("ask-form").addEventListener("submit", (ev) => {
        ev.preventDefault();
        const q = $("question").value;
        $("question").value = "";
        ask(q);
    });
    $("question").addEventListener("keydown", (ev) => {
        if (ev.key === "Enter" && !ev.shiftKey && !ev.isComposing) {
            ev.preventDefault();
            $("ask-form").requestSubmit();
        }
    });
    $("new-chat").addEventListener("click", () => {
        state.history = [];
        $("thread").replaceChildren($("empty"));
        $("empty").hidden = false;
        $("question").focus();
    });
    for (const b of document.querySelectorAll(".example")) {
        b.addEventListener("click", () => ask(b.textContent));
    }
    $("upload-form").addEventListener("submit", upload);
    $("verify-btn").addEventListener("click", verifyAudit);

    const saved = load(KEY_STORE);
    if (saved) {
        $("api-key").value = saved;
        connect(saved);
    }
});
