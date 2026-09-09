# Provider integration notes

CodeMeter talks directly to subscription-usage endpoints from the Android client. These endpoints and first-party OAuth client identifiers are not stable public third-party contracts, so provider HTTP/parsing logic is intentionally isolated.

## Claude Code

### OAuth

- Authorization: `https://claude.com/cai/oauth/authorize`
- Token/refresh: `https://platform.claude.com/v1/oauth/token`
- Usage: `GET https://api.anthropic.com/api/oauth/usage`
- Beta header: `anthropic-beta: oauth-2025-04-20`
- Authorization scope follows the working Claude Code manual flow: `org:create_api_key user:profile user:inference user:sessions:claude_code user:mcp_servers user:file_upload`. Token refresh requests the user scopes without `org:create_api_key`.

Live usage requires `user:profile`. Existing credentials without that scope are refreshed before a usage request; a 403 should be treated as a reconnect/scope problem rather than silently returning empty bars.

### Current usage shapes

Core windows remain `five_hour` and `seven_day` with percentage usage and reset timestamps. CodeMeter also accepts legacy `seven_day_<model>` fields.

Newer model-specific weekly limits can arrive through `limits[]`, for example:

```json
{
  "kind": "weekly_scoped",
  "percent": 52,
  "resets_at": "2026-09-14T00:00:00Z",
  "scope": {
    "model": { "display_name": "Fable" }
  }
}
```

The parser accepts object or array buckets, `utilization`, `used_percent`, `percent`, and legacy `percent_left` semantics, plus ISO/epoch reset aliases.

Some current responses repeat the core quotas inside `limits` as `kind: "session"` / `kind: "weekly_all"` while still returning `five_hour` / `seven_day`. Map-shaped payloads may use those semantic names as dictionary keys instead of a `kind` property. CodeMeter preserves those keys, maps them back to Session/Weekly, lets the structured value replace the legacy duplicate, and only keeps `weekly_scoped` / genuinely distinct limits as extra rows. Anonymous `Limit N` rows that exactly match a named quota's percent/reset identity are suppressed as a final schema-drift guard.

### Rate limiting

`/api/oauth/usage` can return aggressive per-account 429s when Claude Code sessions and external monitors poll concurrently. CodeMeter:

1. reads `Retry-After` as seconds or an HTTP date;
2. persists the profile cooldown across process restarts;
3. does not call the endpoint again before that cooldown ends, even for manual/background refresh;
4. keeps the last successful quota snapshot visible with a stale/rate-limited annotation;
5. never records stale values as new history or uses them for quota notifications.

If `Retry-After` is absent, CodeMeter uses a conservative five-minute cooldown.

## Codex

### OAuth and usage

- Token: `https://auth.openai.com/oauth/token`
- Usage: `GET https://chatgpt.com/backend-api/wham/usage`
- Reset credits: `GET https://chatgpt.com/backend-api/wham/rate-limit-reset-credits`

Usage requests include the bearer token and `ChatGPT-Account-Id`. The dedicated reset-credit request is best-effort; failure there must never blank normal quota data.

### Window classification

Do not assume `primary_window` always means 5-hour and `secondary_window` always means weekly. Codex can place a sole weekly window in the primary slot. CodeMeter classifies observed windows by `limit_window_seconds` first:

- `18000` -> Session / 5-hour
- `604800` -> Weekly / 7-day

Slot position is only a compatibility fallback when duration is missing/unknown. Missing windows stay missing rather than being synthesized from stale data.

Percentages are read from body fields (`used_percent`, legacy `percent_left`, etc.) and can fall back to `x-codex-primary-used-percent` / `x-codex-secondary-used-percent` headers.

### Additional limits

`additional_rate_limits[]` can carry named model-specific rate limits with their own primary/secondary windows. `limit_name` is preferred, then `display_name`, `name`, and `metered_feature`.

Spark telemetry is hidden for Plus profiles because the backend can expose that bucket even when the plan cannot use Spark. Eligible plans can show both Spark and Spark Weekly when returned.

### Reset and flex credits

The main usage payload can expose `rate_limit_reset_credits.available_count`. The dedicated reset-credit endpoint can return richer data; CodeMeter currently shows the available count read-only and does not offer a consume/reset action.

`credits.balance` is shown as a credit count plus its current 4-cent-per-credit equivalent.

## HTTP behavior

The shared HTTP layer preserves response status, headers, and body. This is required because current provider behavior uses response headers for retry timing and, in Codex's case, quota percentage fallbacks.

## Updating safely

If a provider breaks:

1. Confirm login/usage still works in the current official Claude Code or Codex client.
2. Compare current first-party endpoint/request headers and OAuth scopes.
3. Capture only your own redacted response shape; never log bearer, refresh, ID tokens, or raw auth files.
4. Add schema aliases/fallbacks rather than deleting older compatible shapes unless they are unsafe.
5. Test 401, 403, 429, 5xx, missing-window, and token-refresh behavior independently.
6. Respect provider cooldowns; repeated manual refresh must never bypass `Retry-After`.
