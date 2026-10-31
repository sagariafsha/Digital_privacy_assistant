import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class PwnedPasswordChecker {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    // Returns how many times the password was seen in breaches (0 = not found)
    public static int check(String password) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] digest = md.digest(password.getBytes(StandardCharsets.UTF_8));

        StringBuilder sb = new StringBuilder();
        for (byte b : digest) sb.append(String.format("%02X", b));
        String hash = sb.toString();

        String prefix = hash.substring(0, 5);
        String suffix = hash.substring(5);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.pwnedpasswords.com/range/" + prefix))
                .header("Add-Padding", "true")
                .GET()
                .build();

        HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new RuntimeException("HIBP error: " + res.statusCode());
        }

        for (String line : res.body().split("\r?\n")) {
            String[] parts = line.split(":");
            if (parts.length == 2 && parts[0].equalsIgnoreCase(suffix)) {
                return Integer.parseInt(parts[1].trim());
            }
        }
        return 0;
    }
}