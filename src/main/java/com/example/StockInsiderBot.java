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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

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

            Map<String, String> cikToRequestedTicker = new HashMap<>();
            Set<String> ciks = new HashSet<>();
            for (String ticker : tickers) {
                String cik = findCikForTicker(ticker, tickerToCik);
                if (cik != null) {
                    String normalizedCik = cik.replaceFirst("^0+(?!$)", "");
                    ciks.add(normalizedCik);
                    cikToRequestedTicker.put(normalizedCik, ticker);
                    logDebug("Ticker mapped: " + ticker + " -> " + normalizedCik);
                } else {
                    System.err.println("Warning: ticker not found in SEC mapping: " + ticker);
                }
            }

            if (ciks.isEmpty()) {
                System.err.println("No valid CIKs found for provided tickers.");
                return;
            }

            LocalDate currentDate = LocalDate.now(ZoneId.of("America/New_York"));
            List<String> form4Urls = new ArrayList<>();
            MasterIndex masterIndex = findMasterIndex(currentDate, maxLookbackDays);
            if (masterIndex != null) {
                form4Urls.addAll(parseMasterIdx(masterIndex.content, ciks));
                logDebug("Master index lookup returned " + form4Urls.size() + " Form 4 URLs.");
            } else {
                logDebug("Unable to find a valid SEC master index in the last " + maxLookbackDays + " days.");
            }

            if (form4Urls.isEmpty()) {
                logDebug("No Form 4 URLs in master index. Falling back to SEC browse API...");
                form4Urls.addAll(fetchForm4UrlsFromEdgarBrowse(ciks, maxLookbackDays));
                logDebug("Browse API fallback returned " + form4Urls.size() + " Form 4 XML URLs.");
            }

            if (form4Urls.isEmpty()) {
                String msg = "No Form 4 filings found for " + String.join(", ", tickers) + " in the last "
                        + maxLookbackDays + " days.";
                System.out.println(msg);
                sendNotification(buildMissingNotification(tickers, "No Form 4 filings found"));
                return;
            }

            Map<String, List<AlertEntry>> allAlerts = new LinkedHashMap<>();
            Set<String> tickersWithForm4 = new HashSet<>();
            int processedCount = 0;
            int failedCount = 0;
            for (String url : form4Urls) {
                try {
                    String xml = downloadText(url);
                    logDebug("Processing Form 4 URL: " + url);
                    Map<String, List<AlertEntry>> parsed = parseForm4(xml, minimumUsd, cikToRequestedTicker);
                    parsed.forEach((ticker, alerts) -> {
                        tickersWithForm4.add(ticker);
                        if (!alerts.isEmpty()) {
                            allAlerts.computeIfAbsent(ticker, k -> new ArrayList<>()).addAll(alerts);
                        }
                    });
                    processedCount++;
                } catch (Exception ex) {
                    failedCount++;
                    System.err.println("Warning: failed to process Form 4 at " + url + " - " + ex.getMessage());
                }
            }

            if (processedCount == 0 && failedCount > 0) {
                throw new Exception("Failed to process any of the " + failedCount + " Form 4 filings found.");
            }

            // 只保留有交易记录的 ticker
            Map<String, List<AlertEntry>> filteredAlerts = new LinkedHashMap<>();
            for (String ticker : tickers) {
                if (allAlerts.containsKey(ticker) && !allAlerts.get(ticker).isEmpty()) {
                    filteredAlerts.put(ticker, allAlerts.get(ticker));
                }
            }

            if (filteredAlerts.isEmpty()) {
                String noTradeMsg = "📭 No insider transactions found today.";
                System.out.println(noTradeMsg);
                sendNotification(noTradeMsg);
                return;
            }

            String message = buildGroupedNotification(filteredAlerts,
                    masterIndex != null ? masterIndex.indexDate : LocalDate.now().toString());
            boolean notified = sendNotification(message);
            System.out
                    .println("Found " + filteredAlerts.values().stream().mapToInt(List::size).sum() + " alert(s) in " +
                            filteredAlerts.size() + " ticker(s). Notification sent: " + notified);
            if (!notified) {
                System.out.println(message);
            }
        } catch (Exception e) {
            System.err.println("Fatal error: " + e.getMessage());
            e.printStackTrace();
            String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
            if (!errorMsg.contains("No Form 4 filings found") &&
                    !errorMsg.contains("No large insider transactions found") &&
                    !errorMsg.contains("No valid CIKs found")) {
                sendErrorNotification("Insider Bot Error: " + errorMsg);
            }
            System.exit(1);
        }
    }

    private static class AlertEntry {
        final String ownerName;
        final String position;
        final String type;
        final long shares;
        final double price;
        final double amount;
        final boolean is10b51;
        final String transactionDate;
        final long sharesOwnedAfter; // 新增

        AlertEntry(String ownerName, String position, String type, String security,
                long shares, double price, double amount, boolean is10b51,
                String transactionDate, long sharesOwnedAfter) {
            this.ownerName = ownerName;
            this.position = position;
            this.type = type;
            this.shares = shares;
            this.price = price;
            this.amount = amount;
            this.is10b51 = is10b51;
            this.transactionDate = transactionDate;
            this.sharesOwnedAfter = sharesOwnedAfter;
        }
    }
