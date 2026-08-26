# Provider integration notes

These integrations intentionally live behind small provider classes because neither subscription-usage API is a stable public third-party contract.

## Claude

`ClaudeAuth.kt` owns all OAuth constants and token handling. `ClaudeUsageClient.kt` owns the usage endpoint and response parsing.

Expected quota response fields currently include `five_hour`, `seven_day`, optional `seven_day_<model>` objects, and possibly dynamic `limits`. Each recognized object supplies a utilization/used percentage and a reset timestamp. The current service reports usage as percentages; the parser preserves those 0-100 values and only clamps malformed out-of-range values.

The OAuth token exchange/refresh has been observed with JSON and form-encoded variants in third-party/current-client implementations, so `ClaudeAuth` attempts JSON first and falls back to form encoding for 4xx encoding/contract failures.

The usage request currently sends:

```text
Authorization: Bearer <access_token>
anthropic-beta: oauth-2025-04-20
```

A 403 most commonly means the token is missing the profile/session permission needed by the usage route, or Anthropic changed the private contract.

## Codex

`CodexAuth.kt` implements ChatGPT/Codex device authorization. The device-code request returns a `device_auth_id`, `user_code`, and poll interval. After browser approval, polling returns the authorization-code/PKCE material, which is exchanged at the OAuth token endpoint.

`CodexUsageClient.kt` calls the current ChatGPT usage route with:

```text
Authorization: Bearer <access_token>
ChatGPT-Account-Id: <account_id>
```

It accepts both `rate_limit` and `rate_limits`, plus aliases for primary/secondary windows. Window duration is used to label the shorter quota as Session and the longer one as Weekly. `additional_rate_limits` are preserved when recognizable.

## Updating safely

If a provider breaks:

1. Confirm login still works in the official Claude Code or Codex CLI.
2. Compare the official client's current OAuth constants/flow with the corresponding auth class.
3. Capture only your own usage response shape; do not log bearer/refresh/ID tokens.
4. Add parser aliases rather than replacing old aliases when practical.
5. Test token refresh separately from initial login.
6. Keep rate-limit polling conservative.
