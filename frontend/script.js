/* Digital Privacy Assistant - frontend
   Shows only what the backend returns. No demo data, no invented scores. */

const API = "http://localhost:8080/api";
const STATUS_LABELS = { PENDING: "Pending", SUBMITTED: "Submitted", CONFIRMED_BY_USER: "Confirmed by you" };

let connectedEmail = "";
let accounts = [];
let scanMeta = null;
let deletions = [];
let filter = "ALL";
let followUpDays = 30;
let showAllPriority = false;
const deleteCache = {};     // domain -> deletion help from the backend
const deletePending = {};   // domain -> true while loading

const $ = id => document.getElementById(id);
const esc = s => String(s ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#039;" }[c]));
const fmt = iso => iso ? new Date(iso).toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric" }) : "Not available";

function setStatus(text, type) {
    const el = $("statusMsg");
    el.textContent = text || "";
    el.style.color = type === "error" ? "#b91c1c" : type === "success" ? "#047857" : "#374151";
}

async function api(path, method = "GET", body) {
    let res;
    try {
        res = await fetch(API + path, {
            method,
            headers: body ? { "Content-Type": "application/json" } : {},
            body: body ? JSON.stringify(body) : undefined
        });
    } catch (e) {
        throw new Error("Cannot reach the backend at localhost:8080. Start it with: mvn compile exec:java");
    }
    let data = {};
    try { data = await res.json(); } catch (_) {}
    if (!res.ok) throw new Error(data.error || "Request failed (" + res.status + ")");
    return data;
}

/* ---------- start ---------- */
document.addEventListener("DOMContentLoaded", async () => {
    $("emailInput").addEventListener("keydown", e => { if (e.key === "Enter") findAccounts(); });
    ensureSections();
    renderFollowUps();
    await refreshStatus();
    if (connectedEmail) await loadSaved();
    render();
});

async function refreshStatus() {
    const badge = $("backendModeText");
    try {
        const s = await api("/google-status");
        connectedEmail = s.connected ? s.email : "";
        badge.textContent = s.connected ? "Google connected" : "Google not connected";
        if (connectedEmail) $("emailInput").value = connectedEmail;
        $("connectBtn").style.display = s.connected ? "none" : "";
        $("disconnectBtn").style.display = s.connected ? "" : "none";
        if (!s.connected && s.reason) setStatus(s.reason, "info");
    } catch (e) {
        connectedEmail = "";
        badge.textContent = "Backend offline";
        setStatus(e.message, "error");
    }
}

async function loadSaved() {
    try { accounts = (await api("/accounts")).accounts; } catch (_) { /* database may be off */ }
    try { deletions = (await api("/deletions")).requests; } catch (_) {}
    fillDeleteSelect();
    renderDeletions();
}

/* ---------- Google connection ---------- */
async function connectGoogle() {
    $("connectBtn").disabled = true;
    setStatus("Waiting for Google sign-in. Finish it in the Google window, then come back here.", "info");
    try {
        const s = await api("/google-connect", "POST");
        if (!s.connected) throw new Error(s.reason || "Google connection failed.");
        await refreshStatus();
        await loadSaved();
        render();
        setStatus("Connected as " + s.email + ". Click Find Accounts.", "success");
    } catch (e) {
        setStatus(e.message, "error");
    } finally {
        $("connectBtn").disabled = false;
    }
}

async function disconnectGoogle() {
    try {
        await api("/google-disconnect", "POST");
        accounts = []; scanMeta = null; deletions = [];
        await refreshStatus();
        fillDeleteSelect(); renderDeletions(); render();
        setStatus("Google account disconnected.", "success");
    } catch (e) {
        setStatus(e.message, "error");
    }
}

/* ---------- find accounts ---------- */
async function findAccounts() {
    const typed = $("emailInput").value.trim().toLowerCase();
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(typed)) { setStatus("Please enter a valid email address.", "error"); return; }
    if (!connectedEmail) { setStatus("Connect your Google account first. The scan can only read the inbox you connect.", "error"); return; }
    if (typed !== connectedEmail.toLowerCase()) {
        setStatus("This email does not match the connected Google account (" + connectedEmail + "). The scan can only read the connected inbox.", "error");
        return;
    }
    $("findBtn").disabled = true;
    setStatus("Scanning your Gmail headers. This can take up to a minute...", "info");
    try {
        const r = await api("/scan", "POST");
        accounts = r.accounts;
        scanMeta = r;
        fillDeleteSelect();
        render();
        if (!r.accounts.length) {
            setStatus(r.emptyResultHelp, "info");
        } else if (r.saved) {
            setStatus("Found " + r.accounts.length + " account(s) with email evidence. Results saved.", "success");
        } else {
            setStatus("Found " + r.accounts.length + " account(s). Not saved: " + r.saveNote, "info");
        }
        $("dashboard").scrollIntoView({ behavior: "smooth" });
    } catch (e) {
        setStatus(e.message, "error");
    } finally {
        $("findBtn").disabled = false;
    }
}

