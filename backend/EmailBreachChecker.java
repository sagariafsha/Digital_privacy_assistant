import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

public class EmailBreachChecker {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    public static boolean isEnabled() {
        String key = System.getenv("HIBP_API_KEY");
        return key != null && !key.isBlank();
    }

    // Returns a ready-to-send JSON string
    public static String check(String email) {
        // No HIBP key -> use the free XposedOrNot service
        if (!isEnabled()) {
            return XposedChecker.check(email);
        }
        try {
            String url = "https://haveibeenpwned.com/api/v3/breachedaccount/"
                    + URLEncoder.encode(email, StandardCharsets.UTF_8).replace("+", "%20")
                    + "?truncateResponse=true";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("hibp-api-key", System.getenv("HIBP_API_KEY").trim())
                    .header("user-agent", "digital-privacy-assistant")
                    .GET()
                    .build();

            HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());

            switch (res.statusCode()) {
                case 200:
                    return "{\"enabled\":true,\"source\":\"HIBP\",\"breached\":true,\"breaches\":" + res.body() + "}";
                case 404:
                    return "{\"enabled\":true,\"source\":\"HIBP\",\"breached\":false}";
                case 401:
                    // Bad key: fall back to the free service instead of showing an error
                    return XposedChecker.check(email);
                case 429:
                    return "{\"enabled\":true,\"error\":\"Too many requests, try again in a few seconds\"}";
                default:
                    return "{\"enabled\":true,\"error\":\"Breach service returned " + res.statusCode() + "\"}";
            }
        } catch (Exception e) {
            // HIBP unreachable (for example the handshake error): use the free service
            return XposedChecker.check(email);
        }
    }
}