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
3. Install ntfy app on your phone and subscribe to your topic
4. Run the workflow manually or wait for daily schedule

## Usage

- **Manual run**: Go to Actions tab, select "Daily Insider Check", click "Run workflow", enter comma-separated tickers (e.g., AAPL,GOOGL,MSFT).
- **Scheduled**: Runs daily automatically with default tickers (`AAPL,GOOGL`) from the workflow. Update `.github/workflows/daily-check.yml` to change the default list.
- **Configuration**: The bot also supports the following environment variables:
  - `TICKERS` — comma-separated ticker list.
  - `THRESHOLD_USD` — minimum trade size, default `500000`.
  - `LOOKBACK_DAYS` — how many recent days to search back to find a valid SEC daily index when yesterday is unavailable, default `1`.
    - This does not mean the bot searches 1 day of trades.
    - It only finds the latest available SEC index when yesterday is missing.
    - If you run the bot every trading day, `LOOKBACK_DAYS=1` is usually the right setting.
    - If you run on Monday or after a holiday, increase this to `3` so it can find the prior trading day.
- **CLI usage**: In GitHub Actions or local run, you can also pass CLI options:
  - `--tickers=AAPL,GOOGL,MSFT`
  - `--threshold=500000`
  - `--lookback=7`
  - `--debug=false` (disable debug output)
  - `--mock=true` (use mock data for testing, ignores SEC downloads)

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