/* ---------- results ---------- */
function setFilter(f) { filter = f; render(); }

function matches(a) {
    if (filter === "HIGH") return a.riskLevel === "HIGH";
    if (filter === "ACTIVE") return a.status === "ACTIVE";
    if (filter === "UNKNOWN") return a.status === "UNKNOWN";
    if (filter === "VERIFIED") return a.verification === "VERIFIED";
    return true;
}

function render() {
    const high = accounts.filter(a => a.riskLevel === "HIGH").length;
    const active = accounts.filter(a => a.status === "ACTIVE").length;
    const unknown = accounts.filter(a => a.status === "UNKNOWN").length;
    $("totalAccounts").textContent = accounts.length;
    $("verifiedAccounts").textContent = accounts.filter(a => a.verification === "VERIFIED").length;
    $("activeAccounts").textContent = active;
    $("highRiskAccounts").textContent = high;
    $("countAll").textContent = accounts.length;
    $("countHigh").textContent = high;
    $("countActive").textContent = active;
    $("countUnknown").textContent = unknown;
    ["ALL", "HIGH", "ACTIVE", "UNKNOWN"].forEach(f => $("pill_" + f).classList.toggle("active", f === filter));

    renderScanInfo();
    renderInsights();

    const list = $("accountList");
    const q = $("accountSearch").value.trim().toLowerCase();
    let html = "";
    accounts.forEach((a, i) => {
        if (!matches(a)) return;
        if (q && !(a.service.toLowerCase().includes(q) || a.domain.toLowerCase().includes(q))) return;
        const cls = { HIGH: "high", MEDIUM: "medium", LOW: "low" }[a.riskLevel];
        const tag = cls
            ? `<span class="tag-risk ${cls}">${esc(a.riskLevel)} RISK</span>`
            : `<span class="tag-risk" style="background:#e5e7eb;color:#374151;">RISK UNKNOWN</span>`;
        html += `
        <div class="account-card">
            <div class="account-card-header">
                <div class="account-brand">
                    <div class="brand-icon">${esc(a.service.charAt(0))}</div>
                    <div class="brand-text"><h4>${esc(a.service)}</h4><span>${esc(a.domain)}</span></div>
                </div>
                ${tag}
            </div>
            <div class="account-body-stats">
                <div><strong>Found in:</strong> ${esc(a.source)} (${esc(a.verification.toLowerCase())})</div>
                <div><strong>Status:</strong> ${a.status === "ACTIVE" ? "Active" : "Unknown"}</div>
                <div><strong>Last activity:</strong> ${esc(fmt(a.lastActivity))}</div>
                <div><strong>Risk reason:</strong> ${esc(a.riskReason)}</div>
                <div><strong>Next step:</strong> ${esc(a.recommendation)}</div>
            </div>
            <div class="account-actions">
                <button type="button" class="btn btn-secondary btn-sm" onclick="showEvidence(${i})">Evidence</button>
                <button type="button" class="btn btn-secondary btn-sm" onclick="prepareDeletion(${i})">Deletion help</button>
            </div>
        </div>`;
    });
    if (!html) {
        html = `<div class="empty-box"><h4>${accounts.length ? "No accounts match this filter" : "No scan results yet"}</h4>
                <p>${accounts.length ? "Choose All or clear the search." : "Connect Google and click Find Accounts."}</p></div>`;
    }
    list.innerHTML = html;
}

