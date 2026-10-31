import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePartHeader;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Digital Privacy Assistant - backend
 *
 * What it does (all real, no demo data):
 *  1. Connects the user's Google account with OAuth 2.0 (Gmail read-only).
 *  2. Searches the user's own inbox for signup / verification / password-reset / sign-in / receipt emails
 *     and turns the sender domains into "accounts found", with the evidence for each one.
 *  3. Checks breaches: Have I Been Pwned if HIBP_API_KEY is set, otherwise the free XposedOrNot service.
 *     Also has a free password leak check (HIBP Pwned Passwords, no key needed).
 *  4. Saves results and deletion requests in MySQL (only if the database is reachable).
 *
 * Secrets are read from files / environment variables. Nothing secret is hardcoded.
 */
public class Main {

    // ------------------------------------------------------------------ configuration
    static final int PORT = Integer.parseInt(env("PORT", "8080"));
    static final String CLIENT_SECRET_FILE = env("GOOGLE_CLIENT_SECRET_FILE", "client_secret.json");
    static final String TOKENS_DIR = env("TOKENS_DIR", "tokens");
    static final int OAUTH_CALLBACK_PORT = Integer.parseInt(env("OAUTH_CALLBACK_PORT", "8888"));
    static final String DB_URL = env("DB_URL",
            "jdbc:mysql://localhost:3306/privacy_assistant?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
    static final String DB_USER = env("DB_USER", "root");
    static final String DB_PASSWORD = env("DB_PASSWORD", "");
    static final String HIBP_API_KEY = env("HIBP_API_KEY", "");
    static final Set<String> ALLOWED_ORIGINS = Arrays.stream(env("FRONTEND_ORIGINS",
            "http://localhost:5500,http://127.0.0.1:5500,http://localhost:3000,http://localhost:8000,http://127.0.0.1:8000")
            .split(",")).map(String::trim).collect(Collectors.toSet());

    static final String APP_NAME = "Digital Privacy Assistant";
    static final int MAX_MESSAGES = 300;      // most recent matching emails to read
    static final int ACTIVE_DAYS = 180;       // "recent" usage email window
    static final int OLD_DAYS = 365;          // "possibly unused" threshold

    // Gmail search: subjects that normally mean "an account exists for this email address"
    static final String GMAIL_QUERY =
            "subject:(welcome OR \"verify your email\" OR \"verify your account\" OR \"confirm your email\" "
                    + "OR \"confirm your account\" OR \"activate your account\" OR \"verification code\" "
                    + "OR \"reset your password\" OR \"password reset\" OR \"thanks for signing up\" "
                    + "OR \"thank you for signing up\" OR \"new sign-in\" OR \"new login\" "
                    + "OR \"order confirmation\" OR receipt OR invoice) -in:sent";

    // Sender domains that are mail infrastructure or the user's own mailbox, not accounts
    static final Set<String> IGNORED_DOMAINS = new HashSet<>(Arrays.asList(
            "gmail.com", "googlemail.com", "amazonses.com", "sendgrid.net", "mailgun.org",
            "mandrillapp.com", "mcsv.net", "rsgsv.net", "mailchimp.com"));
    static final Set<String> SECOND_LEVELS = new HashSet<>(Arrays.asList("co", "com", "org", "net", "gov", "ac", "edu"));
    static final Set<String> DELETION_STATUSES = new HashSet<>(Arrays.asList("PENDING", "SUBMITTED", "CONFIRMED_BY_USER"));

    static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    static final HttpClient HTTP = HttpClient.newHttpClient();

    static NetHttpTransport transport;
    static FileDataStoreFactory dataStoreFactory;
    static GoogleAuthorizationCodeFlow flow;
    static volatile String cachedEmail;
    static volatile boolean dbReady = false;

    // ------------------------------------------------------------------ small types
    record MailInfo(String domain, String type, String subject, Instant date) {}

    record BreachResult(String status, Map<String, List<String>> byDomain, Map<String, Set<String>> dataByDomain) {
        BreachResult(String status, Map<String, List<String>> byDomain) { this(status, byDomain, Map.of()); }
    }

    static class Account {
        String service, domain, verification, status, statusReason, riskLevel, riskReason, recommendation, evidenceSummary;
        Instant firstSeen, lastSeen, lastActivity;
        int emailsFound;
        List<MailInfo> evidence = new ArrayList<>();
        List<String> breaches = new ArrayList<>();
        Set<String> leakedData = new java.util.LinkedHashSet<>();
        String deleteDifficulty = "unknown";
    }

    static class ApiException extends RuntimeException {
        final int code;
        ApiException(int code, String message) { super(message); this.code = code; }
    }

    interface Endpoint { Object handle(HttpExchange ex) throws Exception; }

    // ------------------------------------------------------------------ startup
    public static void main(String[] args) throws Exception {
        transport = GoogleNetHttpTransport.newTrustedTransport();
        dataStoreFactory = new FileDataStoreFactory(new File(TOKENS_DIR));
        initDatabase();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        route(server, "/api/health", ex -> Map.of("ok", true, "database", dbReady), "GET");
        route(server, "/api/google-status", ex -> googleStatus(), "GET");
        route(server, "/api/google-connect", ex -> googleConnect(), "POST");
        route(server, "/api/google-disconnect", ex -> googleDisconnect(), "POST");
        route(server, "/api/scan", ex -> scan(), "POST");
        route(server, "/api/accounts", ex -> listSavedAccounts(), "GET");
        route(server, "/api/deletions", ex -> "POST".equals(ex.getRequestMethod()) ? createDeletion(ex) : listDeletions(), "GET", "POST");
        route(server, "/api/deletions/status", Main::updateDeletionStatus, "POST");
        route(server, "/api/breach/email", Main::breachEmail, "GET");
        route(server, "/api/breach/password", Main::breachPassword, "POST");
        route(server, "/api/delete-help", Main::deleteHelp, "GET");
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();

        System.out.println("Backend running on http://localhost:" + PORT + "/api");
        System.out.println("Database: " + (dbReady ? "connected" : "NOT connected (scan still works, results are not saved)"));
        System.out.println("Breach check: " + (HIBP_API_KEY.isBlank() ? "on (free: XposedOrNot)" : "on (HIBP)"));
        System.out.println("Allowed frontend origins: " + ALLOWED_ORIGINS);
        Thread loader = new Thread(Main::jdm, "deletion-directory-loader");
        loader.setDaemon(true);
        loader.start();
    }

    // ------------------------------------------------------------------ Google OAuth
    static synchronized GoogleAuthorizationCodeFlow flow() throws IOException {
        if (flow != null) return flow;
        File f = new File(CLIENT_SECRET_FILE);
        if (!f.exists()) {
            throw new ApiException(503, "Google OAuth is not configured: file '" + f.getAbsolutePath()
                    + "' was not found. Download the OAuth client JSON from Google Cloud Console and save it there.");
        }
        GoogleClientSecrets secrets;
        try (FileReader r = new FileReader(f)) {
            secrets = GoogleClientSecrets.load(JSON_FACTORY, r);
        }
        flow = new GoogleAuthorizationCodeFlow.Builder(transport, JSON_FACTORY, secrets,
                Collections.singletonList(GmailScopes.GMAIL_READONLY))
                .setDataStoreFactory(dataStoreFactory)
                .setAccessType("offline")
                .build();
        return flow;
    }

    static Gmail requireGmail() throws IOException {
        Credential credential = flow().loadCredential("user");
        if (credential == null) {
            throw new ApiException(401, "Google account is not connected. Click 'Connect Google' first.");
        }
        return new Gmail.Builder(transport, JSON_FACTORY, credential).setApplicationName(APP_NAME).build();
    }

    static String requireEmail(Gmail gmail) throws IOException {
        String email = cachedEmail;
        if (email == null) {
            email = gmail.users().getProfile("me").execute().getEmailAddress();
            cachedEmail = email;
        }
        return email;
    }

    static Object googleStatus() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            Gmail gmail = requireGmail();
            out.put("connected", true);
            out.put("email", requireEmail(gmail));
        } catch (ApiException e) {
            out.put("connected", false);
            out.put("reason", e.getMessage());
        } catch (Exception e) {
            cachedEmail = null;
            out.put("connected", false);
            out.put("reason", "Could not verify the Google connection: " + e.getMessage());
        }
        out.put("database", dbReady);
        out.put("breachCheck", "ENABLED");
        return out;
    }

    static Object googleConnect() throws Exception {
        Credential credential = new AuthorizationCodeInstalledApp(flow(),
                new LocalServerReceiver.Builder().setPort(OAUTH_CALLBACK_PORT).build()).authorize("user");
        if (credential == null) throw new ApiException(401, "Google authorization was not completed.");
        cachedEmail = null;
        return googleStatus();
    }

    static Object googleDisconnect() throws Exception {
        flow().getCredentialDataStore().delete("user");
        cachedEmail = null;
        return Map.of("connected", false);
    }

    // ------------------------------------------------------------------ account discovery
    static Object scan() throws Exception {
        Gmail gmail = requireGmail();
        String email = requireEmail(gmail);

        // 1. find matching message ids
        List<String> ids = new ArrayList<>();
        String pageToken = null;
        do {
            ListMessagesResponse resp = gmail.users().messages().list("me")
                    .setQ(GMAIL_QUERY).setMaxResults(100L).setPageToken(pageToken).execute();
            if (resp.getMessages() != null) {
                for (Message m : resp.getMessages()) ids.add(m.getId());
            }
            pageToken = resp.getNextPageToken();
        } while (pageToken != null && ids.size() < MAX_MESSAGES);
        if (ids.size() > MAX_MESSAGES) ids = ids.subList(0, MAX_MESSAGES);

        // 2. read only headers (From, Subject) + date of each message
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<MailInfo>> futures = new ArrayList<>();
        for (String id : ids) futures.add(pool.submit(() -> fetchMail(gmail, id)));
        Map<String, List<MailInfo>> byDomain = new HashMap<>();
        int failed = 0;
        for (Future<MailInfo> f : futures) {
            try {
                MailInfo mi = f.get();
                if (mi != null) byDomain.computeIfAbsent(mi.domain(), k -> new ArrayList<>()).add(mi);
            } catch (Exception e) {
                failed++;
            }
        }
        pool.shutdown();

        // 3. breach lookup (HIBP if key is set, otherwise free XposedOrNot), then build accounts
        BreachResult breaches = checkBreaches(email);
        Instant now = Instant.now();
        List<Account> accounts = new ArrayList<>();
        for (Map.Entry<String, List<MailInfo>> en : byDomain.entrySet()) {
            accounts.add(buildAccount(en.getKey(), en.getValue(), breaches, now));
        }
        accounts.sort(Comparator.comparing((Account a) -> a.lastSeen).reversed());

        // 4. save (optional)
        boolean saved = false;
        String saveNote = null;
        try {
            saveAccounts(email, accounts);
            saved = true;
        } catch (ApiException e) {
            saveNote = e.getMessage();
        } catch (SQLException e) {
            saveNote = "Could not save to the database: " + e.getMessage();
        }

        List<Map<String, Object>> accountJson = new ArrayList<>();
        for (Account a : accounts) accountJson.add(accountToJson(a, true));

        List<String> limitations = new ArrayList<>();
        limitations.add("Only accounts that sent signup, verification, password-reset, sign-in or order emails to this Gmail inbox can be found.");
        limitations.add("Accounts created with a different email address are not found. Scan each inbox you use.");
        limitations.add("The newest " + MAX_MESSAGES + " matching emails are read. Older accounts may be missing.");
        limitations.add("Status is ACTIVE only when the service sent a sign-in, order or receipt email in the last " + ACTIVE_DAYS
                + " days. Otherwise it is UNKNOWN. This app never labels an account INACTIVE, because email alone cannot prove that.");
        if (breaches.status().equals("NOT_CONFIGURED")) {
            limitations.add("Breach check is off. Without it, no breach information is shown.");
        } else if (breaches.status().startsWith("ERROR")) {
            limitations.add("Breach check failed: " + breaches.status());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("email", email);
        out.put("scannedAt", now.toString());
        out.put("messagesMatched", ids.size());
        out.put("messagesFailedToRead", failed);
        out.put("source", "Gmail (read-only)");
        out.put("breachCheck", breaches.status());
        out.put("saved", saved);
        out.put("saveNote", saveNote);
        out.put("accountsFound", accounts.size());
        out.put("accounts", accountJson);
        out.put("limitations", limitations);
        if (accounts.isEmpty()) {
            out.put("emptyResultHelp", "No accounts could be identified from this inbox. Possible reasons: the accounts use another email address, "
                    + "the signup emails were deleted, or they use subjects this scan does not look for. Try connecting your other Gmail account.");
        }
        return out;
    }

    static MailInfo fetchMail(Gmail gmail, String id) throws IOException {
        Message m = gmail.users().messages().get("me", id).setFormat("metadata")
                .setMetadataHeaders(Arrays.asList("From", "Subject")).execute();
        String from = null, subject = "";
        if (m.getPayload() != null && m.getPayload().getHeaders() != null) {
            for (MessagePartHeader h : m.getPayload().getHeaders()) {
                if ("From".equalsIgnoreCase(h.getName())) from = h.getValue();
                else if ("Subject".equalsIgnoreCase(h.getName())) subject = h.getValue();
            }
        }
        String domain = senderDomain(from);
        if (domain == null || IGNORED_DOMAINS.contains(domain)) return null;
        String type = classify(subject);
        if (type == null) return null;
        long ms = m.getInternalDate() == null ? 0L : m.getInternalDate();
        return new MailInfo(domain, type, subject, Instant.ofEpochMilli(ms));
    }

    static String classify(String subject) {
        String s = subject == null ? "" : subject.toLowerCase();
        if (s.contains("reset your password") || s.contains("password reset")) return "PASSWORD_RESET";
        if (s.contains("verify") || s.contains("verification code") || s.contains("confirm your") || s.contains("activate your")) return "VERIFY";
        if (s.contains("welcome") || s.contains("signing up") || s.contains("sign up") || s.contains("signed up")) return "SIGNUP";
        if (s.contains("new sign-in") || s.contains("new login") || s.contains("order") || s.contains("receipt") || s.contains("invoice")) return "USAGE";
        return null;
    }

    static final Pattern EMAIL_HOST = Pattern.compile("[\\w.+-]+@([\\w.-]+\\.[A-Za-z]{2,})");

    static String senderDomain(String from) {
        if (from == null) return null;
        Matcher m = EMAIL_HOST.matcher(from);
        if (!m.find()) return null;
        return registrableDomain(m.group(1).toLowerCase());
    }

    static String registrableDomain(String host) {
        String[] p = host.split("\\.");
        if (p.length <= 2) return host;
        String tld = p[p.length - 1], sld = p[p.length - 2];
        int keep = (tld.length() == 2 && SECOND_LEVELS.contains(sld)) ? 3 : 2;
        return String.join(".", Arrays.copyOfRange(p, p.length - keep, p.length));
    }

    static String serviceName(String domain) {
        String first = domain.split("\\.")[0];
        return first.isEmpty() ? domain : Character.toUpperCase(first.charAt(0)) + first.substring(1);
    }

    static Account buildAccount(String domain, List<MailInfo> mails, BreachResult breaches, Instant now) {
        mails.sort(Comparator.comparing(MailInfo::date).reversed());
        Account a = new Account();
        a.domain = domain;
        a.service = serviceName(domain);
        a.emailsFound = mails.size();
        a.deleteDifficulty = deleteDifficultyFor(domain);
        a.lastSeen = mails.get(0).date();
        a.firstSeen = mails.get(mails.size() - 1).date();
        a.evidence = new ArrayList<>(mails.subList(0, Math.min(3, mails.size())));

        Map<String, Integer> counts = new TreeMap<>();
        for (MailInfo m : mails) {
            counts.merge(m.type(), 1, Integer::sum);
            if (m.type().equals("USAGE") && a.lastActivity == null) a.lastActivity = m.date(); // list is newest first
        }
        a.evidenceSummary = counts.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue()).collect(Collectors.joining(", "));

        boolean identityEmail = counts.containsKey("SIGNUP") || counts.containsKey("VERIFY") || counts.containsKey("PASSWORD_RESET");
        a.verification = identityEmail ? "VERIFIED" : "UNVERIFIED";

        // status: only ACTIVE with evidence, otherwise UNKNOWN
        boolean recentUsage = a.lastActivity != null && a.lastActivity.isAfter(now.minus(ACTIVE_DAYS, ChronoUnit.DAYS));
        if (recentUsage) {
            a.status = "ACTIVE";
            a.statusReason = "The service sent a sign-in, order or receipt email on " + a.lastActivity.atZone(ZoneOffset.UTC).toLocalDate() + ".";
        } else {
            a.status = "UNKNOWN";
            a.statusReason = "No sign-in, order or receipt email in the last " + ACTIVE_DAYS
                    + " days. This does not prove the account is inactive.";
        }

        // risk: only with a reason
        List<String> found = breaches.byDomain().get(domain);
        if (found != null && !found.isEmpty()) {
            a.breaches = found;
            Set<String> lk = breaches.dataByDomain().get(domain);
            if (lk != null) a.leakedData.addAll(lk);
            a.riskLevel = "HIGH";
            a.riskReason = "Your email appears in a reported breach linked to this service: " + String.join("; ", found) + " (source: breach database).";
            a.recommendation = "Open the official website, change the password, turn on two-factor authentication, and use a different password on other sites.";
        } else if (!recentUsage && a.lastSeen.isBefore(now.minus(OLD_DAYS, ChronoUnit.DAYS))) {
            a.riskLevel = "MEDIUM";
            a.riskReason = "The newest email we found from this service is over a year old, so the account may be unused. We cannot confirm this.";
            a.recommendation = "Check if you still use it. If not, request account deletion.";
        } else if (breaches.status().equals("CHECKED")) {
            a.riskLevel = "LOW";
            a.riskReason = "No breach found for this service in the breach database, and no unused-account signal. This does not guarantee the account is safe.";
            a.recommendation = "Keep a strong, unique password and two-factor authentication on.";
        } else {
            a.riskLevel = "UNKNOWN";
            a.riskReason = "No risk signal is available yet. Breach check is not connected, so the risk cannot be rated.";
            a.recommendation = "Review the account's security settings on the official website.";
        }
        return a;
    }

    static Map<String, Object> accountToJson(Account a, boolean withEvidence) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("service", a.service);
        m.put("domain", a.domain);
        m.put("source", "Gmail");
        m.put("verification", a.verification);
        m.put("status", a.status);
        m.put("statusReason", a.statusReason);
        m.put("lastActivity", a.lastActivity == null ? null : a.lastActivity.toString());
        m.put("firstEmailSeen", a.firstSeen == null ? null : a.firstSeen.toString());
        m.put("lastEmailSeen", a.lastSeen == null ? null : a.lastSeen.toString());
        m.put("emailsFound", a.emailsFound);
        m.put("evidenceSummary", a.evidenceSummary);
        m.put("riskLevel", a.riskLevel);
        m.put("riskReason", a.riskReason);
        m.put("recommendation", a.recommendation);
        m.put("breaches", a.breaches);
        m.put("leakedData", new ArrayList<>(a.leakedData));
        m.put("deleteDifficulty", a.deleteDifficulty);
        if (withEvidence) {
            List<Map<String, Object>> ev = new ArrayList<>();
            for (MailInfo mi : a.evidence) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("type", mi.type());
                e.put("subject", mi.subject());
                e.put("date", mi.date().toString());
                ev.add(e);
            }
            m.put("evidence", ev);
        }
        return m;
    }

    // ------------------------------------------------------------------ breach check
    // Uses Have I Been Pwned when HIBP_API_KEY is set, otherwise (or if HIBP fails) the free XposedOrNot service.
    static BreachResult checkBreaches(String email) {
        if (HIBP_API_KEY.isBlank()) return checkBreachesFree(email);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://haveibeenpwned.com/api/v3/breachedaccount/"
                            + URLEncoder.encode(email, StandardCharsets.UTF_8) + "?truncateResponse=false"))
                    .header("hibp-api-key", HIBP_API_KEY)
                    .header("user-agent", "DigitalPrivacyAssistant")
                    .timeout(Duration.ofSeconds(15)).GET().build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 404) return new BreachResult("CHECKED", Map.of());
            if (res.statusCode() == 401) return checkBreachesFree(email);   // bad key -> free service
            if (res.statusCode() == 429) return new BreachResult("ERROR: Have I Been Pwned rate limit reached, try again later", Map.of());
            if (res.statusCode() != 200) return new BreachResult("ERROR: Have I Been Pwned returned HTTP " + res.statusCode(), Map.of());

            Map<String, List<String>> byDomain = new HashMap<>();
            Map<String, Set<String>> data = new HashMap<>();
            JsonArray arr = JsonParser.parseString(res.body()).getAsJsonArray();
            for (JsonElement el : arr) {
                JsonObject o = el.getAsJsonObject();
                String domain = o.has("Domain") && !o.get("Domain").isJsonNull() ? o.get("Domain").getAsString().toLowerCase().trim() : "";
                if (domain.isEmpty()) continue;
                String name = o.has("Name") ? o.get("Name").getAsString() : "Unknown breach";
                String date = o.has("BreachDate") && !o.get("BreachDate").isJsonNull() ? o.get("BreachDate").getAsString() : "date unknown";
                byDomain.computeIfAbsent(registrableDomain(domain), k -> new ArrayList<>()).add(name + ", " + date);
                addDataClasses(o.get("DataClasses"),
                        data.computeIfAbsent(registrableDomain(domain), k -> new java.util.LinkedHashSet<>()));
            }
            return new BreachResult("CHECKED", byDomain, data);
        } catch (Exception e) {
            return checkBreachesFree(email);   // HIBP unreachable -> free service
        }
    }

    // Free source: XposedOrNot (no API key)
    static BreachResult checkBreachesFree(String email) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.xposedornot.com/v1/breach-analytics?email="
                            + URLEncoder.encode(email, StandardCharsets.UTF_8)))
                    .header("user-agent", "DigitalPrivacyAssistant")
                    .timeout(Duration.ofSeconds(20)).GET().build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 404) return new BreachResult("CHECKED", Map.of());
            if (res.statusCode() == 429) return new BreachResult("ERROR: free breach service rate limit reached, try again in a minute", Map.of());
            if (res.statusCode() != 200) return new BreachResult("ERROR: free breach service returned HTTP " + res.statusCode(), Map.of());

            Map<String, List<String>> byDomain = new HashMap<>();
            Map<String, Set<String>> data = new HashMap<>();
            collectBreaches(JsonParser.parseString(res.body()), byDomain, data);
            return new BreachResult("CHECKED", byDomain, data);
        } catch (Exception e) {
            return new BreachResult("ERROR: could not reach the breach service", Map.of());
        }
    }

    // Walks the JSON and picks every object that has a "breach" name and a "domain".
    // It also reads which kinds of data were exposed (for example "Passwords;Phone numbers").
    static void collectBreaches(JsonElement el, Map<String, List<String>> out, Map<String, Set<String>> data) {
        if (el == null || el.isJsonNull()) return;
        if (el.isJsonArray()) {
            for (JsonElement e : el.getAsJsonArray()) collectBreaches(e, out, data);
            return;
        }
        if (!el.isJsonObject()) return;
        JsonObject o = el.getAsJsonObject();
        String name = prim(o, "breach");
        String domain = prim(o, "domain").toLowerCase();
        if (!name.isEmpty() && !domain.isEmpty()) {
            String reg = registrableDomain(domain);
            String date = prim(o, "xposed_date");
            out.computeIfAbsent(reg, k -> new ArrayList<>())
                    .add(name + ", " + (date.isEmpty() ? "date unknown" : date));
            Set<String> classes = data.computeIfAbsent(reg, k -> new java.util.LinkedHashSet<>());
            addDataClasses(o.get("xposed_data"), classes);
            addDataClasses(o.get("xposedData"), classes);
            addDataClasses(o.get("exposedData"), classes);
            return;
        }
        for (Map.Entry<String, JsonElement> e : o.entrySet()) collectBreaches(e.getValue(), out, data);
    }

    // Accepts "Passwords;Phone numbers" or ["Passwords","Phone numbers"]
    static void addDataClasses(JsonElement el, Set<String> into) {
        if (el == null || el.isJsonNull()) return;
        if (el.isJsonArray()) {
            for (JsonElement e : el.getAsJsonArray()) addDataClasses(e, into);
            return;
        }
        if (!el.isJsonPrimitive()) return;
        for (String part : el.getAsString().split(";")) {
            String t = part.trim();
            if (!t.isEmpty()) into.add(t);
        }
    }

    static String prim(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString().trim() : "";
    }

    // GET /api/breach/email?email=abc@gmail.com
    static Object breachEmail(HttpExchange ex) {
        String q = ex.getRequestURI().getQuery();
        String email = (q != null && q.startsWith("email=")) ? q.substring(6).trim() : "";
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) throw new ApiException(400, "Enter a valid email address.");

        BreachResult r = checkBreaches(email);
        Map<String, Object> out = new LinkedHashMap<>();
        if (r.status().startsWith("ERROR")) {
            out.put("error", r.status().substring(6).trim());
            return out;
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, List<String>> en : r.byDomain().entrySet()) {
            for (String text : en.getValue()) {
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("Name", text);
                b.put("Domain", en.getKey());
                list.add(b);
            }
        }
        out.put("email", email);
        out.put("source", HIBP_API_KEY.isBlank() ? "XposedOrNot" : "HIBP");
        out.put("breached", !list.isEmpty());
        out.put("breaches", list);
        return out;
    }

    // POST /api/breach/password  (body = the password as plain text)
    static Object breachPassword(HttpExchange ex) throws Exception {
        String password = new String(ex.getRequestBody().readNBytes(1000), StandardCharsets.UTF_8);
        if (password.isEmpty()) throw new ApiException(400, "Password is required.");
        try {
            int count = PwnedPasswordChecker.check(password);
            return Map.of("leaked", count > 0, "count", count);
        } catch (Exception e) {
            throw new ApiException(502, "Could not reach the password breach service.");
        }
    }

    // ------------------------------------------------------------------ database (optional)
    static Connection db() throws SQLException {
        return DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
    }

    static synchronized void initDatabase() {
        try (Connection c = db(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS discovered_accounts ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "user_email VARCHAR(255) NOT NULL,"
                    + "domain VARCHAR(255) NOT NULL,"
                    + "service_name VARCHAR(255) NOT NULL,"
                    + "verification VARCHAR(20) NOT NULL,"
                    + "status VARCHAR(20) NOT NULL,"
                    + "status_reason VARCHAR(500),"
                    + "last_activity DATETIME NULL,"
                    + "first_email_seen DATETIME NULL,"
                    + "last_email_seen DATETIME NULL,"
                    + "emails_found INT NOT NULL,"
                    + "evidence_summary VARCHAR(255),"
                    + "risk_level VARCHAR(20) NOT NULL,"
                    + "risk_reason VARCHAR(600),"
                    + "recommendation VARCHAR(500),"
                    + "scanned_at DATETIME NOT NULL,"
                    + "UNIQUE KEY uq_user_domain (user_email, domain))");
            st.execute("CREATE TABLE IF NOT EXISTS deletion_requests ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "user_email VARCHAR(255) NOT NULL,"
                    + "domain VARCHAR(255) NOT NULL,"
                    + "service_name VARCHAR(255) NOT NULL,"
                    + "status VARCHAR(20) NOT NULL DEFAULT 'PENDING',"
                    + "created_at DATETIME NOT NULL,"
                    + "updated_at DATETIME NOT NULL)");
            try {
                st.execute("ALTER TABLE discovered_accounts ADD COLUMN leaked_data VARCHAR(600) NULL");
            } catch (SQLException ignore) {
                // column already exists
            }
            dbReady = true;
        } catch (SQLException e) {
            dbReady = false;
            System.out.println("[DB] Not available: " + e.getMessage());
            System.out.println("[DB] Create the database (CREATE DATABASE privacy_assistant;) and set DB_URL, DB_USER, DB_PASSWORD.");
        }
    }

    static void requireDb() {
        if (!dbReady) initDatabase();
        if (!dbReady) {
            throw new ApiException(503, "Database is not available. Create the 'privacy_assistant' database, "
                    + "set DB_URL, DB_USER and DB_PASSWORD, then try again.");
        }
    }

    static Timestamp ts(Instant i) { return i == null ? null : Timestamp.from(i); }

    static String iso(Timestamp t) { return t == null ? null : t.toInstant().toString(); }

    static void saveAccounts(String email, List<Account> accounts) throws SQLException {
        requireDb();
        try (Connection c = db()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement del = c.prepareStatement("DELETE FROM discovered_accounts WHERE user_email = ?")) {
                    del.setString(1, email);
                    del.executeUpdate();
                }
                try (PreparedStatement ins = c.prepareStatement("INSERT INTO discovered_accounts (user_email, domain, service_name, "
                        + "verification, status, status_reason, last_activity, first_email_seen, last_email_seen, emails_found, "
                        + "evidence_summary, risk_level, risk_reason, recommendation, scanned_at, leaked_data) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    Timestamp now = Timestamp.from(Instant.now());
                    for (Account a : accounts) {
                        ins.setString(1, email);
                        ins.setString(2, a.domain);
                        ins.setString(3, a.service);
                        ins.setString(4, a.verification);
                        ins.setString(5, a.status);
                        ins.setString(6, a.statusReason);
                        ins.setTimestamp(7, ts(a.lastActivity));
                        ins.setTimestamp(8, ts(a.firstSeen));
                        ins.setTimestamp(9, ts(a.lastSeen));
                        ins.setInt(10, a.emailsFound);
                        ins.setString(11, a.evidenceSummary);
                        ins.setString(12, a.riskLevel);
                        ins.setString(13, a.riskReason);
                        ins.setString(14, a.recommendation);
                        ins.setTimestamp(15, now);
                        ins.setString(16, leakedText(a));
                        ins.addBatch();
                    }
                    ins.executeBatch();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }

    static String leakedText(Account a) {
        String t = String.join("; ", a.leakedData);
        return t.length() > 600 ? t.substring(0, 600) : t;
    }

    static List<String> savedLeaked(ResultSet rs) {
        List<String> out = new ArrayList<>();
        try {
            String t = rs.getString("leaked_data");
            if (t != null) {
                for (String part : t.split(";")) {
                    if (!part.trim().isEmpty()) out.add(part.trim());
                }
            }
        } catch (SQLException e) {
            // column missing: no leaked-data info
        }
        return out;
    }

    static String deleteDifficultyFor(String domain) {
        try {
            JsonObject site = jdm().get(domain);
            if (site == null) return "unknown";
            String d = prim(site, "difficulty").toLowerCase();
            return d.isEmpty() ? "unknown" : d;
        } catch (Exception e) {
            return "unknown";
        }
    }

    static Object listSavedAccounts() throws Exception {
        String email = requireEmail(requireGmail());
        requireDb();
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection c = db(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM discovered_accounts WHERE user_email = ? ORDER BY last_email_seen DESC")) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("service", rs.getString("service_name"));
                    m.put("domain", rs.getString("domain"));
                    m.put("source", "Gmail");
                    m.put("verification", rs.getString("verification"));
                    m.put("status", rs.getString("status"));
                    m.put("statusReason", rs.getString("status_reason"));
                    m.put("lastActivity", iso(rs.getTimestamp("last_activity")));
                    m.put("firstEmailSeen", iso(rs.getTimestamp("first_email_seen")));
                    m.put("lastEmailSeen", iso(rs.getTimestamp("last_email_seen")));
                    m.put("emailsFound", rs.getInt("emails_found"));
                    m.put("evidenceSummary", rs.getString("evidence_summary"));
                    m.put("riskLevel", rs.getString("risk_level"));
                    m.put("riskReason", rs.getString("risk_reason"));
                    m.put("recommendation", rs.getString("recommendation"));
                    m.put("scannedAt", iso(rs.getTimestamp("scanned_at")));
                    m.put("leakedData", savedLeaked(rs));
                    m.put("deleteDifficulty", deleteDifficultyFor(rs.getString("domain")));
                    rows.add(m);
                }
            }
        }
        return Map.of("email", email, "accounts", rows);
    }

    // ------------------------------------------------------------------ delete help
    // Real data: JustDeleteMe is an open-source directory of account-deletion pages (about 2,600 services).
    // It is downloaded once, cached in memory and saved to a local file, so it also works offline later.
    static final String JDM_URL = "https://raw.githubusercontent.com/jdm-contrib/jdm/master/_data/sites.json";
    static final File JDM_CACHE = new File(env("JDM_CACHE_FILE", "jdm-sites.json"));
    static volatile Map<String, JsonObject> jdmIndex;
    static volatile long jdmLastTry = 0;

    static synchronized Map<String, JsonObject> jdm() {
        if (jdmIndex != null) return jdmIndex;
        if (jdmLastTry != 0 && System.currentTimeMillis() - jdmLastTry < 60_000) return Map.of();
        jdmLastTry = System.currentTimeMillis();
        String json = null;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(JDM_URL))
                    .header("user-agent", "DigitalPrivacyAssistant")
                    .timeout(Duration.ofSeconds(25)).GET().build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 200) {
                json = res.body();
                java.nio.file.Files.writeString(JDM_CACHE.toPath(), json, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            // fall back to the saved copy below
        }
        if (json == null && JDM_CACHE.exists()) {
            try {
                json = java.nio.file.Files.readString(JDM_CACHE.toPath(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                // no copy available
            }
        }
        Map<String, JsonObject> idx = new HashMap<>();
        if (json != null) {
            try {
                for (JsonElement el : JsonParser.parseString(json).getAsJsonArray()) {
                    JsonObject o = el.getAsJsonObject();
                    if (!o.has("domains") || !o.get("domains").isJsonArray()) continue;
                    for (JsonElement d : o.getAsJsonArray("domains")) {
                        if (!d.isJsonPrimitive()) continue;
                        String full = d.getAsString().toLowerCase().trim();
                        idx.putIfAbsent(full, o);
                        idx.putIfAbsent(registrableDomain(full), o);
                    }
                }
            } catch (Exception e) {
                idx.clear();
            }
        }
        if (!idx.isEmpty()) jdmIndex = idx;   // if empty, it is tried again on the next request
        return idx;
    }

    static String nextStep(String difficulty) {
        switch (difficulty) {
            case "easy":
                return "Easy: open the deletion page, sign in, and follow the steps.";
            case "medium":
                return "Medium: it takes a few steps. Follow the notes carefully.";
            case "hard":
                return "Hard: you usually have to contact support. Send the email below.";
            case "limited":
                return "Limited: the service deletes only part of your data. Remove what you can in settings and send the email below.";
            case "impossible":
                return "The service says it does not offer deletion. Send the request email below, keep a copy, "
                        + "remove your phone number and address from the profile, and change the password.";
            default:
                return "Open the website, sign in, and look in Settings > Account or Privacy for 'Delete account'. "
                        + "If you cannot find it, send the email below.";
        }
    }

    // GET /api/delete-help?domain=swiggy.com&service=Swiggy
    static Object deleteHelp(HttpExchange ex) throws Exception {
        String userEmail = requireEmail(requireGmail());
        String domain = "", service = "";
        String raw = ex.getRequestURI().getRawQuery();
        if (raw != null) {
            for (String part : raw.split("&")) {
                if (part.startsWith("domain=")) {
                    domain = java.net.URLDecoder.decode(part.substring(7), StandardCharsets.UTF_8).trim().toLowerCase();
                } else if (part.startsWith("service=")) {
                    service = java.net.URLDecoder.decode(part.substring(8), StandardCharsets.UTF_8).trim();
                }
            }
        }
        if (!domain.matches("^[a-z0-9]([a-z0-9.-]{1,251})[a-z0-9]$")) throw new ApiException(400, "Invalid domain.");
        if (service.isEmpty() || service.length() > 100) service = serviceName(domain);

        JsonObject site = jdm().get(domain);
        if (site == null) site = jdm().get(registrableDomain(domain));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("domain", domain);
        out.put("service", service);
        out.put("found", site != null);

        String difficulty = "unknown";
        String directoryEmail = "";
        if (site != null) {
            difficulty = prim(site, "difficulty").toLowerCase();
            if (difficulty.isEmpty()) difficulty = "unknown";
            directoryEmail = prim(site, "email");
            out.put("deletionUrl", prim(site, "url"));
            out.put("notes", prim(site, "notes"));
            out.put("source", "JustDeleteMe (open-source deletion directory)");
        } else {
            out.put("deletionUrl", null);
            out.put("notes", jdm().isEmpty()
                    ? "The deletion directory could not be loaded (no internet?). Use the links below."
                    : "This service is not in the deletion directory. Use the links below.");
            out.put("source", null);
        }
        out.put("difficulty", difficulty);
        out.put("nextStep", nextStep(difficulty));

        Map<String, String> links = new LinkedHashMap<>();
        links.put("website", "https://" + domain);
        links.put("search", "https://www.google.com/search?q="
                + URLEncoder.encode("how to delete " + service + " account", StandardCharsets.UTF_8));
        out.put("links", links);

        // ready-to-send request email
        Map<String, Object> mail = new LinkedHashMap<>();
        boolean known = !directoryEmail.isEmpty();
        mail.put("to", known ? List.of(directoryEmail) : List.of("privacy@" + domain, "support@" + domain));
        mail.put("toIsGuess", !known);
        mail.put("subject", "Request to delete my account and personal data");
        mail.put("body", "Hello " + service + " team,\n\n"
                + "I want to permanently delete my account and erase my personal data "
                + "(name, phone number, address, order and usage history) that you hold.\n\n"
                + "Account email: " + userEmail + "\n\n"
                + "I make this request under the right to erasure in applicable data protection law, including "
                + "India's Digital Personal Data Protection Act, 2023, and the GDPR where it applies. "
                + "Please delete my account and personal data, stop processing it, and confirm by reply once it is done. "
                + "If you need anything to verify that this account is mine, tell me and I will send it.\n\n"
                + "Thank you,\n[Your full name]");
        out.put("email", mail);
        out.put("tips", List.of(
                "Download or save anything you still need (orders, invoices) before deleting.",
                "Never share your password with anyone who asks for it by email.",
                "After you send the request, mark it as SUBMITTED in this app to keep track."));
        return out;
    }

    // ------------------------------------------------------------------ deletion tracking
    static Object listDeletions() throws Exception {
        String email = requireEmail(requireGmail());
        requireDb();
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection c = db(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM deletion_requests WHERE user_email = ? ORDER BY updated_at DESC")) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(deletionRow(rs));
            }
        }
        return Map.of("requests", rows);
    }

    static Map<String, Object> deletionRow(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getLong("id"));
        m.put("domain", rs.getString("domain"));
        m.put("service", rs.getString("service_name"));
        m.put("status", rs.getString("status"));
        m.put("createdAt", iso(rs.getTimestamp("created_at")));
        m.put("updatedAt", iso(rs.getTimestamp("updated_at")));
        return m;
    }

    static Object createDeletion(HttpExchange ex) throws Exception {
        String email = requireEmail(requireGmail());
        requireDb();
        JsonObject body = readJson(ex);
        String domain = str(body, "domain").toLowerCase();
        String service = str(body, "service");
        if (!domain.matches("^[a-z0-9]([a-z0-9.-]{1,251})[a-z0-9]$")) throw new ApiException(400, "Invalid domain.");
        if (service.isEmpty() || service.length() > 100) throw new ApiException(400, "Service name is required (max 100 characters).");

        try (Connection c = db()) {
            try (PreparedStatement q = c.prepareStatement(
                    "SELECT * FROM deletion_requests WHERE user_email = ? AND domain = ? ORDER BY id DESC LIMIT 1")) {
                q.setString(1, email);
                q.setString(2, domain);
                try (ResultSet rs = q.executeQuery()) {
                    if (rs.next()) return deletionRow(rs); // already tracked
                }
            }
            Timestamp now = Timestamp.from(Instant.now());
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO deletion_requests (user_email, domain, service_name, status, created_at, updated_at) VALUES (?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ins.setString(1, email);
                ins.setString(2, domain);
                ins.setString(3, service);
                ins.setString(4, "PENDING");
                ins.setTimestamp(5, now);
                ins.setTimestamp(6, now);
                ins.executeUpdate();
                try (ResultSet keys = ins.getGeneratedKeys()) {
                    keys.next();
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", keys.getLong(1));
                    m.put("domain", domain);
                    m.put("service", service);
                    m.put("status", "PENDING");
                    m.put("createdAt", now.toInstant().toString());
                    m.put("updatedAt", now.toInstant().toString());
                    return m;
                }
            }
        }
    }

    static Object updateDeletionStatus(HttpExchange ex) throws Exception {
        String email = requireEmail(requireGmail());
        requireDb();
        JsonObject body = readJson(ex);
        String status = str(body, "status").toUpperCase();
        long id;
        try {
            id = Long.parseLong(str(body, "id"));
        } catch (NumberFormatException e) {
            throw new ApiException(400, "Invalid request id.");
        }
        if (!DELETION_STATUSES.contains(status)) {
            throw new ApiException(400, "Status must be one of: PENDING, SUBMITTED, CONFIRMED_BY_USER.");
        }
        try (Connection c = db(); PreparedStatement ps = c.prepareStatement(
                "UPDATE deletion_requests SET status = ?, updated_at = ? WHERE id = ? AND user_email = ?")) {
            ps.setString(1, status);
            ps.setTimestamp(2, Timestamp.from(Instant.now()));
            ps.setLong(3, id);
            ps.setString(4, email);
            if (ps.executeUpdate() == 0) throw new ApiException(404, "Deletion request not found.");
        }
        return Map.of("id", id, "status", status,
                "note", "CONFIRMED_BY_USER means you told us the service confirmed the deletion. This app cannot verify it by itself.");
    }

    // ------------------------------------------------------------------ http helpers
    static void route(HttpServer server, String path, Endpoint endpoint, String... methods) {
        Set<String> allowed = new HashSet<>(Arrays.asList(methods));
        server.createContext(path, ex -> {
            try {
                if ("OPTIONS".equals(ex.getRequestMethod())) {
                    send(ex, 204, null);
                    return;
                }
                String origin = ex.getRequestHeaders().getFirst("Origin");
                if (origin != null && !ALLOWED_ORIGINS.contains(origin)) {
                    throw new ApiException(403, "This website origin is not allowed to call the backend.");
                }
                if (!allowed.contains(ex.getRequestMethod())) throw new ApiException(405, "Method not allowed.");
                send(ex, 200, endpoint.handle(ex));
            } catch (ApiException e) {
                send(ex, e.code, error(e.getMessage()));
            } catch (GoogleJsonResponseException e) {
                String msg = e.getDetails() != null ? e.getDetails().getMessage() : e.getMessage();
                send(ex, 502, error("Google API error: " + msg));
            } catch (Exception e) {
                e.printStackTrace();
                send(ex, 500, error("Unexpected server error: " + e.getMessage()));
            }
        });
    }

    static void send(HttpExchange ex, int code, Object body) throws IOException {
        var h = ex.getResponseHeaders();
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin != null && ALLOWED_ORIGINS.contains(origin)) {
            h.set("Access-Control-Allow-Origin", origin);
            h.set("Vary", "Origin");
        }
        h.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        h.set("Access-Control-Allow-Headers", "Content-Type");
        if (body == null) {
            ex.sendResponseHeaders(code, -1);
            ex.close();
            return;
        }
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        h.set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    static Map<String, Object> error(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", message);
        return m;
    }

    static JsonObject readJson(HttpExchange ex) throws IOException {
        String text = new String(ex.getRequestBody().readNBytes(10_000), StandardCharsets.UTF_8);
        try {
            return JsonParser.parseString(text).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new ApiException(400, "Request body must be a JSON object.");
        }
    }

    static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString().trim() : "";
    }

    static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}