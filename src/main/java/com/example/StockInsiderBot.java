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
import java.time.ZoneId;
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

            Map<String, List<AlertEntry>> allRawAlerts = new LinkedHashMap<>();
            Set<String> tickersWithForm4 = new HashSet<>();
            int processedCount = 0;
            int failedCount = 0;
            for (String url : form4Urls) {
                try {
                    String xml = downloadText(url);
                    logDebug("Processing Form 4 URL: " + url);
                    Map<String, List<AlertEntry>> parsed = parseForm4(xml, cikToRequestedTicker);
                    parsed.forEach((ticker, alerts) -> {
                        tickersWithForm4.add(ticker);
                        if (!alerts.isEmpty()) {
                            allRawAlerts.computeIfAbsent(ticker, k -> new ArrayList<>()).addAll(alerts);
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

            Map<String, List<AlertEntry>> aggregatedAlerts = aggregateAlerts(allRawAlerts, minimumUsd);

            Map<String, List<AlertEntry>> filteredAlerts = new LinkedHashMap<>();
            for (String ticker : tickers) {
                if (aggregatedAlerts.containsKey(ticker) && !aggregatedAlerts.get(ticker).isEmpty()) {
                    filteredAlerts.put(ticker, aggregatedAlerts.get(ticker));
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
            System.out.println("Found " + filteredAlerts.values().stream().mapToInt(List::size).sum() + " alert(s) in " +
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

    // ---------- AlertEntry ----------
    private static class AlertEntry {
        final String ownerName;
        final String position;
        final String type;
        final long shares;
        final double price;
        final double amount;
        final boolean is10b51;
        final String transactionDate;
        final long sharesOwnedAfter;
        final double buyAmount;
        final double sellAmount;
        final double netAmount;
        final long buyShares;
        final long sellShares;
        final long netShares;

        AlertEntry(String ownerName, String position, String type,
                long shares, double price, double amount, boolean is10b51,
                String transactionDate, long sharesOwnedAfter,
                double buyAmount, double sellAmount, double netAmount,
                long buyShares, long sellShares, long netShares) {
            this.ownerName = ownerName;
            this.position = position;
            this.type = type;
            this.shares = shares;
            this.price = price;
            this.amount = amount;
            this.is10b51 = is10b51;
            this.transactionDate = transactionDate;
            this.sharesOwnedAfter = sharesOwnedAfter;
            this.buyAmount = buyAmount;
            this.sellAmount = sellAmount;
            this.netAmount = netAmount;
            this.buyShares = buyShares;
            this.sellShares = sellShares;
            this.netShares = netShares;
        }
    }

    // ---------- 聚合：同人同日合并，总交易额大于阈值 ----------
    private static Map<String, List<AlertEntry>> aggregateAlerts(Map<String, List<AlertEntry>> rawAlerts,
            long threshold) {
        Map<String, List<AlertEntry>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<AlertEntry>> entry : rawAlerts.entrySet()) {
            String ticker = entry.getKey();
            List<AlertEntry> list = entry.getValue();

            Map<String, Map<String, List<AlertEntry>>> dateOwnerMap = new LinkedHashMap<>();
            for (AlertEntry e : list) {
                dateOwnerMap.computeIfAbsent(e.transactionDate, k -> new LinkedHashMap<>())
                        .computeIfAbsent(e.ownerName, k -> new ArrayList<>())
                        .add(e);
            }

            List<AlertEntry> aggregated = new ArrayList<>();
            for (Map.Entry<String, Map<String, List<AlertEntry>>> dateEntry : dateOwnerMap.entrySet()) {
                for (Map.Entry<String, List<AlertEntry>> ownerEntry : dateEntry.getValue().entrySet()) {
                    List<AlertEntry> trades = ownerEntry.getValue();

                    double totalBuyAmount = 0, totalSellAmount = 0;
                    long totalBuyShares = 0, totalSellShares = 0;
                    String position = trades.get(0).position;
                    long lastOwnedAfter = trades.get(trades.size() - 1).sharesOwnedAfter;
                    boolean allPlan = trades.stream().allMatch(e -> e.is10b51);

                    for (AlertEntry t : trades) {
                        totalBuyAmount += t.buyAmount;
                        totalSellAmount += t.sellAmount;
                        totalBuyShares += t.buyShares;
                        totalSellShares += t.sellShares;
                    }

                    double totalAmount = totalBuyAmount + totalSellAmount;
                    if (totalAmount < threshold) continue;

                    double netAmount = totalBuyAmount - totalSellAmount;
                    long netShares = totalBuyShares - totalSellShares;
                    String type = netAmount > 0 ? "BUY" : "SELL";
                    double weightPrice;
                    long displayShares;
                    if (netAmount > 0) {
                        weightPrice = totalBuyShares > 0 ? totalBuyAmount / totalBuyShares : 0;
                        displayShares = totalBuyShares;
                    } else {
                        weightPrice = totalSellShares > 0 ? totalSellAmount / totalSellShares : 0;
                        displayShares = totalSellShares;
                    }

                    aggregated.add(new AlertEntry(
                            ownerEntry.getKey(), position, type,
                            displayShares, weightPrice, totalAmount, allPlan,
                            dateEntry.getKey(), lastOwnedAfter,
                            totalBuyAmount, totalSellAmount, netAmount,
                            totalBuyShares, totalSellShares, netShares));
                }
            }
            if (!aggregated.isEmpty()) result.put(ticker, aggregated);
        }
        return result;
    }

// ---------- 通知构建 ----------
private static String buildGroupedNotification(Map<String, List<AlertEntry>> alertsByTicker, String indexDate) {
    StringBuilder msg = new StringBuilder();
    String today = LocalDate.now(ZoneId.of("Pacific/Auckland"))
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    msg.append("🔔 **Insider Alerts** · ").append(today).append("\n");

    for (Map.Entry<String, List<AlertEntry>> entry : alertsByTicker.entrySet()) {
        String ticker = entry.getKey();
        for (AlertEntry e : entry.getValue()) {
            // 日期：去掉年份，只留 MM-DD
            String date = e.transactionDate.isEmpty() ? "N/A"
                    : (e.transactionDate.length() >= 10
                            ? e.transactionDate.substring(5)
                            : e.transactionDate);

            String plan = e.is10b51 ? " 📋" : "";
            String position = (e.position == null || e.position.isBlank()
                    || "Unknown Position".equals(e.position)) ? "" : "·" + e.position;

            // 变动百分比 (带正负号)
            long beforeShares = e.sharesOwnedAfter - e.netShares;
            String pctStr = "";
            if (beforeShares > 0) {
                double pct = Math.abs(e.netShares) * 100.0 / beforeShares;
                if (pct > 0) {
                    double signed = e.netAmount >= 0 ? pct : -pct;
                    pctStr = String.format("%+.1f%%", signed);
                }
            }
            String pctTail = pctStr.isEmpty() ? "" : "·" + pctStr;

            // 持仓
            String holdingStr;
            if (e.sharesOwnedAfter > 0) {
                double hv = e.sharesOwnedAfter * e.price;
                holdingStr = String.format("持 %s≈%s",
                        formatNumber(e.sharesOwnedAfter), formatAmount(hv));
            } else {
                holdingStr = "持 N/A";
            }

            // 主行 & 可选 buy/sell 明细
            String mainLine;
            String tradeDetail = null;
            boolean isMixed = e.buyShares > 0 && e.sellShares > 0;

            if (isMixed) {
                String dirEmoji = e.netAmount > 0 ? "🟢" : (e.netAmount < 0 ? "🔴" : "⚪");
                String dirText  = (e.netAmount != 0) ? "净" : "";
                String netSign  = e.netShares > 0 ? "+" : (e.netShares < 0 ? "-" : "");
                mainLine = String.format("%s%s %s·%s%s%s",
                        dirEmoji, dirText, formatAmount(Math.abs(e.netAmount)),
                        netSign, formatNumber(Math.abs(e.netShares)), pctTail);
                tradeDetail = String.format("买 %s(%s) 卖 %s(%s)",
                        formatNumber(e.buyShares),  formatAmount(e.buyAmount),
                        formatNumber(e.sellShares), formatAmount(e.sellAmount));
            } else if (e.buyShares > 0) {
                mainLine = String.format("🟢 %s·%s@$%.0f%s",
                        formatAmount(e.buyAmount), formatNumber(e.buyShares), e.price, pctTail);
            } else {
                mainLine = String.format("🔴 %s·%s@$%.0f%s",
                        formatAmount(e.sellAmount), formatNumber(e.sellShares), e.price, pctTail);
            }

            // 颜色前缀: 主动买=绿(+) / 主动卖=红(-) / 10b5-1=灰(空格)
            String p;
            if (e.is10b51)                  p = " ";
            else if ("BUY".equals(e.type))  p = "+";
            else                            p = "-";

            msg.append("```diff\n");
            msg.append(p).append(' ').append(ticker)
               .append('·').append(e.ownerName).append(position).append(plan).append('\n');
            msg.append(p).append(' ').append(mainLine).append('\n');
            if (tradeDetail != null) {
                msg.append(p).append(' ').append(tradeDetail).append('\n');
            }
            msg.append(p).append(' ').append(holdingStr)
               .append('·').append(date).append('\n');
            msg.append("```\n");
        }
    }
    return msg.toString().trim();
}
    // ---------- Form 4 解析 ----------
    private static Map<String, List<AlertEntry>> parseForm4(String xml,
            Map<String, String> cikToRequestedTicker) throws Exception {
        Map<String, List<AlertEntry>> alerts = new LinkedHashMap<>();
        String xmlPayload = extractXmlPayload(xml);
        if (xmlPayload.isBlank()) return alerts;

        XmlMapper mapper = new XmlMapper();
        JsonNode root = mapper.readTree(xmlPayload);
        JsonNode issuer = root.path("issuer");
        String rawXmlCik = issuer.path("issuerCik").asText(issuer.path("issuerCIK").asText("Unknown"));
        String normalizedXmlCik = rawXmlCik.replaceFirst("^0+(?!$)", "");
        String ticker = cikToRequestedTicker.getOrDefault(normalizedXmlCik,
                issuer.path("issuerTradingSymbol").asText("Unknown"));

        JsonNode reportingOwner = root.path("reportingOwner");
        if (!isOfficerOrDirector(reportingOwner)) {
            logDebug("Skipping " + ticker + " - not an officer/director");
            return alerts;
        }
        String ownerName = reportingOwner.path("reportingOwnerId").path("rptOwnerName").asText("Unknown Owner");
        String position = extractPosition(reportingOwner);

        // 非衍生品表
        JsonNode nonDeriv = root.path("nonDerivativeTable");
        if (nonDeriv.isMissingNode()) nonDeriv = root.path("ownershipDocument").path("nonDerivativeTable");
        if (!nonDeriv.isMissingNode()) {
            JsonNode nonTrans = nonDeriv.path("nonDerivativeTransaction");
            if (!nonTrans.isMissingNode()) {
                Iterable<JsonNode> txList = nonTrans.isArray() ? nonTrans : Collections.singletonList(nonTrans);
                for (JsonNode tx : txList) {
                    AlertEntry entry = processTransaction(tx, ownerName, position, root);
                    if (entry != null) alerts.computeIfAbsent(ticker, k -> new ArrayList<>()).add(entry);
                }
            }
        }

        // 衍生品表
        JsonNode derivTable = root.path("derivativeTable");
        if (derivTable.isMissingNode()) derivTable = root.path("ownershipDocument").path("derivativeTable");
        if (!derivTable.isMissingNode()) {
            JsonNode derivTrans = derivTable.path("derivativeTransaction");
            if (!derivTrans.isMissingNode()) {
                Iterable<JsonNode> txList = derivTrans.isArray() ? derivTrans : Collections.singletonList(derivTrans);
                for (JsonNode tx : txList) {
                    AlertEntry entry = processTransaction(tx, ownerName, position, root);
                    if (entry != null) alerts.computeIfAbsent(ticker, k -> new ArrayList<>()).add(entry);
                }
            }
        }

        return alerts;
    }

    private static AlertEntry processTransaction(JsonNode transaction, String ownerName, String position,
            JsonNode docRoot) {
        String code = transaction.path("transactionCoding").path("transactionCode").asText();
        if (!"P".equals(code) && !"S".equals(code)) return null;

        if (!transaction.path("exerciseDate").isMissingNode() &&
                !transaction.path("exerciseDate").asText().isBlank()) return null;

        if (isSellToCover(transaction, docRoot)) return null;

        long shares = extractLong(transaction, "transactionAmounts.transactionShares");
        double price = extractDouble(transaction, "transactionAmounts.transactionPricePerShare");
        if (shares <= 0 || price <= 0) return null;

        double amount = shares * price;
        String type = "P".equals(code) ? "BUY" : "SELL";
        boolean isPlan = "true".equalsIgnoreCase(
                transaction.path("transactionCoding").path("is10b51Transaction").asText())
                || "1".equals(transaction.path("transactionCoding").path("is10b51Transaction").asText());

        String transactionDate = extractText(transaction, "transactionDate", "");
        if (!transactionDate.isEmpty() && transactionDate.length() >= 10)
            transactionDate = transactionDate.substring(0, 10);

        long sharesOwnedAfter = extractLong(transaction, "postTransactionAmounts.sharesOwnedFollowingTransaction");
        if (sharesOwnedAfter <= 0)
            sharesOwnedAfter = extractLong(transaction, "sharesOwnedFollowingTransaction");

        double buyAmount = "BUY".equals(type) ? amount : 0;
        double sellAmount = "SELL".equals(type) ? amount : 0;
        long buyShares = "BUY".equals(type) ? shares : 0;
        long sellShares = "SELL".equals(type) ? shares : 0;

        return new AlertEntry(ownerName, position, type,
                shares, price, amount, isPlan,
                transactionDate, sharesOwnedAfter,
                buyAmount, sellAmount, buyAmount - sellAmount,
                buyShares, sellShares, buyShares - sellShares);
    }

    // ---------- Sell-to-cover 检测 ----------
    private static boolean isSellToCover(JsonNode transaction, JsonNode docRoot) {
        String[] paths = {"footnote", "footnotes", "remarks", "transactionText", "explanatoryText"};
        for (String p : paths) {
            if (containsSellToCoverText(extractText(transaction, p, ""))) return true;
        }

        Set<String> referencedIds = new HashSet<>();
        collectFootnoteIds(transaction, referencedIds);
        logDebug("footnoteIds in tx: " + referencedIds);

        if (!referencedIds.isEmpty() && docRoot != null) {
            JsonNode footnotesNode = docRoot.path("footnotes");
            if (!footnotesNode.isMissingNode()) {
                JsonNode fnNode = footnotesNode.path("footnote");
                Iterable<JsonNode> fnList = fnNode.isArray() ? fnNode : Collections.singletonList(fnNode);
                for (JsonNode fn : fnList) {
                    String id = fn.path("id").asText(fn.path("_id").asText(""));
                    String text = fn.path("").asText(fn.asText(""));
                    logDebug("Checking footnote " + id + " [" + text.substring(0, Math.min(80, text.length())) + "]");
                    if (referencedIds.contains(id) && containsSellToCoverText(text)) return true;
                }
            }
        }
        return false;
    }

    private static boolean containsSellToCoverText(String raw) {
        String t = raw.toLowerCase(Locale.ROOT);
        return t.contains("sell to cover") || t.contains("sell-to-cover")
                || t.contains("tax withholding") || t.contains("satisfy tax")
                || t.contains("satisfy withholding") || t.contains("withhold")
                || t.contains("tax obligation") || t.contains("net settlement")
                || (t.contains("rsu") && (t.contains("tax") || t.contains("vest")));
    }

    private static void collectFootnoteIds(JsonNode node, Set<String> ids) {
        if (node == null || node.isMissingNode()) return;
        if (node.isObject()) {
            JsonNode fnId = node.path("footnoteId");
            if (!fnId.isMissingNode()) {
                String id = fnId.path("id").asText(fnId.path("_id").asText(fnId.asText("")));
                if (!id.isBlank()) ids.add(id);
            }
            node.fields().forEachRemaining(e -> collectFootnoteIds(e.getValue(), ids));
        } else if (node.isArray()) {
            node.forEach(child -> collectFootnoteIds(child, ids));
        }
    }

    // ---------- 辅助方法 ----------
    private static boolean isTruthy(JsonNode node) {
        if (node == null || node.isMissingNode()) return false;
        String direct = node.asText("");
        if ("true".equalsIgnoreCase(direct) || "1".equals(direct)) return true;
        String val = node.path("value").asText("");
        return "true".equalsIgnoreCase(val) || "1".equals(val);
    }

    private static boolean isOfficerOrDirector(JsonNode reportingOwner) {
        JsonNode rel = reportingOwner.path("reportingOwnerRelationship");
        if (rel.isMissingNode()) return false;
        return isTruthy(rel.path("isDirector")) || isTruthy(rel.path("isOfficer"));
    }

    private static String extractPosition(JsonNode reportingOwner) {
        JsonNode rel = reportingOwner.path("reportingOwnerRelationship");
        List<String> titles = new ArrayList<>();
        appendIfPresent(rel, "officerTitle", titles);
        appendIfPresent(rel, "directorTitle", titles);
        appendIfPresent(rel, "otherTitle", titles);
        if (!titles.isEmpty()) return String.join(", ", titles);
        String alt = rel.path("relationshipTitle").asText();
        if (!alt.isBlank()) return alt;
        alt = reportingOwner.path("reportingOwnerId").path("rptOwnerTitle").asText();
        return alt.isBlank() ? "Unknown Position" : alt;
    }

    private static void appendIfPresent(JsonNode rel, String field, List<String> titles) {
        JsonNode node = rel.path(field);
        if (!node.isMissingNode() && !node.asText().isBlank()) titles.add(node.asText().trim());
    }

    private static long extractLong(JsonNode root, String path) {
        JsonNode node = nodeAt(root, path);
        if (node.isNumber()) return node.asLong(0);
        if (node.isTextual() && !node.asText().isBlank()) return parseLongSafely(node.asText());
        JsonNode val = node.path("value");
        if (!val.isMissingNode() && !val.isNull()) {
            if (val.isNumber()) return val.asLong(0);
            if (val.isTextual() && !val.asText().isBlank()) return parseLongSafely(val.asText());
        }
        return 0;
    }

    private static double extractDouble(JsonNode root, String path) {
        JsonNode node = nodeAt(root, path);
        if (node.isNumber()) return node.asDouble(0.0);
        if (node.isTextual() && !node.asText().isBlank()) return parseDoubleSafely(node.asText());
        JsonNode val = node.path("value");
        if (!val.isMissingNode() && !val.isNull()) {
            if (val.isNumber()) return val.asDouble(0.0);
            if (val.isTextual() && !val.asText().isBlank()) return parseDoubleSafely(val.asText());
        }
        return 0.0;
    }

    private static String extractText(JsonNode root, String path, String fallback) {
        JsonNode node = nodeAt(root, path);
        if (!node.isMissingNode() && !node.asText().isBlank()) return node.asText();
        JsonNode val = node.path("value");
        if (!val.isMissingNode() && !val.asText().isBlank()) return val.asText();
        return fallback;
    }

    private static JsonNode nodeAt(JsonNode root, String path) {
        JsonNode node = root;
        for (String part : path.split("\\.")) node = node.path(part);
        return node;
    }

    private static long parseLongSafely(String text) {
        try { return (long) Double.parseDouble(text.replaceAll("[^0-9.\\-]", "")); }
        catch (NumberFormatException e) { return 0; }
    }

    private static double parseDoubleSafely(String text) {
        try { return Double.parseDouble(text.replaceAll("[^0-9.\\-]", "")); }
        catch (NumberFormatException e) { return 0.0; }
    }

    private static String formatNumber(long num) {
        if (num >= 1_000_000) return String.format("%.1fM", num / 1_000_000.0);
        if (num >= 1_000) return String.format("%.1fK", num / 1_000.0);
        return Long.toString(num);
    }

    private static String formatAmount(double amount) {
        if (amount >= 1_000_000) return String.format("$%.1fM", amount / 1_000_000.0);
        if (amount >= 1_000) return String.format("$%.1fK", amount / 1_000.0);
        return String.format("$%.0f", amount);
    }

    // ---------- 网络 / 通知 ----------
    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new HashMap<>();
        String positional = null;
        for (String arg : args) {
            if (arg == null || arg.isBlank()) continue;
            if (arg.startsWith("--")) {
                String normalized = arg.substring(2);
                String[] parts = normalized.split("=", 2);
                if (parts.length == 2) options.put(parts[0].toLowerCase(Locale.ROOT), parts[1]);
                else options.put(parts[0].toLowerCase(Locale.ROOT), "true");
            } else if (positional == null) positional = arg;
        }
        options.put("positional", positional);
        return options;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private static boolean debugEnabled = DEFAULT_DEBUG;
    private static void setDebug(boolean enabled) { debugEnabled = enabled; }
    private static void logDebug(String msg) { if (debugEnabled) System.out.println("DEBUG: " + msg); }

    private static long parseLong(String value, long fallback) {
        try { if (value != null && !value.isBlank()) return Long.parseLong(value.trim()); }
        catch (NumberFormatException ignored) {}
        return fallback;
    }

    private static int parseInt(String value, int fallback) {
        try { if (value != null && !value.isBlank()) return Integer.parseInt(value.trim()); }
        catch (NumberFormatException ignored) {}
        return fallback;
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        if (value == null || value.isBlank()) return fallback;
        String v = value.trim().toLowerCase(Locale.ROOT);
        return !(v.equals("false") || v.equals("0") || v.equals("no") || v.equals("off"));
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
                    if (parts.length == 2) map.put(parts[0].toUpperCase(Locale.ROOT), parts[1]);
                }
            }
        } catch (Exception e) {}
        if (map.isEmpty()) map.putAll(FALLBACK_TICKER_MAP);
        return map;
    }

    private static String findCikForTicker(String ticker, Map<String, String> tickerToCik) {
        String clean = ticker.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (clean.isBlank()) return null;
        for (Map.Entry<String, String> e : tickerToCik.entrySet())
            if (e.getKey().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "").equals(clean)) return e.getValue();
        for (Map.Entry<String, String> e : FALLBACK_TICKER_MAP.entrySet())
            if (e.getKey().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "").equals(clean)) return e.getValue();
        return null;
    }

    private static MasterIndex findMasterIndex(LocalDate startDate, int maxLookbackDays) {
        LocalDate date = startDate;
        StringBuilder content = new StringBuilder();
        LocalDate found = null;
        for (int i = 0; i < maxLookbackDays; i++) {
            String dateStr = date.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String url = SEC_BASE + "edgar/daily-index/" + date.getYear() + "/QTR" +
                    ((date.getMonthValue() - 1) / 3 + 1) + "/master." + dateStr + ".idx";
            try {
                String idx = downloadText(url);
                if (idx != null && !idx.isBlank()) {
                    content.append(idx);
                    if (found == null) found = date;
                }
            } catch (Exception ignored) {}
            date = date.minusDays(1);
        }
        return content.length() > 0 && found != null
                ? new MasterIndex(found.format(DateTimeFormatter.ofPattern("yyyyMMdd")), content.toString())
                : null;
    }

    private static String downloadText(String url) throws Exception {
        Exception lastEx = null;
        for (int i = 0; i < 3; i++) {
            try (CloseableHttpClient client = HttpClients.createDefault()) {
                HttpGet get = new HttpGet(url);
                get.setHeader("User-Agent", firstNonBlank(System.getenv("SEC_USER_AGENT"), DEFAULT_SEC_USER_AGENT));
                get.setHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                get.setHeader("From", firstNonBlank(System.getenv("SEC_CONTACT_EMAIL"), DEFAULT_SEC_CONTACT_EMAIL));
                try (ClassicHttpResponse resp = client.execute(get)) {
                    if (resp.getCode() == HttpStatus.SC_OK) return EntityUtils.toString(resp.getEntity());
                    else if (resp.getCode() == 403 || resp.getCode() == 404) throw new Exception("HTTP " + resp.getCode());
                    else lastEx = new Exception("HTTP " + resp.getCode());
                }
            } catch (Exception e) {
                lastEx = e;
                if (i < 2) Thread.sleep(1000);
            }
        }
        throw lastEx != null ? lastEx : new Exception("Failed to download " + url);
    }

    private static List<String> parseMasterIdx(String content, Set<String> ciks) {
        Set<String> urls = new LinkedHashSet<>();
        for (String line : content.split("\\R")) {
            if (line.isBlank() || line.startsWith("CIK|") || line.startsWith("-----")) continue;
            String[] parts = line.split("\\|", 6);
            if (parts.length < 5) continue;
            String cik = parts[0].trim().replaceFirst("^0+(?!$)", "");
            if (ciks.contains(cik) && parts[2].trim().startsWith("4"))
                urls.add(SEC_BASE + parts[4].trim());
        }
        return new ArrayList<>(urls);
    }

    private static List<String> fetchForm4UrlsFromEdgarBrowse(Set<String> ciks, int maxDays) {
        Set<String> urls = new LinkedHashSet<>();
        for (String cik : ciks) {
            try {
                String atom = downloadText("https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK="
                        + cik + "&type=4&owner=include&count=100&output=atom");
                if (atom != null) urls.addAll(parseBrowseEdgarAtom(atom, maxDays));
            } catch (Exception e) {}
        }
        return new ArrayList<>(urls);
    }

    private static List<String> parseBrowseEdgarAtom(String atom, int maxDays) {
        Set<String> urls = new LinkedHashSet<>();
        LocalDate threshold = LocalDate.now().minusDays(maxDays);
        Pattern entry = Pattern.compile("<entry>(.*?)</entry>", Pattern.DOTALL);
        Pattern datePat = Pattern.compile("<filing-date>(.*?)</filing-date>");
        Pattern hrefPat = Pattern.compile("<filing-href>(.*?)</filing-href>");
        Matcher m = entry.matcher(atom);
        while (m.find()) {
            String e = m.group(1);
            Matcher dm = datePat.matcher(e);
            Matcher hm = hrefPat.matcher(e);
            if (dm.find() && hm.find()) {
                try {
                    LocalDate d = LocalDate.parse(dm.group(1).trim());
                    if (!d.isBefore(threshold)) {
                        String xmlUrl = findForm4XmlUrlFromIndexPage(hm.group(1).trim());
                        if (xmlUrl != null) urls.add(xmlUrl);
                    }
                } catch (Exception ignored) {}
            }
        }
        return new ArrayList<>(urls);
    }

    private static String findForm4XmlUrlFromIndexPage(String indexUrl) {
        try {
            String html = downloadText(indexUrl);
            Matcher m = Pattern.compile("href=\"([^\"]*?/form4\\.xml)\"", Pattern.CASE_INSENSITIVE).matcher(html);
            String best = null;
            while (m.find()) {
                String rel = m.group(1);
                String full = rel.startsWith("http") ? rel : "https://www.sec.gov" + rel;
                if (!rel.toLowerCase().contains("xslf345")) return full;
                if (best == null) best = full;
            }
            return best;
        } catch (Exception e) { return null; }
    }

    private static String extractXmlPayload(String raw) {
        if (raw == null) return "";
        String clean = "";
        int start = raw.indexOf("<XML>");
        if (start >= 0) {
            int end = raw.indexOf("</XML>", start);
            if (end > start) clean = raw.substring(start + 5, end);
        }
        if (clean.isBlank()) {
            Matcher m = Pattern.compile("<ownershipDocument[^>]*>.*?</ownershipDocument>", Pattern.DOTALL).matcher(raw);
            if (m.find()) clean = m.group();
        }
        clean = clean.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "")
                .replaceAll("&(?!(amp|apos|quot|lt|gt|#\\d+);)", "&amp;")
                .replaceAll("</\\s+", "</")
                .replaceAll("<\\s+(?=[a-zA-Z_/?!])", "<");
        return clean.trim();
    }

    private static boolean sendNotification(String msg) {
        String discord = System.getenv("DISCORD_WEBHOOK_URL");
        if (discord == null || discord.isBlank()) return false;
        return sendDiscordWebhook(discord, "Insider Alert", msg);
    }

    private static void sendErrorNotification(String err) {
        String discord = System.getenv("DISCORD_WEBHOOK_URL");
        if (discord != null && !discord.isBlank()) sendDiscordWebhook(discord, "Insider Bot Error", err);
    }

    private static boolean sendDiscordWebhook(String url, String title, String msg) {
        try { return sendDiscordMessages(url, title, msg); }
        catch (Exception e) { return false; }
    }

    private static boolean sendDiscordMessages(String url, String title, String msg) throws Exception {
        String header = "**" + title + "**\n";
        String full = header + msg;

        if (escapeJson(full).length() <= 1990) return sendSingle(url, full);

        List<String> blocks = splitIntoBlocks(msg);
        StringBuilder chunk = new StringBuilder(header);
        boolean ok = true;

        for (String block : blocks) {
            String candidate = chunk + block + "\n";
            if (escapeJson(candidate).length() > 1990 && chunk.length() > header.length()) {
                if (!sendSingle(url, chunk.toString().stripTrailing())) ok = false;
                chunk = new StringBuilder(block).append("\n");
            } else {
                chunk.append(block).append("\n");
            }
        }
        if (chunk.length() > 0 && !sendSingle(url, chunk.toString().stripTrailing())) ok = false;
        return ok;
    }

    private static List<String> splitIntoBlocks(String msg) {
        List<String> blocks = new ArrayList<>();
        Pattern p = Pattern.compile("```diff\\n[\\s\\S]*?```", Pattern.MULTILINE);
        Matcher m = p.matcher(msg);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {
                String text = msg.substring(last, m.start()).strip();
                if (!text.isEmpty()) blocks.add(text);
            }
            String block = m.group();
            if (escapeJson(block).length() > 1990)
                block = block.replaceAll("```diff\\n[+\\- ]?", "").replace("```", "").strip();
            blocks.add(block);
            last = m.end();
        }
        if (last < msg.length()) {
            String text = msg.substring(last).strip();
            if (!text.isEmpty()) blocks.add(text);
        }
        return blocks;
    }

    private static boolean sendSingle(String url, String content) {
        try {
            String json = "{\"content\":\"" + escapeJson(content) + "\"}";
            HttpClient client = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(HTTP_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() >= 200 && resp.statusCode() < 300;
        } catch (Exception e) { return false; }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String buildMissingNotification(String[] tickers, String reason) {
        StringBuilder sb = new StringBuilder("🔔 Insider Alerts\n\n");
        for (String t : tickers) sb.append("▶ ").append(t).append("\n  ").append(reason).append("\n\n");
        return sb.toString().trim();
    }

    private static class MasterIndex {
        final String indexDate;
        final String content;
        MasterIndex(String d, String c) { indexDate = d; content = c; }
    }
}