function renderScanInfo() {
    const el = $("scanInfo");
    if (!scanMeta) { el.innerHTML = ""; return; }
    const breach = scanMeta.breachCheck === "CHECKED" ? "Done"
        : scanMeta.breachCheck === "NOT_CONFIGURED" ? "Off" : esc(scanMeta.breachCheck);
    el.innerHTML = `
        <p><strong>Source:</strong> ${esc(scanMeta.source)} &nbsp; <strong>Scanned:</strong> ${esc(fmt(scanMeta.scannedAt))}
        &nbsp; <strong>Emails checked:</strong> ${scanMeta.messagesMatched} &nbsp; <strong>Breach check:</strong> ${breach}</p>
        <p style="margin-top:8px;"><strong>Limits of this scan:</strong></p>
        <ul style="margin-left:20px;">${scanMeta.limitations.map(l => `<li>${esc(l)}</li>`).join("")}</ul>`;
}

function showEvidence(i) {
    const a = accounts[i];
    $("modalTitle").textContent = a.service + " - evidence";
    $("modalSub").textContent = a.domain;
    const ev = (a.evidence || []).map(e =>
        `<li>${esc(e.type.replace("_", " ").toLowerCase())}: "${esc(e.subject)}" (${esc(fmt(e.date))})</li>`).join("");
    const br = (a.breaches || []).map(b => `<li>${esc(b)}</li>`).join("");
    $("modalBody").innerHTML = `
        <p><strong>Status reason:</strong> ${esc(a.statusReason)}</p>
        <p><strong>Emails found:</strong> ${a.emailsFound} (${esc(a.evidenceSummary)})</p>
        <p><strong>First email seen:</strong> ${esc(fmt(a.firstEmailSeen))} &nbsp; <strong>Latest:</strong> ${esc(fmt(a.lastEmailSeen))}</p>
        ${ev ? `<p style="margin-top:10px;"><strong>Newest matching emails:</strong></p><ul style="margin-left:20px;">${ev}</ul>` : ""}
        ${br ? `<p style="margin-top:10px;"><strong>Reported breaches:</strong></p><ul style="margin-left:20px;">${br}</ul>` : ""}
        <p style="margin-top:12px; font-size:13px;">Verified means a signup, verification or password-reset email exists in your inbox.
        It does not prove the account is still open.</p>`;
    $("accountModal").style.display = "flex";
}

function closeModal() { $("accountModal").style.display = "none"; }

/* =====================================================================
   NEW: insights (privacy score, what was leaked, clean-up priority)
   Everything is computed from the scan result. Nothing is invented.
   ===================================================================== */
const CARD_STYLE = "background:rgba(127,127,127,0.08); border:1px solid rgba(127,127,127,0.3); border-radius:10px; "
    + "padding:14px 16px; margin:12px 0; box-sizing:border-box; width:100%; grid-column:1 / -1;";

const SENSITIVE_KINDS = ["password", "phone", "address", "dob", "financial", "govid"];
const KIND_LABELS = {
    password: "Passwords", phone: "Phone numbers", address: "Home addresses",
    dob: "Dates of birth", financial: "Financial details", govid: "Government IDs",
    email: "Email addresses", name: "Names", ip: "IP addresses", other: "Other data"
};
const KIND_ADVICE = {
    password: "Change this password now, and on every site where you reused it. Turn on two-factor authentication.",
    phone: "Expect spam calls and SMS scams. Never share OTPs or bank details on a call.",
    address: "Your home address may be known. Be careful with fake courier or delivery messages.",
    dob: "A date of birth helps identity thieves. Watch for loan or SIM requests you did not make.",
    financial: "Tell your bank, check your statements, and consider blocking the card.",
    govid: "Government ID numbers cannot be changed easily. Watch for misuse and report it quickly."
};

function dataKind(cls) {
    const c = String(cls).toLowerCase();
    if (c.includes("password")) return "password";
    if (c.includes("phone") || c.includes("mobile")) return "phone";
    if (c.includes("ip address")) return "ip";
    if (c.includes("email")) return "email";
    if (c.includes("address")) return "address";
    if (c.includes("birth")) return "dob";
    if (c.includes("credit") || c.includes("card") || c.includes("bank") || c.includes("payment") || c.includes("financial")) return "financial";
    if (c.includes("government") || c.includes("passport") || c.includes("aadhaar") || c.includes("social security") || c.includes("national id")) return "govid";
    if (c.includes("name")) return "name";
    return "other";
}

function sensitiveKinds(a) {
    const kinds = new Set((a.leakedData || []).map(dataKind));
    return SENSITIVE_KINDS.filter(k => kinds.has(k));
}

