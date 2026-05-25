# SEC4-Insider-cronjob

A Java bot that runs on GitHub Actions to check for large insider transactions (>500k USD by default) for specified US stock tickers, and posts grouped alerts to Discord.

## Features

<img width="1206" height="2622" alt="124c8c6a6c8b33514828527908d2c2d4" src="https://github.com/user-attachments/assets/07e4b9ec-45d8-4c35-ae86-306cd23f52ad" />

- Checks SEC Form 4 filings within a configurable lookback window
- Filters non-derivative purchase (P) and sale (S) transactions over a configurable threshold
- Excludes awards (A) and option exercises (M) to avoid RSU-related noise
- Sends grouped, color-coded notifications via Discord webhook
- Runs **twice daily** on schedule (04:00 and 16:00 Auckland time), or manually via workflow dispatch
- edit and save `TICKERS` / `THRESHOLD` / `LOOKBACK` directly from the "Run workflow" button

## Setup

1. Fork this repository.
2. Create a Discord webhook:
   - In Discord: `Server Settings` → `Integrations` → `Webhooks` → `New Webhook`.
   - Pick the channel for alerts and copy the webhook URL.
3. Add repository secrets under `Settings` → `Secrets and variables` → `Actions`:
   - `DISCORD_WEBHOOK_URL` — your Discord webhook URL.
   - `PAT` — a **Personal Access Token** with `repo` scope (Fine-grained: `Secrets: read & write` on this repository). Required only if you want to update the three secrets above from the workflow UI.

## Usage

### Scheduled runs

Runs automatically at **04:00 and 16:00 NZST** (Auckland time) every day, using the values in `TICKERS`, `THRESHOLD`, and `LOOKBACK` variables.

### Manual run

Go to the `Actions` tab → `Daily Insider Check` → `Run workflow`. You'll see four inputs:

| Input | Purpose |
|---|---|
| `update_secrets` (checkbox) | If **checked**, the workflow first overwrites the `TICKERS` / `THRESHOLD` / `LOOKBACK` secrets with whatever you typed below (blank fields keep their existing secret value), then runs the check using the new values. If **unchecked**, secrets are not modified — any value you type is used **only for this one run**. |
| `tickers` | Tickers to check (comma-separated). |
| `threshold` | USD threshold for this run or for the new secret value. |
| `lookback` | Lookback days for this run or for the new secret value. |

Secret update only proceeds if `PAT` is set. The main check job runs only after the secret-update step succeeds (or is skipped), so a partial update never leaves you in a broken state.

### Ticker format

`BRKB` or `BRK-B` are supported; `BRK.B` is not. Input is case-insensitive.

## Notification format

Each Discord alert begins with a header showing the run date (local Auckland date), followed by one color-coded `diff` block per ticker:
