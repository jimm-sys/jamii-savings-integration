import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

public class SmokeTest {
    static HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static String base; static boolean external = false;
    static String acct = "/accounts", cust = "/customers", loans = "/loans";
    static List<String[]> headers = new ArrayList<>();
    static int passed = 0, failed = 0;

    public static void main(String[] a) throws Exception {
        base = a[0].replaceAll("/+$", "");
        for (int i = 1; i < a.length; i++) {
            switch (a[i]) {
                case "--external": external = true; break;
                case "--header": String[] kv = a[++i].split(":", 2); headers.add(new String[]{kv[0].trim(), kv[1].trim()}); break;
                case "--accounts-path": acct = a[++i]; break;
                case "--customers-path": cust = a[++i]; break;
                case "--loans-path": loans = a[++i]; break;
            }
        }
        check("accounts: ACTIVE account -> 200", get(acct + "/1000000001/balance"), 200, "\"status\":\"ACTIVE\"", "\"currency\":\"KES\"", "\"requestId\"", "\"timestamp\"");
        check("accounts: DORMANT account -> 200", get(acct + "/1000000003/balance"), 200, "\"status\":\"DORMANT\"");
        check("accounts: unknown account -> 404", get(acct + "/9999999999/balance"), 404, "\"code\":\"ACCOUNT_NOT_FOUND\"", "\"requestId\"");
        check("accounts: malformed number -> 400", get(acct + "/12ab/balance"), 400, "\"code\":\"INVALID_REQUEST\"");
        HttpResponse<String> inj = get(acct + "/1%27%20OR%20%271%27=%271/balance");
        assertTrue("accounts: SQL-injection attempt rejected (400 or 404)", inj != null && (inj.statusCode() == 400 || inj.statusCode() == 404));
        check("customers: malformed id -> 400", get(cust + "/abc"), 400, "\"code\":\"INVALID_REQUEST\"");
        HttpResponse<String> r = get(cust + "/abc");
        assertTrue("customers: correlation id header present", r != null && r.headers().firstValue("X-Correlation-ID").isPresent());
        assertTrue("customers: no backend headers leaked", r != null && r.headers().firstValue("Server").isEmpty() && r.headers().firstValue("X-Powered-By").isEmpty());
        check("loans: invalid body -> 400", post(loans + "/eligibility", "{}"), 400, "\"code\":\"INVALID_REQUEST\"");
        check("loans: not JSON -> 400", post(loans + "/eligibility", "hello"), 400, "\"code\":\"INVALID_REQUEST\"");
        if (external) {
            check("customers: existing customer -> 200", get(cust + "/1"), 200, "\"id\"");
            check("customers: unknown customer -> 404", get(cust + "/999"), 404, "\"code\":\"CUSTOMER_NOT_FOUND\"");
            check("loans: eligible -> 200", post(loans + "/eligibility", "{\"applicantId\":\"A123\",\"monthlyIncome\":80000,\"requestedAmount\":500000}"), 200, "\"decision\":\"ELIGIBLE\"", "\"loanToIncomeMultiple\":6");
            check("loans: not eligible -> 200", post(loans + "/eligibility", "{\"applicantId\":\"A123\",\"monthlyIncome\":80000,\"requestedAmount\":960000}"), 200, "\"decision\":\"NOT_ELIGIBLE\"");
            HttpResponse<String> f = post(loans + "/eligibility", "{\"applicantId\":\"A123\",\"monthlyIncome\":0,\"requestedAmount\":500000}");
            check("loans: SOAP fault -> 502", f, 502, "\"code\":\"ELIGIBILITY_SERVICE_ERROR\"");
            assertTrue("loans: SOAP fault text not leaked", f != null && !f.body().contains("Divide") && !f.body().contains("Exception"));
        } else System.out.println("SKIP  external-backend checks (run with --external)");
        System.out.printf("%n%d passed, %d failed%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    static HttpResponse<String> get(String path) throws Exception { return send(HttpRequest.newBuilder(URI.create(base + path)).GET()); }
    static HttpResponse<String> post(String path, String body) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)));
    }
    static HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
        for (String[] h : headers) b.header(h[0], h[1]);
        try { return http.send(b.timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString()); }
        catch (Exception e) { System.out.println("      request failed: " + e); return null; }
    }
    static void check(String name, HttpResponse<String> r, int status, String... contains) {
        if (r == null) { fail(name, "no response"); return; }
        List<String> problems = new ArrayList<>();
        if (r.statusCode() != status) problems.add("status " + r.statusCode() + " (expected " + status + ")");
        for (String c : contains) if (!r.body().replace(" ", "").contains(c.replace(" ", ""))) problems.add("body missing " + c);
        if (problems.isEmpty()) { passed++; System.out.println("PASS  " + name); }
        else fail(name, String.join("; ", problems) + "  body=" + r.body().substring(0, Math.min(120, r.body().length())));
    }
    static void assertTrue(String name, boolean ok) { if (ok) { passed++; System.out.println("PASS  " + name); } else fail(name, "assertion failed"); }
    static void fail(String name, String why) { failed++; System.out.println("FAIL  " + name + " -> " + why); }
}