function ensureSections() {
    if (!$("insights")) {
        const box = document.createElement("div");
        box.id = "insights";
        box.style.cssText = "width:100%; grid-column:1 / -1; box-sizing:border-box;";
        const anchor = $("scanInfo");
        if (anchor) anchor.insertAdjacentElement("afterend", box); else document.body.appendChild(box);
    }
    if (!$("toolsSection")) {
        const box = document.createElement("div");
        box.id = "toolsSection";
        box.style.cssText = "width:100%; grid-column:1 / -1; box-sizing:border-box; margin-top:16px;";
        box.innerHTML = `
        <div style="${CARD_STYLE}">
            <h3>Password leak check</h3>
            <p style="font-size:13px; margin:6px 0 10px;">Find out if a password has appeared in known data breaches.
            Only the first 5 characters of its SHA-1 hash are sent to the breach service, never the password itself.
            Your backend does not save or log it.</p>
            <input id="pwInput" type="password" placeholder="Type a password to check" autocomplete="off"
                   style="padding:8px; min-width:240px; max-width:100%;">
            <label style="margin-left:8px; font-size:13px;">
                <input type="checkbox" onchange="$('pwInput').type = this.checked ? 'text' : 'password'"> Show
            </label>
            <button type="button" class="btn btn-secondary btn-sm" onclick="checkPassword()" style="margin-left:8px;">Check password</button>
            <p id="pwResult" style="margin-top:10px; font-size:14px;"></p>
        </div>
        <div style="${CARD_STYLE}">
            <h3>Deletion follow-ups</h3>
            <p style="font-size:13px; margin:6px 0 10px;">Services often ignore the first deletion request.
            After you mark a request as Submitted, this list tells you when to follow up.
            <label style="margin-left:8px;">Remind me after
                <select onchange="setFollowUpDays(this.value)">
                    <option value="7">7 days</option><option value="15">15 days</option><option value="30" selected>30 days</option>
                </select></label></p>
            <div id="followUps"></div>
        </div>`;
        const anchor = $("deleteSection");
        if (anchor) anchor.insertAdjacentElement("afterend", box); else document.body.appendChild(box);
    }
}

function computeScore(list) {
    const parts = [
        { label: "Accounts found in a breach", count: list.filter(a => a.riskLevel === "HIGH").length, each: 10, cap: 40 },
        { label: "Accounts where passwords, phone, address or similar data leaked", count: list.filter(a => sensitiveKinds(a).length > 0).length, each: 5, cap: 20 },
        { label: "Accounts that look unused for over a year", count: list.filter(a => a.riskLevel === "MEDIUM").length, each: 3, cap: 15 },
        { label: "Accounts with no recent sign-in or order email", count: list.filter(a => a.status === "UNKNOWN").length, each: 1, cap: 10 }
    ];
    parts.forEach(p => { p.points = Math.min(p.count * p.each, p.cap); });
    const score = Math.max(0, 100 - parts.reduce((sum, p) => sum + p.points, 0));
    return { score, parts };
}

function priorityList(list) {
    const items = [];
    list.forEach((a, i) => {
        const reasons = [];
        let pts = 0;
        if (a.riskLevel === "HIGH") { pts += 50; reasons.push("Found in a breach"); }
        else if (a.riskLevel === "MEDIUM") { pts += 20; reasons.push("Looks unused for over a year"); }
        const sk = sensitiveKinds(a);
        if (sk.length) { pts += 20; reasons.push("Leaked: " + sk.map(k => KIND_LABELS[k]).join(", ")); }
        const idle = a.status !== "ACTIVE";
        if (idle) { pts += 15; reasons.push("No recent activity"); }
        if (idle) {
            const ease = { easy: 15, medium: 8, limited: 3 }[a.deleteDifficulty] || 0;
            pts += ease;
            if (a.deleteDifficulty === "easy") reasons.push("Easy to delete");
            else if (a.deleteDifficulty === "medium") reasons.push("Medium effort to delete");
            else if (a.deleteDifficulty === "hard" || a.deleteDifficulty === "impossible") reasons.push("Hard to delete: needs a support email");
        }
        if (!idle && a.riskLevel !== "HIGH") return;   // in use and not breached: leave it alone
        if (pts === 0) return;
        items.push({ index: i, account: a, pts, reasons, action: idle ? "Delete" : "Secure it (change password, turn on 2FA)" });
    });
    return items.sort((x, y) => y.pts - x.pts);
}

