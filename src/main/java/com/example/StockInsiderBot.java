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

public class StockInsiderBot {

    private static final String SEC_BASE = "https://www.sec.gov/Archives/";
    private static final String TICKER_URL = "https://www.sec.gov/include/ticker.txt";
    private static final String DEFAULT_SEC_USER_AGENT = "SEC4-Insider-Bot AdminContact@example.com";
    private static final String DEFAULT_SEC_CONTACT_EMAIL = "contact@example.com";
    private static final long DEFAULT_MINIMUM_USD = 500_000L;
    private static final int DEFAULT_MAX_LOOKBACK_DAYS = 1;
    private static final boolean DEFAULT_DEBUG = true;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);
    private static final Map<String, String> FALLBACK_TICKER_MAP = Map.ofEntries(
            Map.entry("BRKB", "1067983"),
            Map.entry("BRK-B", "1067983"),
            Map.entry("MSFT", "0000789019"),
            Map.entry("ZTS", "0001555285"),
            Map.entry("STZ", "0001593873"));

    public static void main(String[] args) {
        try {
            Map<String, String> options = parseOptions(args);
            String tickersArg = firstNonBlank(options.get("tickers"), System.getenv("TICKERS"),
                    options.get("positional"));
            long minimumUsd = parseLong(firstNonBlank(options.get("threshold"), System.getenv("THRESHOLD_USD")),
                    DEFAULT_MINIMUM_USD);
            int maxLookbackDays = parseInt(firstNonBlank(options.get("lookback"), System.getenv("LOOKBACK_DAYS")),
                    DEFAULT_MAX_LOOKBACK_DAYS);
            boolean debug = parseBoolean(firstNonBlank(options.get("debug"), System.getenv("DEBUG")), DEFAULT_DEBUG);
            setDebug(debug);
            logDebug("Debug mode enabled: " + debug);
            logDebug("Tickers: " + tickersArg);
            logDebug("Threshold: " + minimumUsd);
            logDebug("Lookback days: " + maxLookbackDays);

            if (tickersArg == null || tickersArg.isBlank()) {
                System.out.println("No tickers provided. Use --tickers=... or TICKERS env.");
                return;
            }

            String[] tickers = parseTickers(tickersArg);
            if (tickers.length == 0) {
                System.out.println("No valid tickers found in input.");
                return;
            }

            Map<String, String> tickerToCik = downloadTickerMapping();
            if (tickerToCik.isEmpty()) {
                System.err.println("Failed to download SEC ticker mapping.");
                return;
            }

            Set<String> ciks = mapTickersToCiks(tickers, tickerToCik);
            if (ciks.isEmpty()) {
                System.err.println("No valid CIKs found for provided tickers.");
                return;
            }

            LocalDate currentDate = LocalDate.now().minusDays(1);
            List<String> form4Urls = new ArrayList<>();
            MasterIndex masterIndex = findMasterIndex(currentDate, maxLookbackDays);
            if (masterIndex != null) {
                form4Urls.addAll(parseMasterIdx(masterIndex.content, ciks));
                logDebug("Master index lookup returned " + form4Urls.size() + " Form 4 URLs.");
            } else {
                logDebug("Unable to find a valid SEC master index in the last " + maxLookbackDays + " days.");
            }

            if (form4Urls.isEmpty()) {
                System.out.println("No Form 4 filings found in daily master index. Falling back to SEC browse API...");
                form4Urls.addAll(fetchForm4UrlsFromEdgarBrowse(ciks, maxLookbackDays));
                logDebug("Browse API fallback returned " + form4Urls.size() + " Form 4 XML URLs.");
            }

            if (form4Urls.isEmpty()) {
                // No Form 4 found - could be weekend/holiday. Not an error.
                System.out.println("No Form 4 filings found for " + String.join(", ", tickers) + " in the last "
                        + maxLookbackDays + " days.");
                return;
            }

            // Group alerts by ticker and track failures
            Map<String, List<AlertEntry>> allAlerts = new LinkedHashMap<>();
            int processedCount = 0;
            int failedCount = 0;
            for (String url : form4Urls) {
                try {
                    String xml = downloadText(url);
                    Map<String, List<AlertEntry>> parsed = parseForm4(xml, minimumUsd);
                    parsed.forEach((ticker, alerts) -> {
                        allAlerts.computeIfAbsent(ticker, k -> new ArrayList<>()).addAll(alerts);
                    });
                    processedCount++;
                } catch (Exception ex) {
                    failedCount++;
                    System.err.println("Warning: failed to process Form 4 at " + url + " - " + ex.getMessage());
                }
            }

            // If all Form 4s failed, that's an error worth reporting
            if (processedCount == 0 && failedCount > 0) {
                throw new Exception("Failed to process any of the " + failedCount + " Form 4 filings found.");
            }

            if (allAlerts.isEmpty()) {
                System.out.println("No large insider transactions found for " + String.join(", ", tickers)
                        + " (threshold: $" + minimumUsd + ") on " + masterIndex.indexDate + ".");
                return;
            }

            // Build nicely formatted notification grouped by ticker
            String message = buildGroupedNotification(allAlerts, masterIndex.indexDate);
            boolean notified = sendNotification(message);
            System.out.println("Found " + allAlerts.values().stream().mapToInt(List::size).sum() + " alert(s) in " +
                    allAlerts.size() + " ticker(s). Notification sent: " + notified);
            if (!notified) {
                System.out.println(message);
            }
        } catch (Exception e) {
            System.err.println("Fatal error: " + e.getMessage());
            e.printStackTrace();
            // Only send error notification for real errors, not for "no data found"
            // situations
            String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
            if (!errorMsg.contains("No Form 4 filings found") &&
                    !errorMsg.contains("No large insider transactions found") &&
                    !errorMsg.contains("No valid CIKs found")) {
                sendErrorNotification("Insider Bot Error: " + errorMsg);
            }
            System.exit(1);
        }
    }

    // ---------- Helper classes and methods ----------

    private static class AlertEntry {
        final String ownerName;
        final String position;
        final String type;
        final String security;
        final long shares;
        final double price;
        final double amount;
        final boolean is10b51;

        AlertEntry(String ownerName, String position, String type, String security,
                long shares, double price, double amount, boolean is10b51) {
            this.ownerName = ownerName;
            this.position = position;
            this.type = type;
            this.security = security;
            this.shares = shares;
            this.price = price;
            this.amount = amount;
            this.is10b51 = is10b51;
        }
    }

    private static String buildGroupedNotification(Map<String, List<AlertEntry>> alertsByTicker, String indexDate) {
        StringBuilder msg = new StringBuilder();
        msg.append("🔔 Insider Alerts (").append(indexDate).append(")\n\n");

        List<String> sortedTickers = new ArrayList<>(alertsByTicker.keySet());
        Collections.sort(sortedTickers);

        for (String ticker : sortedTickers) {
            List<AlertEntry> entries = alertsByTicker.get(ticker);
            msg.append("▶ ").append(ticker).append("\n");

            for (AlertEntry e : entries) {
                String action = e.type + (e.is10b51 ? " [10b5-1]" : "");
                msg.append(String.format("  %s | %s | %s | %,d @ $%,.2f = $%,.2f\n",
                        e.ownerName,
                        e.position,
                        action,
                        e.shares,
                        e.price,
                        e.amount));
            }
            msg.append("\n");
        }

        return msg.toString().trim();
    }

    // ---------- Parsing helpers (unchanged) ----------

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new HashMap<>();
        String positional = null;
        for (String arg : args) {
            if (arg == null || arg.isBlank()) {
                continue;
            }
            if (arg.startsWith("--")) {
                String normalized = arg.substring(2);
                String[] parts = normalized.split("=", 2);
                if (parts.length == 2) {
                    options.put(parts[0].toLowerCase(Locale.ROOT), parts[1]);
                } else if (parts.length == 1) {
                    options.put(parts[0].toLowerCase(Locale.ROOT), "true");
                }
            } else if (positional == null) {
                positional = arg;
            }
        }
        options.put("positional", positional);
        return options;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static boolean debugEnabled = DEFAULT_DEBUG;

    private static void setDebug(boolean enabled) {
        debugEnabled = enabled;
    }

    private static void logDebug(String message) {
        if (debugEnabled) {
            System.out.println("DEBUG: " + message);
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            if (value != null && !value.isBlank()) {
                return Long.parseLong(value.trim());
            }
        } catch (NumberFormatException ignored) {
        }
        return fallback;
    }

    private static int parseInt(String value, int fallback) {
        try {
            if (value != null && !value.isBlank()) {
                return Integer.parseInt(value.trim());
            }
        } catch (NumberFormatException ignored) {
        }
        return fallback;
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        if (trimmed.equals("false") || trimmed.equals("0") || trimmed.equals("no") || trimmed.equals("off")) {
            return false;
        }
        return true;
    }

    private static String[] parseTickers(String tickersArg) {
        return Arrays.stream(tickersArg.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toUpperCase)
                .toArray(String[]::new);
    }

    private static Map<String, String> downloadTickerMapping() {
        Map<String, String> map = new HashMap<>();
        try {
            String content = downloadText(TICKER_URL);
            if (content != null && !content.isBlank()) {
                for (String line : content.split("\\R")) {
                    String[] parts = line.trim().split("\\t");
                    if (parts.length == 2) {
                        map.put(parts[0].toUpperCase(Locale.ROOT), parts[1]);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Warning: could not download SEC ticker mapping - " + e.getMessage());
        }

        if (map.isEmpty()) {
            System.err.println("Warning: using fallback ticker mapping for common symbols.");
            map.putAll(FALLBACK_TICKER_MAP);
        }
        return map;
    }

    private static Set<String> mapTickersToCiks(String[] tickers, Map<String, String> tickerToCik) {
        Set<String> ciks = new HashSet<>();
        for (String ticker : tickers) {
            String cik = findCikForTicker(ticker, tickerToCik);
            if (cik != null) {
                ciks.add(cik);
                logDebug("Ticker mapped: " + ticker + " -> " + cik);
            } else {
                System.err.println("Warning: ticker not found in SEC mapping: " + ticker);
            }
        }
        return ciks;
    }

    private static String findCikForTicker(String ticker, Map<String, String> tickerToCik) {
        String upperTicker = ticker.toUpperCase(Locale.ROOT).trim();
        if (upperTicker.isBlank()) {
            return null;
        }

        if (tickerToCik.containsKey(upperTicker)) {
            return tickerToCik.get(upperTicker);
        }

        String normalized = upperTicker.replace('.', '-');
        if (tickerToCik.containsKey(normalized)) {
            return tickerToCik.get(normalized);
        }

        String noDash = upperTicker.replace("-", "");
        if (!noDash.equals(upperTicker) && tickerToCik.containsKey(noDash)) {
            return tickerToCik.get(noDash);
        }

        if (upperTicker.length() > 1) {
            char last = upperTicker.charAt(upperTicker.length() - 1);
            if ((last == 'A' || last == 'B') && upperTicker.charAt(upperTicker.length() - 2) != '-') {
                String option = upperTicker.substring(0, upperTicker.length() - 1) + '-' + last;
                if (tickerToCik.containsKey(option)) {
                    return tickerToCik.get(option);
                }
            }
        }

        if (FALLBACK_TICKER_MAP.containsKey(upperTicker)) {
            String cik = FALLBACK_TICKER_MAP.get(upperTicker);
            logDebug("Using fallback mapping for " + upperTicker + " -> " + cik);
            return cik;
        }
        if (FALLBACK_TICKER_MAP.containsKey(normalized)) {
            String cik = FALLBACK_TICKER_MAP.get(normalized);
            logDebug("Using fallback mapping for " + normalized + " -> " + cik);
            return cik;
        }
        if (!noDash.equals(upperTicker) && FALLBACK_TICKER_MAP.containsKey(noDash)) {
            String cik = FALLBACK_TICKER_MAP.get(noDash);
            logDebug("Using fallback mapping for " + noDash + " -> " + cik);
            return cik;
        }
        if (upperTicker.length() > 1) {
            char last = upperTicker.charAt(upperTicker.length() - 1);
            if ((last == 'A' || last == 'B') && upperTicker.charAt(upperTicker.length() - 2) != '-') {
                String option = upperTicker.substring(0, upperTicker.length() - 1) + '-' + last;
                if (FALLBACK_TICKER_MAP.containsKey(option)) {
                    String cik = FALLBACK_TICKER_MAP.get(option);
                    logDebug("Using fallback mapping for " + option + " -> " + cik);
                    return cik;
                }
            }
        }

        logDebug("Ticker not found in any mapping: " + upperTicker);
        return null;
    }

    private static MasterIndex findMasterIndex(LocalDate startDate, int maxLookbackDays) {
        LocalDate date = startDate;
        StringBuilder combinedContent = new StringBuilder();
        LocalDate foundDate = null;

        for (int i = 0; i < maxLookbackDays; i++) {
            String dateStr = date.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String url = SEC_BASE + "edgar/daily-index/" + date.getYear() + "/QTR"
                    + ((date.getMonthValue() - 1) / 3 + 1)
                    + "/master." + dateStr + ".idx";
            try {
                String content = downloadText(url);
                if (content != null && !content.isBlank()) {
                    System.out.println("Using SEC index: " + url);
                    combinedContent.append(content);
                    if (foundDate == null) {
                        foundDate = date;
                    }
                }
            } catch (Exception e) {
                logDebug("Could not download master index for " + date + " - " + e.getMessage());
            }
            date = date.minusDays(1);
        }

        if (combinedContent.length() > 0 && foundDate != null) {
            return new MasterIndex(foundDate.format(DateTimeFormatter.ofPattern("yyyyMMdd")),
                    combinedContent.toString());
        }
        return null;
    }

    private static String downloadText(String url) throws Exception {
        Exception lastException = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try (CloseableHttpClient client = HttpClients.createDefault()) {
                HttpGet get = new HttpGet(url);
                String userAgent = firstNonBlank(System.getenv("SEC_USER_AGENT"), DEFAULT_SEC_USER_AGENT);
                String contactEmail = firstNonBlank(System.getenv("SEC_CONTACT_EMAIL"), DEFAULT_SEC_CONTACT_EMAIL);
                get.setHeader("User-Agent", userAgent);
                get.setHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                get.setHeader("Accept-Language", "en-US,en;q=0.9");
                get.setHeader("Accept-Encoding", "gzip, deflate");
                get.setHeader("Cache-Control", "no-cache");
                get.setHeader("Pragma", "no-cache");
                get.setHeader("Connection", "keep-alive");
                get.setHeader("Referer", "https://www.sec.gov/edgar/searchedgar/companies.htm");
                get.setHeader("From", contactEmail);
                get.setHeader("Contact", contactEmail);
                get.setHeader("DNT", "1");
                get.setHeader("Upgrade-Insecure-Requests", "1");
                get.setHeader("Sec-Fetch-Dest", "document");
                get.setHeader("Sec-Fetch-Mode", "navigate");
                get.setHeader("Sec-Fetch-Site", "same-origin");
                get.setHeader("Sec-Fetch-User", "?1");
                try (ClassicHttpResponse response = client.execute(get)) {
                    int status = response.getCode();
                    if (status == HttpStatus.SC_OK) {
                        HttpEntity entity = response.getEntity();
                        if (entity == null) {
                            throw new IllegalStateException("Empty response from " + url);
                        }
                        return EntityUtils.toString(entity);
                    } else if (status == 403 || status == 404) {
                        // 403 Forbidden and 404 Not Found are permanent errors, don't retry
                        throw new IllegalStateException("HTTP " + status + " for " + url);
                    } else {
                        // Temporary errors: retry
                        lastException = new IllegalStateException(
                                "HTTP " + status + " for " + url + " (attempt " + attempt + ")");
                        if (attempt < 3) {
                            Thread.sleep(2000);
                        }
                    }
                }
            } catch (Exception e) {
                lastException = e;
                if (attempt < 3) {
                    Thread.sleep(1000);
                }
            }
        }
        throw lastException != null ? lastException
                : new IllegalStateException("Failed to download " + url + " after 3 attempts");
    }

    private static List<String> parseMasterIdx(String content, Set<String> ciks) {
        Set<String> urls = new LinkedHashSet<>();
        if (content == null) {
            return new ArrayList<>(urls);
        }
        for (String line : content.split("\\R")) {
            if (line.isBlank() || line.startsWith("CIK|") || line.startsWith("-----")) {
                continue;
            }
            String[] parts = line.split("\\|", 6);
            if (parts.length < 5) {
                continue;
            }
            String cik = parts[0].trim();
            String formType = parts[2].trim();
            if (!formType.startsWith("4")) {
                continue;
            }
            if (ciks.contains(cik)) {
                String filename = parts[4].trim();
                if (!filename.isEmpty()) {
                    urls.add(SEC_BASE + filename);
                }
            }
        }
        return new ArrayList<>(urls);
    }

    private static List<String> fetchForm4UrlsFromEdgarBrowse(Set<String> ciks, int maxLookbackDays) {
        Set<String> urls = new LinkedHashSet<>();
        for (String cik : ciks) {
            try {
                String browseUrl = "https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=" + cik
                        + "&type=4&owner=include&count=100&output=atom";
                logDebug("Fetching browse-edgar feed for CIK " + cik + ": " + browseUrl);
                String atomXml = downloadText(browseUrl);
                if (atomXml == null || atomXml.isBlank()) {
                    continue;
                }
                urls.addAll(parseBrowseEdgarAtom(atomXml, maxLookbackDays));
            } catch (Exception e) {
                System.err.println("Warning: browse-edgar fallback failed for CIK " + cik + " - " + e.getMessage());
            }
        }
        return new ArrayList<>(urls);
    }

    private static List<String> parseBrowseEdgarAtom(String atomXml, int maxLookbackDays) {
        Set<String> urls = new LinkedHashSet<>();
        LocalDate threshold = LocalDate.now().minusDays(maxLookbackDays);
        Pattern entryPattern = Pattern.compile("<entry>(.*?)</entry>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Pattern datePattern = Pattern.compile("<filing-date>(.*?)</filing-date>",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Pattern hrefPattern = Pattern.compile("<filing-href>(.*?)</filing-href>",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Matcher entryMatcher = entryPattern.matcher(atomXml);
        while (entryMatcher.find()) {
            String entry = entryMatcher.group(1);
            Matcher dateMatcher = datePattern.matcher(entry);
            Matcher hrefMatcher = hrefPattern.matcher(entry);
            if (!dateMatcher.find() || !hrefMatcher.find()) {
                continue;
            }
            String filingDate = dateMatcher.group(1).trim();
            String filingHref = hrefMatcher.group(1).trim();
            try {
                LocalDate date = LocalDate.parse(filingDate);
                if (date.isBefore(threshold)) {
                    continue;
                }
                String xmlUrl = findForm4XmlUrlFromIndexPage(filingHref);
                if (xmlUrl != null) {
                    urls.add(xmlUrl);
                } else {
                    logDebug("Unable to resolve form4.xml from index page: " + filingHref);
                }
            } catch (Exception e) {
                logDebug("Skipping entry with invalid date or URL: " + e.getMessage());
            }
        }
        return new ArrayList<>(urls);
    }

    private static String findForm4XmlUrlFromIndexPage(String indexUrl) {
        try {
            String html = downloadText(indexUrl);
            if (html == null || html.isBlank()) {
                return null;
            }
            Pattern xmlLinkPattern = Pattern.compile("href=\"([^\"]*?/form4\\.xml)\"", Pattern.CASE_INSENSITIVE);
            Matcher matcher = xmlLinkPattern.matcher(html);
            String bestUrl = null;
            while (matcher.find()) {
                String relative = matcher.group(1).trim();
                String fullUrl = relative.startsWith("http") ? relative : "https://www.sec.gov" + relative;
                if (!relative.toLowerCase(Locale.ROOT).contains("xslf345")) {
                    return fullUrl; // Prefer raw XML over HTML render
                }
                if (bestUrl == null) {
                    bestUrl = fullUrl;
                }
            }
            return bestUrl;
        } catch (Exception e) {
            logDebug("Failed to parse filing index page " + indexUrl + " - " + e.getMessage());
        }
        return null;
    }

    private static Map<String, List<AlertEntry>> parseForm4(String xml, long minimumUsd) throws Exception {
        Map<String, List<AlertEntry>> alerts = new LinkedHashMap<>();
        XmlMapper mapper = new XmlMapper();
        String xmlPayload = extractXmlPayload(xml);
        JsonNode root = mapper.readTree(xmlPayload);

        JsonNode issuer = root.path("issuer");
        String ticker = issuer.path("issuerTradingSymbol").asText(issuer.path("issuerCIK").asText("Unknown"));

        JsonNode reportingOwner = root.path("reportingOwner");
        String ownerName = reportingOwner.path("reportingOwnerId").path("rptOwnerName").asText("Unknown Owner");
        String position = extractPosition(reportingOwner);

        JsonNode nonDeriv = root.path("nonDerivativeTable");
        if (nonDeriv.isMissingNode()) {
            nonDeriv = root.path("ownershipDocument").path("nonDerivativeTable");
        }
        if (nonDeriv.isMissingNode()) {
            return alerts;
        }

        JsonNode trans = nonDeriv.path("nonDerivativeTransaction");
        if (trans.isMissingNode()) {
            return alerts;
        }

        if (trans.isArray()) {
            for (JsonNode transaction : trans) {
                AlertEntry entry = processTransaction(transaction, ownerName, position, minimumUsd);
                if (entry != null) {
                    alerts.computeIfAbsent(ticker, k -> new ArrayList<>()).add(entry);
                }
            }
        } else if (trans.isObject()) {
            AlertEntry entry = processTransaction(trans, ownerName, position, minimumUsd);
            if (entry != null) {
                alerts.computeIfAbsent(ticker, k -> new ArrayList<>()).add(entry);
            }
        }
        return alerts;
    }

    private static String extractPosition(JsonNode reportingOwner) {
        String[] candidatePaths = {
                "reportingOwnerRelationship.officer.title",
                "reportingOwnerRelationship.director.title",
                "reportingOwnerRelationship.other.title",
                "relationshipTitle",
                "reportingOwnerId.rptOwnerTitle"
        };
        for (String path : candidatePaths) {
            String value = pathValue(reportingOwner, path);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "Unknown Position";
    }

    private static String pathValue(JsonNode root, String path) {
        JsonNode node = root;
        for (String part : path.split("\\.")) {
            node = node.path(part);
            if (node.isMissingNode()) {
                return null;
            }
        }
        return node.asText(null);
    }

    private static AlertEntry processTransaction(JsonNode transaction, String ownerName, String position,
            long minimumUsd) {
        String code = transaction.path("transactionCoding").path("transactionCode").asText();
        if (!"P".equals(code) && !"S".equals(code)) {
            return null;
        }

        long shares = extractLong(transaction, "transactionAmounts.transactionShares");
        double price = extractDouble(transaction, "transactionAmounts.transactionPricePerShare");
        if (shares <= 0 || price <= 0) {
            return null;
        }
        double amount = shares * price;
        if (amount <= minimumUsd) {
            return null;
        }

        String type = "P".equals(code) ? "BUY" : "SELL";
        String security = extractText(transaction, "securityTitle", "stock");
        String is10b51 = transaction.path("transactionCoding").path("is10b51Transaction").asText();
        boolean isPlan = "true".equalsIgnoreCase(is10b51);

        return new AlertEntry(ownerName, position, type, security, shares, price, amount, isPlan);
    }

    private static long extractLong(JsonNode root, String path) {
        JsonNode node = nodeAt(root, path);
        if (node.isNumber()) {
            return node.asLong(0);
        }
        if (node.isTextual() && !node.asText().isBlank()) {
            return parseLongSafely(node.asText());
        }
        JsonNode valueNode = node.path("value");
        if (!valueNode.isMissingNode() && !valueNode.isNull()) {
            if (valueNode.isNumber()) {
                return valueNode.asLong(0);
            }
            if (valueNode.isTextual() && !valueNode.asText().isBlank()) {
                return parseLongSafely(valueNode.asText());
            }
        }
        return 0;
    }

    private static double extractDouble(JsonNode root, String path) {
        JsonNode node = nodeAt(root, path);
        if (node.isNumber()) {
            return node.asDouble(0.0);
        }
        if (node.isTextual() && !node.asText().isBlank()) {
            return parseDoubleSafely(node.asText());
        }
        JsonNode valueNode = node.path("value");
        if (!valueNode.isMissingNode() && !valueNode.isNull()) {
            if (valueNode.isNumber()) {
                return valueNode.asDouble(0.0);
            }
            if (valueNode.isTextual() && !valueNode.asText().isBlank()) {
                return parseDoubleSafely(valueNode.asText());
            }
        }
        return 0.0;
    }

    private static String extractText(JsonNode root, String path, String fallback) {
        JsonNode node = nodeAt(root, path);
        if (!node.isMissingNode() && !node.asText().isBlank()) {
            return node.asText();
        }
        JsonNode valueNode = node.path("value");
        if (!valueNode.isMissingNode() && !valueNode.asText().isBlank()) {
            return valueNode.asText();
        }
        return fallback;
    }

    private static JsonNode nodeAt(JsonNode root, String path) {
        JsonNode node = root;
        for (String part : path.split("\\.")) {
            node = node.path(part);
            if (node.isMissingNode()) {
                return node;
            }
        }
        return node;
    }

    private static long parseLongSafely(String text) {
        try {
            return Long.parseLong(text.replaceAll("[^0-9-]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double parseDoubleSafely(String text) {
        try {
            return Double.parseDouble(text.replaceAll("[^0-9.\\-]", ""));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static String extractXmlPayload(String rawText) {
        if (rawText == null) {
            return "";
        }
        int xmlWrapperStart = rawText.indexOf("<XML>");
        if (xmlWrapperStart >= 0) {
            int xmlWrapperEnd = rawText.indexOf("</XML>", xmlWrapperStart);
            if (xmlWrapperEnd > xmlWrapperStart) {
                return rawText.substring(xmlWrapperStart + 5, xmlWrapperEnd).trim();
            }
        }
        int documentStart = rawText.indexOf("<ownershipDocument");
        if (documentStart >= 0) {
            int documentEnd = rawText.indexOf("</ownershipDocument>", documentStart);
            if (documentEnd > documentStart) {
                return rawText.substring(documentStart, documentEnd + "</ownershipDocument>".length()).trim();
            }
        }
        return rawText;
    }

    private static boolean sendNotification(String message) {
        String discordUrl = System.getenv("DISCORD_WEBHOOK_URL");
        if (discordUrl == null || discordUrl.isBlank()) {
            System.err.println("Warning: DISCORD_WEBHOOK_URL is not set. Notification will not be sent.");
            return false;
        }
        return sendDiscordWebhook(discordUrl, "Insider Alert", message);
    }

    private static void sendErrorNotification(String errorMessage) {
        String discordUrl = System.getenv("DISCORD_WEBHOOK_URL");
        if (discordUrl == null || discordUrl.isBlank()) {
            System.err.println("Warning: DISCORD_WEBHOOK_URL is not set. Error notification will not be sent.");
            return;
        }
        sendDiscordWebhook(discordUrl, "Insider Bot Error", errorMessage);
    }

    private static boolean sendDiscordWebhook(String webhookUrl, String title, String message) {
        try {
            String payload = "{\"content\":\"**" + escapeJson(title) + "**\\n" + escapeJson(message) + "\"}";
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(HTTP_TIMEOUT)
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .timeout(HTTP_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(truncateDiscordPayload(payload)))
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return true;
            }
            System.err.println("Warning: Discord webhook returned status " + response.statusCode());
        } catch (Exception e) {
            System.err.println("Warning: failed to send Discord notification: " + e.getMessage());
        }
        return false;
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private static String truncateDiscordPayload(String payload) {
        int maxLength = 1900;
        if (payload.length() <= maxLength) {
            return payload;
        }
        return payload.substring(0, maxLength) + "...";
    }

    private static class MasterIndex {
        final String indexDate;
        final String content;

        MasterIndex(String indexDate, String content) {
            this.indexDate = indexDate;
            this.content = content;
        }
    }
}