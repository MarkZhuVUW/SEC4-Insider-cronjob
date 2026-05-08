# SEC4-Insider-cronjob

A simple Java bot that runs on GitHub Actions to check for large insider transactions (>500k USD) for specified US stock tickers.

## Features
<img width="1206" height="2622" alt="bf187b1b39aafa8f2670a86ed5b22870" src="https://github.com/user-attachments/assets/51b30c03-d15c-4f63-be45-b089a182fb4b" />

- Checks SEC Form 4 filings for the previous day
- Filters for purchase (P) and sale (S) transactions over $500,000
- Sends push notifications via Discord webhook when alerts are found
- Runs daily at 9 AM UTC or manually via workflow dispatch

## Setup

1. Fork this repository.
2. Create a Discord webhook:
   - Open your Discord server and go to `Server Settings` → `Integrations` → `Webhooks`.
   - Click `New Webhook` and select a channel where you want alerts to appear.
   - Copy the webhook URL.
3. Add the webhook URL to GitHub secrets:
   - Open your fork on GitHub and go to `Settings` → `Secrets and variables` → `Actions`.
   - Add a new secret named `DISCORD_WEBHOOK_URL`.
   - Paste the webhook URL as the value.
4. (Optional) Configure repo variables for cron defaults:
   - In `Settings` → `Secrets and variables` → `Variables`, add:
     - `TICKERS`: Comma-separated tickers, e.g. `AAPL,GOOGL,MSFT`
     - `THRESHOLD_USD`: Minimum trade size, e.g. `500000`
     - `LOOKBACK_DAYS`: Lookback days, e.g. `1`
   - If not set, defaults are `AAPL,GOOGL`, `500000`, `1`.
5. Run the workflow manually or wait for the daily schedule:
   - Go to the `Actions` tab, choose `Daily Insider Check`, then `Run workflow`.
   - Enter tickers, threshold, and lookback values as needed.

## Usage

- **Manual run**: Go to Actions tab, select "Daily Insider Check", click "Run workflow", enter your desired tickers, threshold, and lookback days. Defaults are provided.
- **Scheduled**: Runs daily automatically using repository variables if set, otherwise uses built-in defaults (`AAPL,GOOGL`, $500k, 1 day lookback).
- **Configuration**: 
  - **Repository Variables** (for cron job defaults): Set `TICKERS`, `THRESHOLD_USD`, `LOOKBACK_DAYS` in repo Settings → Variables
  - **GitHub secret**: Set `DISCORD_WEBHOOK_URL` in Settings → Secrets and variables → Actions
  - **CLI options**: For local testing or custom runs
  - **Ticker format**: `BRKB` or `BRK-B` are supported; `BRK.B` is not supported. Ticker input is case-insensitive.

### Example local CLI commands

```bash
mvn exec:java -Dexec.args="--tickers=AAPL,GOOGL,MSFT --threshold=500000 --lookback=7"
```

```bash
mvn exec:java -Dexec.args="AAPL,GOOGL,MSFT --threshold=1000000 --lookback=3"
```

```bash
mvn exec:java -Dexec.args="--tickers=ZTS --threshold=500000 --lookback=1 --mock=true"
```

- If `DISCORD_WEBHOOK_URL` is not set, the bot logs alerts to the Actions console instead of failing.
- Push notifications include ticker, owner, position, action, security, shares, price, and amount on separate lines.

## Requirements

- Java 21
- Maven
- GitHub Actions

## Notes

- Only checks non-derivative transactions (P/S codes)
- Excludes awards (A) and exercises (M) to avoid RSU-related transactions
- Data from SEC EDGAR, subject to their terms