function chip(text, bad) {
    return `<span style="display:inline-block; margin:2px 4px 2px 0; padding:2px 8px; border-radius:12px; font-size:12px; `
        + `background:${bad ? "#fee2e2" : "rgba(127,127,127,0.2)"}; color:${bad ? "#991b1b" : "inherit"};">${esc(text)}</span>`;
}

function toggleAllPriority() { showAllPriority = !showAllPriority; renderInsights(); }

function renderInsights() {
    ensureSections();
    const box = $("insights");
    if (!accounts.length) { box.style.display = "none"; return; }
    box.style.display = "";

    /* 1. privacy score */
    const { score, parts } = computeScore(accounts);
    const band = score >= 80 ? ["Good", "#047857"] : score >= 50 ? ["Needs attention", "#b45309"] : ["At risk", "#b91c1c"];
    const scoreHtml = `
    <div style="${CARD_STYLE}">
        <h3>Your privacy score</h3>
        <div style="display:flex; align-items:center; gap:16px; flex-wrap:wrap; margin:8px 0;">
            <div style="font-size:42px; font-weight:700; color:${band[1]};">${score}<span style="font-size:18px;">/100</span></div>
            <div style="flex:1; min-width:180px;">
                <div style="font-weight:600; color:${band[1]};">${band[0]}</div>
                <div style="height:10px; background:rgba(127,127,127,0.25); border-radius:6px; overflow:hidden; margin-top:6px;">
                    <div style="height:100%; width:${score}%; background:${band[1]};"></div>
                </div>
            </div>
        </div>
        <table style="width:100%; font-size:13px; border-collapse:collapse;">
            <tr style="text-align:left;"><th>What lowers the score</th><th>Count</th><th>Each</th><th>Points lost</th></tr>
            ${parts.map(p => `<tr><td>${esc(p.label)}</td><td>${p.count}</td><td>-${p.each}</td><td>-${p.points} (max -${p.cap})</td></tr>`).join("")}
        </table>
        <p style="font-size:12px; margin-top:8px;">The score starts at 100 and uses only the numbers from your scan.
        It is a simple guide, not a guarantee. Accounts the scan could not find are not counted.</p>
    </div>`;

    /* 2. what was leaked */
    const withData = accounts.filter(a => (a.leakedData || []).length > 0);
    const highCount = accounts.filter(a => a.riskLevel === "HIGH").length;
    let leakedHtml;
    if (withData.length) {
        const kindCount = {};
        withData.forEach(a => sensitiveKinds(a).forEach(k => { kindCount[k] = (kindCount[k] || 0) + 1; }));
        const advice = Object.keys(kindCount).map(k =>
            `<li><strong>${esc(KIND_LABELS[k])}</strong> (${kindCount[k]} account${kindCount[k] > 1 ? "s" : ""}): ${esc(KIND_ADVICE[k])}</li>`).join("");
        leakedHtml = `
        <div style="${CARD_STYLE}">
            <h3>What was leaked</h3>
            ${withData.map(a => `
                <div style="margin:8px 0;">
                    <strong>${esc(a.service)}</strong> <span style="font-size:12px;">(${esc(a.domain)})</span><br>
                    ${(a.leakedData || []).map(c => chip(c, SENSITIVE_KINDS.includes(dataKind(c)))).join("")}
                </div>`).join("")}
            ${advice ? `<p style="margin-top:10px;"><strong>What to do:</strong></p><ul style="margin-left:20px; font-size:13.5px;">${advice}</ul>` : ""}
        </div>`;
    } else {
        leakedHtml = `
        <div style="${CARD_STYLE}">
            <h3>What was leaked</h3>
            <p style="font-size:13.5px;">${highCount
                ? "Your email appears in breaches linked to " + highCount + " account(s), but the breach source did not say which data was exposed."
                : "No breach linked to your accounts was found, so there is no leaked data to show."}</p>
        </div>`;
    }

    /* 3. clean-up priority */
    const prio = priorityList(accounts);
    const shown = showAllPriority ? prio : prio.slice(0, 5);
    const prioHtml = `
    <div style="${CARD_STYLE}">
        <h3>Clean-up priority: what to do first</h3>
        <p style="font-size:12.5px; margin:4px 0 8px;">Ranked by breach risk, leaked personal data, no recent activity, and how easy the account is to delete.
        Accounts you still use and that were not breached are left out.</p>
        ${prio.length ? shown.map((it, n) => `
            <div style="display:flex; justify-content:space-between; gap:10px; flex-wrap:wrap; padding:8px 0; border-top:1px solid rgba(127,127,127,0.25);">
                <div style="flex:1; min-width:220px;">
                    <strong>${n + 1}. ${esc(it.account.service)}</strong>
                    <span style="font-size:12px;">(${esc(it.account.domain)})</span>
                    <div style="font-size:13px; margin-top:2px;">${esc(it.action)}</div>
                    <div>${it.reasons.map(r => chip(r, r.startsWith("Found") || r.startsWith("Leaked"))).join("")}</div>
                </div>
                <div><button type="button" class="btn btn-secondary btn-sm" onclick="prepareDeletion(${it.index})">Deletion help</button></div>
            </div>`).join("")
            : `<p style="font-size:13.5px;">Nothing needs cleaning up right now.</p>`}
        ${prio.length > 5 ? `<button type="button" class="btn btn-secondary btn-sm" onclick="toggleAllPriority()" style="margin-top:8px;">${showAllPriority ? "Show top 5" : "Show all " + prio.length}</button>` : ""}
    </div>`;

    box.innerHTML = scoreHtml + leakedHtml + prioHtml;
}

