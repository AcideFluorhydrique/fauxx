#!/usr/bin/env python3
"""Check the bundled crawl lists for link rot.

Reads app/src/main/assets/crawl_urls.json (en) and app/src/main/assets/crawl_urls/*.json,
fetches every URL with a real mobile browser User-Agent, and sorts each entry into:

  dead          HTTP 404 or 410, or the host does not resolve (NXDOMAIN). An entry is
                only called dead if it fails the same way twice, with a pause between.
  ok            any 2xx or 3xx response (redirects are followed).
  inconclusive  everything else: 403, 429, 5xx, timeouts, TLS and connection errors.
                Datacenter IPs (CI runners) are routinely bot-blocked, so these are NOT
                evidence that a page is gone and must be re-checked in a real browser.

Only the standard library is used. Requests are polite: the URLs of a single host are
fetched one after another with a short pause, and only a few hosts are worked on at once.

Usage:
  python3 scripts/check_crawl_urls.py [--report report.md] [--json summary.json]
                                      [--locale en --locale fr] [--url URL ...]

Exit status is 0 whenever the check itself ran, whatever it found; the caller decides
what to do with the summary. A DNS failure is only reported as dead when the local
resolver is trustworthy; on a filtering resolver pass --no-dns-dead.
"""

from __future__ import annotations

import argparse
import concurrent.futures as cf
import glob
import json
import os
import socket
import ssl
import sys
import time
import urllib.error
import urllib.request
from collections import Counter, defaultdict
from urllib.parse import urlsplit

UA = (
    "Mozilla/5.0 (Linux; Android 14; SM-S911B) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36"
)
HEADERS = {
    "User-Agent": UA,
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "en-US,en;q=0.9",
}
DEAD_HTTP = {404, 410}
# getaddrinfo codes that mean "this name has no address": EAI_NONAME and EAI_NODATA.
# EAI_AGAIN (temporary failure) is deliberately not here.
NXDOMAIN_CODES = {socket.EAI_NONAME, getattr(socket, "EAI_NODATA", -5)}
REPORT_LIMIT = 60000  # GitHub issue bodies are capped at 65536 characters.


def load_entries(assets: str, locales: list[str] | None) -> list[tuple[str, str, str]]:
    files = [("en", os.path.join(assets, "crawl_urls.json"))]
    for path in sorted(glob.glob(os.path.join(assets, "crawl_urls", "*.json"))):
        files.append((os.path.splitext(os.path.basename(path))[0], path))
    entries = []
    for locale, path in files:
        if locales and locale not in locales:
            continue
        if not os.path.exists(path):
            continue
        with open(path, encoding="utf-8") as fh:
            for e in json.load(fh):
                entries.append((locale, e["category"], e["url"]))
    return entries


def fetch_once(url: str, timeout: float) -> tuple[str, str]:
    """Return (kind, detail) where kind is ok, dead, dns or inconclusive."""
    ctx = ssl.create_default_context()
    req = urllib.request.Request(url, headers=HEADERS)
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=ctx) as resp:
            resp.read(2048)
            return "ok", str(resp.status)
    except urllib.error.HTTPError as e:
        if e.code in DEAD_HTTP:
            return "dead", f"HTTP {e.code}"
        return "inconclusive", f"HTTP {e.code}"
    except urllib.error.URLError as e:
        reason = e.reason
        if isinstance(reason, socket.gaierror) and reason.errno in NXDOMAIN_CODES:
            return "dns", "NXDOMAIN"
        if isinstance(reason, (socket.timeout, TimeoutError)):
            return "inconclusive", "timeout"
        return "inconclusive", type(reason).__name__
    except (socket.timeout, TimeoutError):
        return "inconclusive", "timeout"
    except Exception as e:  # noqa: BLE001 - any other failure is just inconclusive
        return "inconclusive", type(e).__name__


def check_url(url: str, timeout: float, retry_delay: float, dns_dead: bool) -> tuple[str, str]:
    kind, detail = fetch_once(url, timeout)
    if kind == "dns" and not dns_dead:
        return "inconclusive", "DNS failure (resolver not trusted)"
    if kind in ("dead", "dns"):
        # Retry once before calling anything dead.
        time.sleep(retry_delay)
        kind2, detail2 = fetch_once(url, timeout)
        if kind2 == kind and detail2 == detail:
            return "dead", detail
        if kind2 == "ok":
            return "ok", detail2
        return "inconclusive", f"{detail} then {detail2}"
    return kind, detail


def check_host(items, timeout, retry_delay, host_delay, dns_dead):
    out = []
    for i, (locale, category, url) in enumerate(items):
        if i:
            time.sleep(host_delay)
        kind, detail = check_url(url, timeout, retry_delay, dns_dead)
        out.append({"locale": locale, "category": category, "url": url,
                    "result": kind, "detail": detail})
        if kind == "dead" and detail == "NXDOMAIN":
            # The rest of this host's URLs cannot resolve either; skip the network.
            for locale2, category2, url2 in items[i + 1:]:
                out.append({"locale": locale2, "category": category2, "url": url2,
                            "result": "dead", "detail": "NXDOMAIN"})
            break
    return out


