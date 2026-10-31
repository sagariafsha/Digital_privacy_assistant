// Delete help for one account.
// Usage: showDeleteHelp("swiggy.com", "Swiggy", document.getElementById("deleteHelpBox"));
// Needs an empty container, for example: <div id="deleteHelpBox"></div>
const API = "http://localhost:8080/api";

async function showDeleteHelp(domain, service, box) {
  box.textContent = "Loading deletion steps...";
  try {
    const res = await fetch(
      API + "/delete-help?domain=" + encodeURIComponent(domain) +
      "&service=" + encodeURIComponent(service)
    );
    const d = await res.json();
    if (d.error) { box.textContent = d.error; return; }

    box.textContent = "";
    const add = (tag, text, cls) => {
      const el = document.createElement(tag);
      el.textContent = text;
      if (cls) el.className = cls;
      box.appendChild(el);
      return el;
    };
    const link = (label, url) => {
      if (!url || !url.startsWith("https://")) return;
      const a = document.createElement("a");
      a.href = url; a.target = "_blank"; a.rel = "noopener noreferrer";
      a.textContent = label;
      a.style.marginRight = "12px";
      box.appendChild(a);
    };

    add("h3", "How to delete your " + d.service + " account");
    add("p", "Difficulty: " + d.difficulty.toUpperCase(), "diff diff-" + d.difficulty);
    add("p", d.nextStep);
    if (d.notes) add("p", "Steps: " + d.notes);

    link("Open deletion page", d.deletionUrl);
    link("Open website", d.links.website);
    link("Search on Google", d.links.search);
    if (d.source) add("small", "Source: " + d.source);

    // ready-to-send email
    add("h4", "Deletion request email");
    if (d.email.toIsGuess) {
      add("p", "No official contact is listed. " + d.email.to.join(" or ") +
              " are guesses, so check the website's privacy page for the real address.");
    } else {
      add("p", "To: " + d.email.to.join(", "));
    }
    add("p", "Subject: " + d.email.subject);
    const text = add("textarea", "");
    text.value = d.email.body;
    text.rows = 12;
    text.style.width = "100%";

    const copy = add("button", "Copy email");
    copy.onclick = async () => {
      await navigator.clipboard.writeText(d.email.body);
      copy.textContent = "Copied";
    };

    const mailto = add("button", "Open in mail app");
    mailto.onclick = () => {
      window.location.href = "mailto:" + encodeURIComponent(d.email.to[0]) +
        "?subject=" + encodeURIComponent(d.email.subject) +
        "&body=" + encodeURIComponent(text.value);
    };

    const sent = add("button", "I sent the request");
    sent.onclick = async () => {
      const r = await fetch(API + "/deletions", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ domain: d.domain, service: d.service })
      });
      const j = await r.json();
      sent.textContent = j.error ? j.error : "Tracked as " + j.status;
      sent.disabled = true;
    };

    add("h4", "Before you delete");
    const ul = document.createElement("ul");
    d.tips.forEach(t => { const li = document.createElement("li"); li.textContent = t; ul.appendChild(li); });
    box.appendChild(ul);
  } catch (e) {
    box.textContent = "Backend not reachable. Is it running on port 8080?";
  }
}