/* ---------- NEW: password leak check ---------- */
async function checkPassword() {
    const input = $("pwInput");
    const out = $("pwResult");
    const pw = input.value;
    if (!pw) { out.textContent = "Type a password first."; return; }
    out.textContent = "Checking...";
    try {
        let res;
        try {
            res = await fetch(API + "/breach/password", { method: "POST", body: pw });
        } catch (e) {
            throw new Error("Cannot reach the backend at localhost:8080.");
        }
        const d = await res.json().catch(() => ({}));
        if (!res.ok || d.error) throw new Error(d.error || "Request failed (" + res.status + ")");
        if (d.leaked) {
            out.style.color = "#b91c1c";
            out.textContent = "This password was seen " + Number(d.count).toLocaleString("en-IN")
                + " times in data breaches. Do not use it anywhere. Choose a new, unique one.";
        } else {
            out.style.color = "#047857";
            out.textContent = "Not found in known breaches. This does not mean it is strong: use a long, unique password.";
        }
    } catch (e) {
        out.style.color = "#b91c1c";
        out.textContent = e.message;
    } finally {
        input.value = "";
    }
}

/* ---------- NEW: deletion follow-up reminders ---------- */
function daysSince(iso) { return Math.floor((Date.now() - new Date(iso).getTime()) / 86400000); }

function setFollowUpDays(v) { followUpDays = Number(v) || 30; renderFollowUps(); }

function followUpState(d) {
    if (d.status === "CONFIRMED_BY_USER") return { kind: "done", text: "Done. You confirmed the deletion." };
    if (d.status === "SUBMITTED") {
        const days = daysSince(d.updatedAt);
        if (days >= followUpDays) return { kind: "due", text: "No confirmation after " + days + " days. Time to follow up." };
        return { kind: "wait", text: "Waiting: " + days + " day(s) since you submitted. Follow up in " + (followUpDays - days) + " day(s)." };
    }
    const days = daysSince(d.createdAt);
    return days >= 7
        ? { kind: "unsent", text: "Not sent yet (" + days + " days). Send the request letter, then mark it Submitted." }
        : { kind: "wait", text: "Pending: send your request letter, then mark it Submitted." };
}