def build_report(results: list[dict], total: int) -> str:
    dead = [r for r in results if r["result"] == "dead"]
    inconclusive = Counter(r["detail"] for r in results if r["result"] == "inconclusive")
    ok = sum(1 for r in results if r["result"] == "ok")
    lines = [
        "Automated monthly check of the bundled crawl lists "
        "(`app/src/main/assets/crawl_urls.json`, `app/src/main/assets/crawl_urls/*.json`).",
        "",
        f"Checked {total} entries: {ok} ok, {len(dead)} dead, "
        f"{sum(inconclusive.values())} inconclusive.",
        "",
        "Dead means HTTP 404/410 or NXDOMAIN, seen twice. Verify in a real browser before "
        "removing an entry; a dead deep link can often be replaced by the same site's "
        "working section page in the same category.",
        "",
    ]
    if dead:
        lines.append("## Dead entries")
        lines.append("")
        by_locale = defaultdict(list)
        for r in dead:
            by_locale[r["locale"]].append(r)
        body = []
        for locale in sorted(by_locale):
            body.append(f"### {locale} ({len(by_locale[locale])})")
            body.append("")
            body.append("| Category | URL | Result |")
            body.append("| --- | --- | --- |")
            for r in sorted(by_locale[locale], key=lambda r: (r["category"], r["url"])):
                body.append(f"| {r['category']} | {r['url']} | {r['detail']} |")
            body.append("")
        lines.extend(body)
    if inconclusive:
        lines.append("## Inconclusive (not acted on)")
        lines.append("")
        lines.append("CI runners use datacenter IPs that many sites bot-block, so these "
                     "counts are informational only.")
        lines.append("")
        lines.append("| Result | Count |")
        lines.append("| --- | --- |")
        for detail, n in inconclusive.most_common():
            lines.append(f"| {detail} | {n} |")
        lines.append("")
    text = "\n".join(lines)
    if len(text) > REPORT_LIMIT:
        text = text[:REPORT_LIMIT].rsplit("\n", 1)[0]
        text += "\n\n(Report truncated; see the workflow run artifact for the full list.)\n"
    return text


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--assets", default="app/src/main/assets")
    ap.add_argument("--locale", action="append", help="limit to this locale (repeatable)")
    ap.add_argument("--url", action="append", help="check only these URLs (for testing)")
    ap.add_argument("--concurrency", type=int, default=6, help="hosts checked at once")
    ap.add_argument("--timeout", type=float, default=20.0)
    ap.add_argument("--retry-delay", type=float, default=5.0)
    ap.add_argument("--host-delay", type=float, default=1.0)
    ap.add_argument("--no-dns-dead", action="store_true",
                    help="treat DNS failures as inconclusive (filtering resolver)")
    ap.add_argument("--report", help="write a Markdown report here")
    ap.add_argument("--json", help="write the full results and counts here")
    args = ap.parse_args()

    if args.url:
        entries = [("adhoc", "-", u) for u in args.url]
    else:
        entries = load_entries(args.assets, args.locale)
    by_host = defaultdict(list)
    for e in entries:
        by_host[(urlsplit(e[2]).hostname or "").lower()].append(e)

    results: list[dict] = []
    with cf.ThreadPoolExecutor(max_workers=max(1, args.concurrency)) as ex:
        futures = [ex.submit(check_host, items, args.timeout, args.retry_delay,
                             args.host_delay, not args.no_dns_dead)
                   for items in by_host.values()]
        for done_count, fut in enumerate(cf.as_completed(futures), 1):
            results.extend(fut.result())
            if done_count % 100 == 0:
                print(f"{done_count}/{len(futures)} hosts", file=sys.stderr)

    counts = Counter(r["result"] for r in results)
    summary = {
        "total": len(results),
        "ok": counts["ok"],
        "dead": counts["dead"],
        "inconclusive": counts["inconclusive"],
        "results": sorted(results, key=lambda r: (r["locale"], r["category"], r["url"])),
    }
    if args.json:
        with open(args.json, "w", encoding="utf-8") as fh:
            json.dump(summary, fh, indent=1, ensure_ascii=False)
    report = build_report(results, len(results))
    if args.report:
        with open(args.report, "w", encoding="utf-8") as fh:
            fh.write(report)
    print(f"total={summary['total']} ok={summary['ok']} dead={summary['dead']} "
          f"inconclusive={summary['inconclusive']}")
    if args.url:
        for r in summary["results"]:
            print(f"  {r['result']:12} {r['detail']:30} {r['url']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
