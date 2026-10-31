import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class XposedChecker {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    public static String check(String email) {
        try {
            String url = "https://api.xposedornot.com/v1/check-email/"
                    + URLEncoder.encode(email, StandardCharsets.UTF_8).replace("+", "%20");

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("user-agent", "digital-privacy-assistant")
                    .GET()
                    .build();

            HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() == 404) {
                return "{\"enabled\":true,\"source\":\"XposedOrNot\",\"breached\":false}";
            }
            if (res.statusCode() == 429) {
                return "{\"enabled\":true,\"error\":\"Too many requests, try again in a minute\"}";
            }
            if (res.statusCode() != 200) {
                return "{\"enabled\":true,\"error\":\"Breach service returned " + res.statusCode() + "\"}";
            }

            // Response looks like: {"breaches":[["Adobe","LinkedIn"]],"email":"..."}
            Matcher block = Pattern.compile("\"breaches\"\\s*:\\s*\\[\\s*\\[(.*?)\\]\\s*\\]", Pattern.DOTALL)
                    .matcher(res.body());
            if (!block.find() || block.group(1).isBlank()) {
                return "{\"enabled\":true,\"source\":\"XposedOrNot\",\"breached\":false}";
            }

            StringBuilder list = new StringBuilder();
            Matcher name = Pattern.compile("\"([^\"]+)\"").matcher(block.group(1));
            while (name.find()) {
                if (list.length() > 0) list.append(",");
                list.append("{\"Name\":\"").append(name.group(1)).append("\"}");
            }
            return "{\"enabled\":true,\"source\":\"XposedOrNot\",\"breached\":true,\"breaches\":[" + list + "]}";

        } catch (Exception e) {
            return "{\"enabled\":true,\"error\":\"Could not reach breach service\"}";
        }
    }
}