private static String buildGroupedNotification(Map<String, List<AlertEntry>> alertsByTicker,
        String indexDate) {
    StringBuilder msg = new StringBuilder();
    msg.append("🔔⏰ Insider Alerts (").append(indexDate).append(")\n\n");
    for (Map.Entry<String, List<AlertEntry>> entry : alertsByTicker.entrySet()) {
        String ticker = entry.getKey();
        List<AlertEntry> entries = entry.getValue();
        msg.append("▪ **").append(ticker).append("**\n");
        for (AlertEntry e : entries) {
            String planIcon = e.is10b51 ? " 🛡️[10b5-1]" : "";
            String date = e.transactionDate.isEmpty() ? "N/A" : e.transactionDate;
            String sharesStr = formatNumber(e.shares);
            String amountStr = formatAmount(e.amount);
            String positionStr = e.sharesOwnedAfter > 0 ? formatNumber(e.sharesOwnedAfter) : "N/A";
            String actionIcon = e.type.equals("BUY") ? "📈" : "📉";

            String line = String.format(
                    "📅 %s 👤 %s | 💼 %s | %s %s%s | %s @ $%,.2f = %s | 持仓: %s",
                    date, e.ownerName, e.position, actionIcon, e.type, planIcon,
                    sharesStr, e.price, amountStr, positionStr);

            if ("BUY".equals(e.type)) {
                // 红色圆点 + diff 代码块（桌面端红色字体，手机端可见红色圆点）
                msg.append("```diff\n- 🔴 ").append(line).append("\n```\n");
            } else {
                msg.append("  ").append(line).append("\n");
            }
        }
        msg.append("\n");
    }
    return msg.toString().trim();
} private static String formatNumber(long num) {
        if (num >= 1_000_000)
            return String.format("%.1fM", num / 1_000_000.0);
        if (num >= 1_000)
            return String.format("%.1fK", num / 1_000.0);
        return Long.toString(num);
    }

    private static String formatAmount(double amount) {
        if (amount >= 1_000_000)
            return String.format("$%.1fM", amount / 1_000_000.0);
        if (amount >= 1_000)
            return String.format("$%.1fK", amount / 1_000.0);
        return String.format("$%.0f", amount);
    }

    private static String buildMissingNotification(String[] tickers, String reason) {
        StringBuilder msg = new StringBuilder();
        msg.append("🔔 Insider Alerts\n\n");
        for (String ticker : tickers) {
            msg.append("▶ ").append(ticker).append("\n  ").append(reason).append("\n\n");
        }
        return msg.toString().trim();
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new HashMap<>();
        String positional = null;
        for (String arg : args) {
            if (arg == null || arg.isBlank())
                continue;
            if (arg.startsWith("--")) {
                String normalized = arg.substring(2);
                String[] parts = normalized.split("=", 2);
                if (parts.length == 2)
                    options.put(parts[0].toLowerCase(Locale.ROOT), parts[1]);
                else if (parts.length == 1)
                    options.put(parts[0].toLowerCase(Locale.ROOT), "true");
            } else if (positional == null)
                positional = arg;
        }
        options.put("positional", positional);
        return options;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values)
            if (value != null && !value.isBlank())
                return value;
        return null;
    }

    private static boolean debugEnabled = DEFAULT_DEBUG;

    private static void setDebug(boolean enabled) {
        debugEnabled = enabled;
    }

    private static void logDebug(String message) {
        if (debugEnabled)
            System.out.println("DEBUG: " + message);
    }

    private static long parseLong(String value, long fallback) {
        try {
            if (value != null && !value.isBlank())
                return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
        }
        return fallback;
    }

    private static int parseInt(String value, int fallback) {
        try {
            if (value != null && !value.isBlank())
                return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
        }
        return fallback;
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        if (value == null || value.isBlank())
            return fallback;
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        return !(trimmed.equals("false") || trimmed.equals("0") || trimmed.equals("no") || trimmed.equals("off"));
    }

    private static String[] parseTickers(String tickersArg) {
        return Arrays.stream(tickersArg.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(String::toUpperCase).toArray(String[]::new);
    }

    private static Map<String, String> downloadTickerMapping() {
        Map<String, String> map = new HashMap<>();
        try {
            String content = downloadText(TICKER_URL);
            if (content != null && !content.isBlank()) {
                for (String line : content.split("\\R")) {
                    String[] parts = line.trim().split("\\t");
                    if (parts.length == 2)
                        map.put(parts[0].toUpperCase(Locale.ROOT), parts[1]);
                }
            }
        } catch (Exception e) {
        }
        if (map.isEmpty()) {
            map.putAll(FALLBACK_TICKER_MAP);
        }
        return map;
    }

    private static String findCikForTicker(String ticker, Map<String, String> tickerToCik) {
        String cleanInput = ticker.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (cleanInput.isBlank())
            return null;
        for (Map.Entry<String, String> entry : tickerToCik.entrySet()) {
            if (entry.getKey().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "").equals(cleanInput))
                return entry.getValue();
        }
        for (Map.Entry<String, String> entry : FALLBACK_TICKER_MAP.entrySet()) {
            if (entry.getKey().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "").equals(cleanInput))
                return entry.getValue();
        }
        return null;
    }

    private static MasterIndex findMasterIndex(LocalDate startDate, int maxLookbackDays) {
        LocalDate date = startDate;
        StringBuilder combinedContent = new StringBuilder();
        LocalDate foundDate = null;
        for (int i = 0; i < maxLookbackDays; i++) {
            String dateStr = date.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String url = SEC_BASE + "edgar/daily-index/" + date.getYear() + "/QTR"
                    + ((date.getMonthValue() - 1) / 3 + 1) + "/master." + dateStr + ".idx";
            try {
                String content = downloadText(url);
                if (content != null && !content.isBlank()) {
                    logDebug("Using SEC index: " + url);
                    combinedContent.append(content);
                    if (foundDate == null)
                        foundDate = date;
                }
            } catch (Exception e) {
            }
            date = date.minusDays(1);
        }
        return combinedContent.length() > 0 && foundDate != null
                ? new MasterIndex(foundDate.format(DateTimeFormatter.ofPattern("yyyyMMdd")), combinedContent.toString())
                : null;
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
                get.setHeader("From", contactEmail);
                try (ClassicHttpResponse response = client.execute(get)) {
                    int status = response.getCode();
                    if (status == HttpStatus.SC_OK) {
                        HttpEntity entity = response.getEntity();
                        if (entity == null)
                            throw new IllegalStateException("Empty response from " + url);
                        return EntityUtils.toString(entity);
                    } else if (status == 403 || status == 404) {
                        throw new IllegalStateException("HTTP " + status + " for " + url);
                    } else {
                        lastException = new IllegalStateException(
                                "HTTP " + status + " for " + url + " (attempt " + attempt + ")");
                        if (attempt < 3)
                            Thread.sleep(2000);
                    }
                }
            } catch (Exception e) {
                lastException = e;
                if (attempt < 3)
                    Thread.sleep(1000);
            }
        }
        throw lastException != null ? lastException
                : new IllegalStateException("Failed to download " + url + " after 3 attempts");
    }

    private static List<String> parseMasterIdx(String content, Set<String> ciks) {
        Set<String> cleanCiks = new HashSet<>();
        for (String cik : ciks)
            cleanCiks.add(cik.replaceFirst("^0+(?!$)", ""));
        Set<String> urls = new LinkedHashSet<>();
        if (content == null)
            return new ArrayList<>(urls);
        for (String line : content.split("\\R")) {
            if (line.isBlank() || line.startsWith("CIK|") || line.startsWith("-----"))
                continue;
            String[] parts = line.split("\\|", 6);
            if (parts.length < 5)
                continue;
            String fileCik = parts[0].trim().replaceFirst("^0+(?!$)", "");
            String formType = parts[2].trim();
            if (!formType.startsWith("4"))
                continue;
            if (cleanCiks.contains(fileCik)) {
                String filename = parts[4].trim();
                if (!filename.isEmpty())
                    urls.add(SEC_BASE + filename);
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
                String atomXml = downloadText(browseUrl);
                if (atomXml != null && !atomXml.isBlank())
                    urls.addAll(parseBrowseEdgarAtom(atomXml, maxLookbackDays));
            } catch (Exception e) {
                System.err.println("Warning: browse-edgar fallback failed for CIK " + cik);
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
            if (!dateMatcher.find() || !hrefMatcher.find())
                continue;
            String filingDate = dateMatcher.group(1).trim();
            String filingHref = hrefMatcher.group(1).trim();
            try {
                LocalDate date = LocalDate.parse(filingDate);
                if (date.isBefore(threshold))
                    continue;
                String xmlUrl = findForm4XmlUrlFromIndexPage(filingHref);
                if (xmlUrl != null)
                    urls.add(xmlUrl);
            } catch (Exception e) {
            }
        }
        return new ArrayList<>(urls);
    }

    private static String findForm4XmlUrlFromIndexPage(String indexUrl) {
        try {
            String html = downloadText(indexUrl);
            if (html == null || html.isBlank())
                return null;
            Pattern xmlLinkPattern = Pattern.compile("href=\"([^\"]*?/form4\\.xml)\"", Pattern.CASE_INSENSITIVE);
            Matcher matcher = xmlLinkPattern.matcher(html);
            String bestUrl = null;
            while (matcher.find()) {
                String relative = matcher.group(1).trim();
                String fullUrl = relative.startsWith("http") ? relative : "https://www.sec.gov" + relative;
                if (!relative.toLowerCase(Locale.ROOT).contains("xslf345"))
                    return fullUrl;
                if (bestUrl == null)
                    bestUrl = fullUrl;
            }
            return bestUrl;
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, List<AlertEntry>> parseForm4(String xml, long minimumUsd,
            Map<String, String> cikToRequestedTicker) throws Exception {
        Map<String, List<AlertEntry>> alerts = new LinkedHashMap<>();
        String xmlPayload = extractXmlPayload(xml);
        if (xmlPayload.isBlank()) {
            logDebug("Skipping file: Could not extract valid XML payload.");
            return alerts;
        }
        XmlMapper mapper = new XmlMapper();
        JsonNode root = mapper.readTree(xmlPayload);
        JsonNode issuer = root.path("issuer");
        String rawXmlCik = issuer.path("issuerCik").asText(issuer.path("issuerCIK").asText("Unknown"));
        String normalizedXmlCik = rawXmlCik.replaceFirst("^0+(?!$)", "");
        String ticker = cikToRequestedTicker.getOrDefault(normalizedXmlCik,
                issuer.path("issuerTradingSymbol").asText("Unknown"));
        JsonNode reportingOwner = root.path("reportingOwner");
        if (!isOfficerOrDirector(reportingOwner)) {
            logDebug("Skipping Form 4 for " + ticker + " - reporter is not an officer/director.");
            return alerts;
        }
        String ownerName = reportingOwner.path("reportingOwnerId").path("rptOwnerName").asText("Unknown Owner");
        String position = extractPosition(reportingOwner);

        JsonNode nonDeriv = root.path("nonDerivativeTable");
        if (nonDeriv.isMissingNode())
            nonDeriv = root.path("ownershipDocument").path("nonDerivativeTable");
        if (!nonDeriv.isMissingNode()) {
            JsonNode nonTrans = nonDeriv.path("nonDerivativeTransaction");
            if (!nonTrans.isMissingNode()) {
                if (nonTrans.isArray()) {
                    for (JsonNode tx : nonTrans) {
                        AlertEntry entry = processTransaction(tx, ownerName, position, minimumUsd);
                        if (entry != null)
                            alerts.computeIfAbsent(ticker, k -> new ArrayList<>()).add(entry);
                    }
                } else if (nonTrans.isObject()) {
                    AlertEntry entry = processTransaction(nonTrans, ownerName, position, minimumUsd);
                    if (entry != null)
                        alerts.computeIfAbsent(ticker, k -> new ArrayList<>()).add(entry);
                }
            } else
                logDebug("No non-derivativeTransaction for " + ticker);
        } else
            logDebug("No non-derivativeTable for " + ticker);

        JsonNode deriv = root.path("derivativeTable");
        if (deriv.isMissingNode())
            deriv = root.path("ownershipDocument").path("derivativeTable");
        if (!deriv.isMissingNode()) {
            JsonNode derivTrans = deriv.path("derivativeTransaction");
            if (!derivTrans.isMissingNode()) {
                if (derivTrans.isArray()) {
                    for (JsonNode tx : derivTrans) {
                        AlertEntry entry = processTransaction(tx, ownerName, position, minimumUsd);
                        if (entry != null)
                            alerts.computeIfAbsent(ticker, k -> new ArrayList<>()).add(entry);
                    }
                } else if (derivTrans.isObject()) {
                    AlertEntry entry = processTransaction(derivTrans, ownerName, position, minimumUsd);
                    if (entry != null)
                        alerts.computeIfAbsent(ticker, k -> new ArrayList<>()).add(entry);
                }
            } else
                logDebug("No derivativeTransaction for " + ticker);
        } else
            logDebug("No derivativeTable for " + ticker);

        alerts.putIfAbsent(ticker, new ArrayList<>());
        return alerts;
    }

    private static boolean isOfficerOrDirector(JsonNode reportingOwner) {
        JsonNode rel = reportingOwner.path("reportingOwnerRelationship");
        if (rel.isMissingNode())
            return false;
        String isDirector = rel.path("isDirector").asText();
        String isOfficer = rel.path("isOfficer").asText();
        return "true".equalsIgnoreCase(isDirector) || "1".contentEquals(isDirector) ||
                "true".equalsIgnoreCase(isOfficer) || "1".contentEquals(isOfficer);
    }

    private static String extractPosition(JsonNode reportingOwner) {
        JsonNode rel = reportingOwner.path("reportingOwnerRelationship");
        List<String> titles = new ArrayList<>();
        if (!rel.isMissingNode()) {
            appendIfPresent(rel, "officerTitle", titles);
            appendIfPresent(rel, "directorTitle", titles);
            appendIfPresent(rel, "otherTitle", titles);
            if (!titles.isEmpty())
                return String.join(", ", titles);
        }
        String[] fallbacks = { "relationshipTitle", "reportingOwnerId.rptOwnerTitle" };
        for (String path : fallbacks) {
            String val = pathValue(reportingOwner, path);
            if (val != null && !val.isBlank())
                return val;
        }
        return "Unknown Position";
    }

    private static void appendIfPresent(JsonNode rel, String field, List<String> titles) {
        JsonNode node = rel.path(field);
        if (!node.isMissingNode() && !node.asText().isBlank())
            titles.add(node.asText().trim());
    }

    private static String pathValue(JsonNode root, String path) {
        JsonNode node = root;
        for (String part : path.split("\\.")) {
            node = node.path(part);
            if (node.isMissingNode())
                return null;
        }
        return node.asText(null);
    }

    private static AlertEntry processTransaction(JsonNode transaction, String ownerName, String position,
            long minimumUsd) {
        String code = transaction.path("transactionCoding").path("transactionCode").asText();

        if (!"P".equals(code) && !"S".equals(code)) {
            if (debugEnabled)
                logDebug("Skipping transaction: code=" + code + " (not P/S)");
            return null;
        }

        JsonNode exerciseDateNode = transaction.path("exerciseDate");
        if (!exerciseDateNode.isMissingNode() && !exerciseDateNode.asText().isBlank()) {
            if (debugEnabled)
                logDebug("Skipping transaction: code=" + code + " has exerciseDate=" + exerciseDateNode.asText());
            return null;
        }

        long shares = extractLong(transaction, "transactionAmounts.transactionShares");
        double price = extractDouble(transaction, "transactionAmounts.transactionPricePerShare");
        if (shares <= 0 || price <= 0) {
            if (debugEnabled)
                logDebug("Skipping transaction: code=" + code + " shares=" + shares + " price=" + price);
            return null;
        }

        double amount = shares * price;
        if (amount < minimumUsd) {
            if (debugEnabled)
                logDebug("Skipping transaction: code=" + code + " amount=" + amount + " < threshold=" + minimumUsd);
            return null;
        }

        String type = "P".equals(code) ? "BUY" : "SELL";
        String security = extractText(transaction, "securityTitle", "stock");
        String is10b51 = transaction.path("transactionCoding").path("is10b51Transaction").asText();
        boolean isPlan = "true".equalsIgnoreCase(is10b51);

        String transactionDate = extractText(transaction, "transactionDate", "");
        if (!transactionDate.isEmpty() && transactionDate.length() >= 10) {
            transactionDate = transactionDate.substring(0, 10);
        }

        long sharesOwnedAfter = extractLong(transaction, "postTransactionAmounts.sharesOwnedFollowingTransaction");
        if (sharesOwnedAfter <= 0) {
            sharesOwnedAfter = extractLong(transaction, "sharesOwnedFollowingTransaction");
        }

        if (debugEnabled)
            logDebug("Creating alert: " + ownerName + " " + type + " " + shares + " shares at " + price + " amount="
                    + amount + " date=" + transactionDate + " ownedAfter=" + sharesOwnedAfter);
        return new AlertEntry(ownerName, position, type, security, shares, price, amount, isPlan, transactionDate,
                sharesOwnedAfter);
    }

    private static long extractLong(JsonNode root, String path) {
        JsonNode node = nodeAt(root, path);
        if (node.isNumber())
            return node.asLong(0);
        if (node.isTextual() && !node.asText().isBlank())
            return parseLongSafely(node.asText());
        JsonNode valueNode = node.path("value");
        if (!valueNode.isMissingNode() && !valueNode.isNull()) {
            if (valueNode.isNumber())
                return valueNode.asLong(0);
            if (valueNode.isTextual() && !valueNode.asText().isBlank())
                return parseLongSafely(valueNode.asText());
        }
        return 0;
    }

    private static double extractDouble(JsonNode root, String path) {
        JsonNode node = nodeAt(root, path);
        if (node.isNumber())
            return node.asDouble(0.0);
        if (node.isTextual() && !node.asText().isBlank())
            return parseDoubleSafely(node.asText());
        JsonNode valueNode = node.path("value");
        if (!valueNode.isMissingNode() && !valueNode.isNull()) {
            if (valueNode.isNumber())
                return valueNode.asDouble(0.0);
            if (valueNode.isTextual() && !valueNode.asText().isBlank())
                return parseDoubleSafely(valueNode.asText());
        }
        return 0.0;
    }

    private static String extractText(JsonNode root, String path, String fallback) {
        JsonNode node = nodeAt(root, path);
        if (!node.isMissingNode() && !node.asText().isBlank())
            return node.asText();
        JsonNode valueNode = node.path("value");
        if (!valueNode.isMissingNode() && !valueNode.asText().isBlank())
            return valueNode.asText();
        return fallback;
    }

    private static JsonNode nodeAt(JsonNode root, String path) {
        JsonNode node = root;
        for (String part : path.split("\\.")) {
            node = node.path(part);
            if (node.isMissingNode())
                return node;
        }
        return node;
    }

    private static long parseLongSafely(String text) {
        try {
            return (long) Double.parseDouble(text.replaceAll("[^0-9.\\-]", ""));
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
        if (rawText == null)
            return "";
        String cleanXml = "";
        int xmlStart = rawText.indexOf("<XML>");
        if (xmlStart >= 0) {
            int xmlEnd = rawText.indexOf("</XML>", xmlStart);
            if (xmlEnd > xmlStart)
                cleanXml = rawText.substring(xmlStart + 5, xmlEnd);
        }
        if (cleanXml.isBlank()) {
            Matcher m = Pattern.compile("<ownershipDocument[^>]*>.*?</ownershipDocument>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(rawText);
            if (m.find())
                cleanXml = m.group(0);
        }
        if (cleanXml.isBlank())
            return "";
        cleanXml = cleanXml.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
        cleanXml = cleanXml.replaceAll("&(?!(amp|apos|quot|lt|gt|#\\d+);)", "&amp;");
        cleanXml = cleanXml.replaceAll("</\\s+", "</");
        cleanXml = cleanXml.replaceAll("<\\s+(?=[a-zA-Z_/?!])", "<");
        cleanXml = cleanXml.replaceAll("<(?=[^a-zA-Z_/?!])", "&lt;");
        return cleanXml.trim();
    }

    private static boolean sendNotification(String message) {
        String dingTalkUrl = System.getenv("DING_WEBHOOK_URL");
        if (dingTalkUrl != null && !dingTalkUrl.isBlank()) {
            String dingTalkSecret = System.getenv("DING_WEBHOOK_SIGN");
            return sendDingTalkWebhook(dingTalkUrl, dingTalkSecret, "Insider Alert", message);
        }

        String discordUrl = System.getenv("DISCORD_WEBHOOK_URL");
        if (discordUrl == null || discordUrl.isBlank())
            return false;
        return sendDiscordWebhook(discordUrl, "Insider Alert", message);
    }

    private static void sendErrorNotification(String errorMessage) {
        String dingTalkUrl = System.getenv("DING_WEBHOOK_URL");
        if (dingTalkUrl != null && !dingTalkUrl.isBlank()) {
            String dingTalkSecret = System.getenv("DING_WEBHOOK_SIGN");
            sendDingTalkWebhook(dingTalkUrl, dingTalkSecret, "Insider Bot Error", errorMessage);
            return;
        }

        String discordUrl = System.getenv("DISCORD_WEBHOOK_URL");
        if (discordUrl != null && !discordUrl.isBlank())
            sendDiscordWebhook(discordUrl, "Insider Bot Error", errorMessage);
    }

    private static boolean sendDingTalkWebhook(String webhookUrl, String secret, String title, String message) {
        try {
            String signedUrl = buildDingTalkUrl(webhookUrl, secret);
            String markdown = "### " + title + "\n\n" + message;
            String payload = "{\"msgtype\":\"markdown\",\"markdown\":{\"title\":\"" + escapeJson(title)
                    + "\",\"text\":\"" + escapeJson(markdown) + "\"}}";

            HttpClient client = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(signedUrl))
                    .timeout(HTTP_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body() == null ? "" : response.body();
            boolean success = response.statusCode() >= 200 && response.statusCode() < 300
                    && body.replace(" ", "").contains("\"errcode\":0");
            if (!success) {
                System.err.println("Warning: DingTalk notification failed. status=" + response.statusCode()
                        + " body=" + body);
            }
            return success;
        } catch (Exception e) {
            System.err.println("Warning: failed to send DingTalk notification: " + e.getMessage());
            return false;
        }
    }

    private static String buildDingTalkUrl(String webhookUrl, String secret) throws Exception {
        if (secret == null || secret.isBlank())
            return webhookUrl;

        long timestamp = System.currentTimeMillis();
        String stringToSign = timestamp + "\n" + secret;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
        String sign = URLEncoder.encode(Base64.getEncoder().encodeToString(signData), StandardCharsets.UTF_8);
        String separator = webhookUrl.contains("?") ? "&" : "?";
        return webhookUrl + separator + "timestamp=" + timestamp + "&sign=" + sign;
    }

    private static boolean sendDiscordWebhook(String webhookUrl, String title, String message) {
        try {
            return sendDiscordMessages(webhookUrl, title, message);
        } catch (Exception e) {
            System.err.println("Warning: failed to send Discord notification: " + e.getMessage());
            return false;
        }
    }

    private static boolean sendDiscordMessages(String webhookUrl, String title, String message) throws Exception {
        String titleLine = "**" + title + "**\n";
        String fullBody = titleLine + message;
        String escapedFull = escapeJson(fullBody);
        if (escapedFull.length() <= 2000) {
            return sendSingleDiscordMessage(webhookUrl, fullBody);
        }

        String[] lines = fullBody.split("\n", -1);
        StringBuilder chunk = new StringBuilder();
        boolean success = true;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String newline = (i < lines.length - 1) ? "\n" : "";
            String candidate = chunk.toString() + line + newline;

            if (escapeJson(candidate).length() <= 2000) {
                chunk.append(line).append(newline);
            } else {
                if (chunk.length() > 0) {
                    if (!sendSingleDiscordMessage(webhookUrl, chunk.toString()))
                        success = false;
                    chunk.setLength(0);
                }
                String newCandidate = line + newline;
                if (escapeJson(newCandidate).length() > 2000) {
                }
                chunk.append(line).append(newline);
            }
        }
        if (chunk.length() > 0) {
            if (!sendSingleDiscordMessage(webhookUrl, chunk.toString()))
                success = false;
        }
        return success;
    }

    private static boolean sendSingleDiscordMessage(String webhookUrl, String content) {
        try {
            String escaped = escapeJson(content);
            String payload = "{\"content\":\"" + escaped + "\"}";
            HttpClient client = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .timeout(HTTP_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception e) {
            System.err.println("Warning: failed to send single Discord message: " + e.getMessage());
            return false;
        }
    }

    private static String escapeJson(String value) {
        if (value == null)
            return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
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
