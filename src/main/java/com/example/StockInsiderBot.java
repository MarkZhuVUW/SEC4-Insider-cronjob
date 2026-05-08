package com.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.io.entity.EntityUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class StockInsiderBot {

    private static final String SEC_BASE = "https://www.sec.gov/Archives/";
    private static final String TICKER_URL = "https://www.sec.gov/include/ticker.txt";
    private static final String DEFAULT_SEC_USER_AGENT = "SEC4-Insider-Bot AdminContact@example.com";
    private static final String DEFAULT_SEC_CONTACT_EMAIL = "contact@example.com";
    private static final long DEFAULT_MINIMUM_USD = 500_000L;
    private static final int DEFAULT_MAX_LOOKBACK_DAYS = 1;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);

    public static void main(String[] args) {
        try {
            Map<String, String> options = parseOptions(args);
            String tickersArg = firstNonBlank(options.get("tickers"), System.getenv("TICKERS"), options.get("positional"));
            long minimumUsd = parseLong(firstNonBlank(options.get("threshold"), System.getenv("THRESHOLD_USD")), DEFAULT_MINIMUM_USD);
            int maxLookbackDays = parseInt(firstNonBlank(options.get("lookback"), System.getenv("LOOKBACK_DAYS")), DEFAULT_MAX_LOOKBACK_DAYS);

            logDebug("Initializing Insider Bot...");
            logDebug("Target Tickers: " + tickersArg);
            logDebug("Minimum Threshold: $" + minimumUsd);

            if (tickersArg == null || tickersArg.isBlank()) {
                System.err.println("Error: No tickers provided.");
                return;
            }

            String[] tickerArray = parseTickers(tickersArg);
            List<String> targetTickerList = Arrays.asList(tickerArray);

            Map<String, String> tickerToCik = downloadTickerMapping();
            Map<String, String> cikToTicker = new HashMap<>();
            Set<String> ciks = new HashSet<>();

            for (String t : tickerArray) {
                String cik = findCikForTicker(t, tickerToCik);
                if (cik != null) {
                    ciks.add(cik);
                    cikToTicker.put(cik, t);
                }
            }

            LocalDate searchDate = LocalDate.now().minusDays(1);
            List<String> form4Urls = new ArrayList<>();
            MasterIndex masterIndex = findMasterIndex(searchDate, maxLookbackDays);

            if (masterIndex != null) {
                form4Urls.addAll(parseMasterIdx(masterIndex.content, ciks));
            }

            // Fallback to Browse API if master index is thin (weekends/holidays)
            if (form4Urls.isEmpty()) {
                logDebug("No filings in daily index, trying Browse API fallback...");
                form4Urls.addAll(fetchForm4UrlsFromEdgarBrowse(ciks, maxLookbackDays));
            }

            Map<String, List<AlertEntry>> resultsMap = new LinkedHashMap<>();
            // Initialize the map with all target tickers to ensure they appear in notification
            targetTickerList.forEach(t -> resultsMap.put(t, new ArrayList<>()));

            for (String url : form4Urls) {
                try {
                    String xml = downloadText(url);
                    parseAndFilterForm4(xml, minimumUsd, targetTickerList, resultsMap);
                } catch (Exception ex) {
                    logDebug("Failed to process filing at " + url + " : " + ex.getMessage());
                }
            }

            String dateStr = (masterIndex != null) ? masterIndex.indexDate : searchDate.toString();
            String message = buildGroupedNotification(resultsMap, targetTickerList, dateStr);

            boolean notified = sendNotification(message);
            logDebug("Process complete. Discord notification sent: " + notified);
            if (!notified) System.out.println(message);

        } catch (Exception e) {
            logDebug("FATAL ERROR: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void parseAndFilterForm4(String xml, long threshold, List<String> targets, Map<String, List<AlertEntry>> results) throws Exception {
        XmlMapper mapper = new XmlMapper();
        JsonNode root = mapper.readTree(extractXmlPayload(xml));

        JsonNode issuer = root.path("issuer");
        String filingTicker = issuer.path("issuerTradingSymbol").asText("").toUpperCase();

        // CRITICAL FIX: Only accept if the filing is for one of our target companies (Self-Transaction)
        if (!targets.contains(filingTicker)) {
            logDebug("Skipping filing: Issuer " + filingTicker + " is not in target list (likely an institutional holding move).");
            return;
        }

        JsonNode reportingOwner = root.path("reportingOwner");
        String ownerName = reportingOwner.path("reportingOwnerId").path("rptOwnerName").asText("Unknown");
        String position = extractPosition(reportingOwner);

        JsonNode nonDeriv = root.path("nonDerivativeTable").isMissingNode() 
            ? root.path("ownershipDocument").path("nonDerivativeTable") : root.path("nonDerivativeTable");

        if (nonDeriv.isMissingNode()) return;

        JsonNode transNode = nonDeriv.path("nonDerivativeTransaction");
        List<JsonNode> transactions = new ArrayList<>();
        if (transNode.isArray()) transNode.forEach(transactions::add);
        else if (transNode.isObject()) transactions.add(transNode);

        for (JsonNode t : transactions) {
            String code = t.path("transactionCoding").path("transactionCode").asText();
            if (!"P".equals(code) && !"S".equals(code)) continue;

            long shares = extractLong(t, "transactionAmounts.transactionShares");
            double price = extractDouble(t, "transactionAmounts.transactionPricePerShare");
            double amount = shares * price;

            if (amount >= threshold) {
                String type = "P".equals(code) ? "BUY" : "SELL";
                boolean isPlan = "true".equalsIgnoreCase(t.path("transactionCoding").path("is10b51Transaction").asText());
                results.get(filingTicker).add(new AlertEntry(ownerName, position, type, shares, price, amount, isPlan));
                logDebug("Match found: " + filingTicker + " | " + ownerName + " | $" + String.format("%.2f", amount));
            }
        }
    }

    private static String extractPosition(JsonNode reportingOwner) {
        JsonNode rel = reportingOwner.path("reportingOwnerRelationship");
        if (rel.isMissingNode()) return "Insider";

        // Priority 1: Official Title
        String title = rel.path("officerTitle").asText("");
        if (title.isEmpty()) title = rel.path("otherText").asText("");
        if (!title.isEmpty()) return title;

        // Priority 2: Director Status
        if ("1".equals(rel.path("isDirector").asText()) || "true".equalsIgnoreCase(rel.path("isDirector").asText())) return "Director";

        // Priority 3: Officer Status (Generic)
        if ("1".equals(rel.path("isOfficer").asText()) || "true".equalsIgnoreCase(rel.path("isOfficer").asText())) return "Officer";

        // Priority 4: Large Shareholder
        if ("1".equals(rel.path("isTenPercentOwner").asText()) || "true".equalsIgnoreCase(rel.path("isTenPercentOwner").asText())) return "10% Owner";

        return "Insider";
    }

    private static String buildGroupedNotification(Map<String, List<AlertEntry>> results, List<String> allTargets, String date) {
        StringBuilder sb = new StringBuilder();
        sb.append("🔔 **Insider Alerts (").append(date).append(")**\n\n");

        for (String ticker : allTargets) {
            sb.append("▶ **").append(ticker).append("**\n");
            List<AlertEntry> alerts = results.get(ticker);
            if (alerts == null || alerts.isEmpty()) {
                sb.append("  └ _[No significant transactions found]_\n");
            } else {
                for (AlertEntry e : alerts) {
                    String action = e.type + (e.is10b51 ? " [10b5-1]" : "");
                    sb.append(String.format("  └ %s (%s) | **%s** | %,d @ $%.2f = **$%,.0f**\n",
                            e.ownerName, e.position, action, e.shares, e.price, e.amount));
                }
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    // --- Helper Classes ---
    private static class AlertEntry {
        final String ownerName, position, type;
        final long shares;
        final double price, amount;
        final boolean is10b51;

        AlertEntry(String o, String p, String t, long s, double pr, double a, boolean i) {
            this.ownerName = o; this.position = p; this.type = t;
            this.shares = s; this.price = pr; this.amount = a; this.is10b51 = i;
        }
    }

    // --- Existing SEC Utils (Simplified/Cleaned) ---
    private static void logDebug(String msg) {
        System.out.println("DEBUG: " + msg);
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (String arg : args) {
            if (arg.startsWith("--")) {
                String[] parts = arg.substring(2).split("=", 2);
                if (parts.length == 2) options.put(parts[0].toLowerCase(), parts[1]);
            } else if (!options.containsKey("positional")) {
                options.put("positional", arg);
            }
        }
        return options;
    }

    private static String firstNonBlank(String... v) {
        for (String s : v) if (s != null && !s.isBlank()) return s;
        return null;
    }

    private static long parseLong(String v, long f) {
        try { return Long.parseLong(v.trim()); } catch (Exception e) { return f; }
    }

    private static int parseInt(String v, int f) {
        try { return Integer.parseInt(v.trim()); } catch (Exception e) { return f; }
    }

    private static String[] parseTickers(String arg) {
        return Arrays.stream(arg.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(String::toUpperCase).toArray(String[]::new);
    }

    private static Map<String, String> downloadTickerMapping() {
        Map<String, String> map = new HashMap<>();
        try {
            String content = downloadText(TICKER_URL);
            for (String line : content.split("\\R")) {
                String[] parts = line.trim().split("\\t");
                if (parts.length == 2) map.put(parts[0].toUpperCase(), parts[1]);
            }
        } catch (Exception e) { logDebug("Mapping download failed: " + e.getMessage()); }
        return map;
    }

    private static String findCikForTicker(String t, Map<String, String> map) {
        String res = map.get(t);
        if (res == null) res = map.get(t.replace("-", "."));
        if (res == null) res = map.get(t.replace(".", "-"));
        return res;
    }

    private static MasterIndex findMasterIndex(LocalDate start, int lookback) {
        for (int i = 0; i < lookback; i++) {
            LocalDate d = start.minusDays(i);
            String dateStr = d.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String url = String.format("%sedgar/daily-index/%d/QTR%d/master.%s.idx", 
                    SEC_BASE, d.getYear(), (d.getMonthValue()-1)/3+1, dateStr);
            try {
                String content = downloadText(url);
                if (content != null) return new MasterIndex(dateStr, content);
            } catch (Exception e) { logDebug("Index not found for " + dateStr); }
        }
        return null;
    }

    private static String downloadText(String url) throws Exception {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpGet get = new HttpGet(url);
            get.setHeader("User-Agent", firstNonBlank(System.getenv("SEC_USER_AGENT"), DEFAULT_SEC_USER_AGENT));
            get.setHeader("From", firstNonBlank(System.getenv("SEC_CONTACT_EMAIL"), DEFAULT_SEC_CONTACT_EMAIL));
            try (ClassicHttpResponse resp = client.execute(get)) {
                if (resp.getCode() == HttpStatus.SC_OK) return EntityUtils.toString(resp.getEntity());
                throw new Exception("HTTP " + resp.getCode());
            }
        }
    }

    private static List<String> parseMasterIdx(String content, Set<String> ciks) {
        Set<String> urls = new LinkedHashSet<>();
        for (String line : content.split("\\R")) {
            String[] parts = line.split("\\|");
            if (parts.length >= 5 && parts[2].startsWith("4") && ciks.contains(parts[0].trim())) {
                urls.add(SEC_BASE + parts[4].trim());
            }
        }
        return new ArrayList<>(urls);
    }

    private static List<String> fetchForm4UrlsFromEdgarBrowse(Set<String> ciks, int days) {
        List<String> urls = new ArrayList<>();
        LocalDate limit = LocalDate.now().minusDays(days);
        for (String cik : ciks) {
            try {
                String xml = downloadText("https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=" + cik + "&type=4&output=atom");
                Matcher m = Pattern.compile("<entry>.*?<filing-date>(.*?)</filing-date>.*?<filing-href>(.*?)</filing-href>.*?</entry>", Pattern.DOTALL).matcher(xml);
                while (m.find()) {
                    if (LocalDate.parse(m.group(1).trim()).isAfter(limit.minusDays(1))) {
                        String xmlUrl = findXmlInIndex(m.group(2).trim());
                        if (xmlUrl != null) urls.add(xmlUrl);
                    }
                }
            } catch (Exception e) { logDebug("Browse fallback failed for CIK " + cik); }
        }
        return urls;
    }

    private static String findXmlInIndex(String indexUrl) {
        try {
            String html = downloadText(indexUrl);
            Matcher m = Pattern.compile("href=\"([^\"]*?/form4\\.xml)\"", Pattern.CASE_INSENSITIVE).matcher(html);
            if (m.find()) return m.group(1).startsWith("http") ? m.group(1) : "https://www.sec.gov" + m.group(1);
        } catch (Exception e) {}
        return null;
    }

    private static long extractLong(JsonNode n, String p) {
        JsonNode target = nodeAt(n, p);
        String val = target.isObject() ? target.path("value").asText("0") : target.asText("0");
        return Long.parseLong(val.replaceAll("[^0-9-]", ""));
    }

    private static double extractDouble(JsonNode n, String p) {
        JsonNode target = nodeAt(n, p);
        String val = target.isObject() ? target.path("value").asText("0") : target.asText("0");
        return Double.parseDouble(val.replaceAll("[^0-9.\\-]", ""));
    }

    private static JsonNode nodeAt(JsonNode n, String p) {
        for (String part : p.split("\\.")) n = n.path(part);
        return n;
    }

    private static String extractXmlPayload(String raw) {
        int s = raw.indexOf("<ownershipDocument");
        int e = raw.indexOf("</ownershipDocument>");
        return (s >= 0 && e > s) ? raw.substring(s, e + 20) : raw;
    }

    private static boolean sendNotification(String msg) {
        String url = System.getenv("DISCORD_WEBHOOK_URL");
        if (url == null || url.isBlank()) return false;
        try {
            String payload = "{\"content\":\"" + msg.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"}";
            HttpClient.newHttpClient().send(HttpRequest.newBuilder().uri(URI.create(url)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.discarding());
            return true;
        } catch (Exception e) { return false; }
    }

    private static class MasterIndex {
        final String indexDate, content;
        MasterIndex(String d, String c) { this.indexDate = d; this.content = c; }
    }
}