function renderFollowUps() {
    const el = $("followUps");
    if (!el) return;
    if (!deletions.length) { el.innerHTML = `<p style="font-size:13px;">No deletion requests tracked yet. Use "Deletion help" on an account to start one.</p>`; return; }
    const colors = { due: "#b91c1c", unsent: "#b45309", wait: "inherit", done: "#047857" };
    const sorted = [...deletions].sort((a, b) => (followUpState(a).kind === "due" ? 0 : 1) - (followUpState(b).kind === "due" ? 0 : 1));
    const dueCount = deletions.filter(d => followUpState(d).kind === "due").length;
    el.innerHTML = (dueCount ? `<p style="color:#b91c1c; font-weight:600; margin-bottom:6px;">${dueCount} request(s) need a follow-up.</p>` : "")
        + sorted.map(d => {
            const st = followUpState(d);
            return `<div style="padding:8px 0; border-top:1px solid rgba(127,127,127,0.25);">
                <strong>${esc(d.service)}</strong> <span style="font-size:12px;">(${esc(d.domain)})</span>
                <div style="font-size:13px; color:${colors[st.kind]};">${esc(st.text)}</div>
                ${st.kind === "due" ? `<button type="button" class="btn btn-secondary btn-sm" style="margin-top:6px;" onclick="copyFollowUp(${Number(d.id)})">Copy follow-up letter</button>` : ""}
            </div>`;
        }).join("");
}

function copyFollowUp(id) {
    const d = deletions.find(x => Number(x.id) === Number(id));
    if (!d) return;
    const nameEl = $("userNameInput");
    const name = (nameEl && nameEl.value.trim()) || "[Your full name]";
    const email = connectedEmail || "[Your registered email]";
    const text =
`Subject: Follow-up: request to delete my account and personal data

Hello ${d.service} team,

On ${fmt(d.updatedAt)} I asked you to delete my account registered with ${email}, and to erase my personal data. I have not received a written confirmation yet.

Please confirm in writing within 7 days that the deletion is complete, or tell me what is still pending and why. If I do not hear back, I may report this to the relevant data protection authority.

Thank you.
${name}
${email}`;
    navigator.clipboard.writeText(text).then(
        () => setStatus("Follow-up letter for " + d.service + " copied.", "success"),
        () => setStatus("Could not copy the letter.", "error"));
}

/* ---------- deletion help ---------- */
function fillDeleteSelect() {
    const sel = $("delAccount");
    const old = sel.value;
    sel.innerHTML = `<option value="">Select an account</option>` +
        accounts.map((a, i) => `<option value="${i}">${esc(a.service)} (${esc(a.domain)})</option>`).join("");
    sel.value = old;
}

function prepareDeletion(i) {
    $("delAccount").value = String(i);
    generateLetter();
    $("deleteSection").scrollIntoView({ behavior: "smooth" });
}

function selectedAccount() {
    const v = $("delAccount").value;
    return v === "" ? null : accounts[Number(v)];
}

// Loads the real deletion steps for one account from the backend (once per domain)
function loadDeleteHelp(a) {
    if (deleteCache[a.domain] || deletePending[a.domain]) return;
    deletePending[a.domain] = true;
    api("/delete-help?domain=" + encodeURIComponent(a.domain) + "&service=" + encodeURIComponent(a.service))
        .then(d => { deleteCache[a.domain] = d; })
        .catch(() => { deleteCache[a.domain] = { error: true }; })
        .finally(() => {
            delete deletePending[a.domain];
            const cur = selectedAccount();
            if (cur && cur.domain === a.domain) generateLetter();
        });
}

