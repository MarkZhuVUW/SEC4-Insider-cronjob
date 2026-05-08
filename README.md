# SEC4-Insider-cronjob

A simple Java bot that runs on GitHub Actions to check for large insider transactions (>500k USD) for specified US stock tickers.

## Features

- Checks SEC Form 4 filings for the previous day
- Filters for purchase (P) and sale (S) transactions over $500,000
- Sends push notifications via ntfy.sh when alerts are found
- Runs daily at 9 AM UTC or manually via workflow dispatch

## Setup

1. Fork this repository
2. Set up ntfy.sh topic:
   - Go to https://ntfy.sh and create a topic (e.g., `mystockalerts`)
   - In your repo settings, add a secret `NTFY_TOPIC` with your topic name
3. **Configure repository variables** (optional, for cron job defaults):
   - Go to your repository Settings → Secrets and variables → Variables
   - Add variables:
     - `TICKERS`: Comma-separated list of tickers (e.g., `AAPL,GOOGL,MSFT`)
     - `THRESHOLD_USD`: Minimum trade size (e.g., `500000`)
     - `LOOKBACK_DAYS`: Days to search back (e.g., `1`)
   - If not set, cron job uses built-in defaults: `AAPL,GOOGL`, `500000`, `1`
4. Install ntfy app on your phone and subscribe to your topic
5. Run the workflow manually or wait for daily schedule

## Usage

- **Manual run**: Go to Actions tab, select "Daily Insider Check", click "Run workflow", enter your desired tickers, threshold, and lookback days. Defaults are provided.
- **Scheduled**: Runs daily automatically using repository variables if set, otherwise uses built-in defaults (`AAPL,GOOGL`, $500k, 1 day lookback).
- **Configuration**: 
  - **Repository Variables** (for cron job defaults): Set `TICKERS`, `THRESHOLD_USD`, `LOOKBACK_DAYS` in repo Settings → Variables
  - **Environment Variables**: Can also be overridden via env vars in workflow
  - **CLI options**: For local testing or custom runs

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

- If `NTFY_TOPIC` is not set, the bot logs alerts to the Actions console instead of failing.
- Push notifications include ticker, owner, position, action, security, shares, price, and amount on separate lines.

## Requirements

- Java 21
- Maven
- GitHub Actions

## Notes

- Only checks non-derivative transactions (P/S codes)
- Excludes awards (A) and exercises (M) to avoid RSU-related transactions
- Data from SEC EDGAR, subject to their terms