// Shows difficulty, steps and notes above the letter (the box is created here, no HTML change needed)
function renderDeleteInfo(a, d) {
    let box = $("delInfo");
    if (!box) {
        box = document.createElement("div");
        box.id = "delInfo";
        box.style.cssText = "margin:10px 0; padding:10px 12px; border:1px solid #e5e7eb; border-radius:8px; font-size:13.5px;";
        const t = $("letterText");
        t.parentNode.insertBefore(box, t);
    }
    if (!a) { box.style.display = "none"; return; }
    box.style.display = "";
    if (!d) { box.textContent = "Loading deletion steps..."; return; }
    if (d.error) { box.textContent = "Could not load deletion steps. Use the search link and the letter below."; return; }
    const colors = { easy: "#047857", medium: "#b45309", limited: "#b45309", hard: "#b91c1c", impossible: "#7f1d1d" };
    box.innerHTML = `
        <p><strong>Deletion difficulty:</strong>
           <span style="color:${colors[d.difficulty] || "#374151"}; font-weight:600;">${esc(String(d.difficulty).toUpperCase())}</span></p>
        <p style="margin-top:6px;">${esc(d.nextStep)}</p>
        ${d.notes ? `<p style="margin-top:6px;"><strong>Steps:</strong> ${esc(d.notes)}</p>` : ""}
        ${d.email && d.email.toIsGuess ? `<p style="margin-top:6px;">No official contact email is listed for this service. Check its website's privacy page for the real address before sending the letter.</p>` : ""}
        ${d.source ? `<p style="margin-top:6px; font-size:12px;">Source: ${esc(d.source)}</p>` : ""}`;
}

function generateLetter() {
    const a = selectedAccount();
    const link = $("delLink");
    if (!a) { $("letterText").value = ""; link.style.display = "none"; renderDeleteInfo(null); return; }
    loadDeleteHelp(a);
    const d = deleteCache[a.domain];
    const name = $("userNameInput").value.trim() || "[Your full name]";
    const email = connectedEmail || "[Your registered email]";
    const date = new Date().toLocaleDateString("en-IN", { day: "numeric", month: "long", year: "numeric" });
    const toLine = d && d.email && !d.email.toIsGuess ? d.email.to[0] : "Privacy / Support Team, " + a.service;
    $("letterText").value =
`Date: ${date}
To: ${toLine}
Subject: Request to delete my account and personal data

Hello,

I request the deletion of the account registered with the email address ${email}, and the erasure of my personal data linked to it.

I make this request under the data protection laws that apply to me, including India's Digital Personal Data Protection Act, 2023 and, where it applies, Article 17 of the GDPR.

Please:
1. Delete my account and profile.
2. Delete or anonymise the personal data you hold about me, unless the law requires you to keep it.
3. Tell me in writing which data, if any, you must keep and why.
4. Confirm in writing when the deletion is complete.

Thank you.
${name}
${email}`;
    link.href = d && d.deletionUrl && d.deletionUrl.startsWith("https://")
        ? d.deletionUrl
        : "https://www.google.com/search?q=" + encodeURIComponent("how to delete " + a.service + " account official " + a.domain);
    link.style.display = "";
    renderDeleteInfo(a, d);
}

function copyLetter() {
    const t = $("letterText").value;
    if (!t) { setStatus("Choose an account to create the letter first.", "error"); return; }
    navigator.clipboard.writeText(t).then(
        () => setStatus("Letter copied.", "success"),
        () => setStatus("Could not copy. Select the text and copy it manually.", "error"));
}

function downloadLetter() {
    const a = selectedAccount();
    if (!a) { setStatus("Choose an account to create the letter first.", "error"); return; }
    const url = URL.createObjectURL(new Blob([$("letterText").value], { type: "text/plain" }));
    const link = document.createElement("a");
    link.href = url;
    link.download = "deletion_request_" + a.domain + ".txt";
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
}

async function trackDeletion() {
    const a = selectedAccount();
    if (!a) { setStatus("Choose an account first.", "error"); return; }
    try {
        await api("/deletions", "POST", { domain: a.domain, service: a.service });
        deletions = (await api("/deletions")).requests;
        renderDeletions();
        setStatus("Tracking started for " + a.service + ". Status: Pending.", "success");
    } catch (e) {
        setStatus(e.message, "error");
    }
}

async function updateDeletion(id, status) {
    try {
        await api("/deletions/status", "POST", { id, status });
        deletions = (await api("/deletions")).requests;
        renderDeletions();
        setStatus("Request updated to " + STATUS_LABELS[status] + ".", "success");
    } catch (e) {
        setStatus(e.message, "error");
    }
}

function renderDeletions() {
    const el = $("delList");
    if (!deletions.length) {
        el.innerHTML = `<p style="font-size:13px;">No requests tracked yet.</p>`;
    } else {
        el.innerHTML = deletions.map(d => `
        <div style="display:flex; justify-content:space-between; align-items:center; gap:10px; flex-wrap:wrap; padding:10px 0; border-bottom:1px solid #e5e7eb;">
            <span><strong>${esc(d.service)}</strong> (${esc(d.domain)}), started ${esc(fmt(d.createdAt))}</span>
            <select onchange="updateDeletion(${Number(d.id)}, this.value)" aria-label="Request status">
                ${Object.keys(STATUS_LABELS).map(s => `<option value="${s}" ${s === d.status ? "selected" : ""}>${STATUS_LABELS[s]}</option>`).join("")}
            </select>
        </div>`).join("") +
        `<p style="font-size:12.5px; margin-top:8px;">"Confirmed by you" means you received confirmation from the service. This app cannot check it.</p>`;
    }
    renderFollowUps();
}