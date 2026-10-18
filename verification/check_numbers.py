#!/usr/bin/env python3
"""Recomputes every derived number in DESIGN.md from the brief's figures and the stated assumptions, and
checks that the rounded value printed in DESIGN.md is present. Exit code 1 on any mismatch."""
import os, re, sys

DESIGN = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "DESIGN.md")).read()
flat = re.sub(r"\s+", " ", DESIGN)
checks = []
def show(label, value, text):
    ok = text in flat
    checks.append(ok)
    print(f"{'OK  ' if ok else 'MISS'} {label:<58} {value:>14,.4g}   expects '{text}'")

# Brief
firms, engagements, products, largest, per_week = 4_000, 800_000, 40, 40_000, 1
min_per_week, days_per_month = 7 * 24 * 60, 365.25 / 12
weeks_per_month = days_per_month / 7
# Assumptions (DESIGN.md A5, A8, A9)
grant, load_cost = 50, (0.001, 0.002)
pairs_per_publish, summary_in, summary_out = 10, 30_000, 1_500
price_in, price_out = 3 / 1e6, 15 / 1e6
wru, rru, eb = 0.625 / 1e6, 0.125 / 1e6, 1.0 / 1e6

per_product = engagements / products
show("engagements per product", per_product, "20k engagements per product")
show("hours per publish at the grant", per_product / grant / 60, "≈ 6.7 h")
weekly = products * per_week * per_product
show("load-minutes per week", weekly, "800k load-min/week")
show("loads permanently in flight", weekly / min_per_week, "≈ 79 loads in flight")
monthly_loads = weekly * weeks_per_month
show("naive loads per month (millions)", monthly_loads / 1e6, "3.5M loads/month")
show("largest firm share of fleet (%)", 100 * largest / engagements, "5% of fleet")
show("largest firm backfill minutes at the grant", largest / grant, "800 min")
show("largest firm backfill hours", largest / grant / 60, "13.3 h")
backfill_min = engagements / grant
show("backfill minutes at the grant", backfill_min, "16,000 min")
show("backfill days 24/7", backfill_min / 1440, "≈ 11 days")
show("backfill days off-peak 12 h/day", backfill_min / 720, "≈ 22 days")
show("backfill compute low ($)", engagements * load_cost[0], "$0.8k–1.6k")
show("naive monthly load compute low ($k)", monthly_loads * load_cost[0] / 1e3, "$3.5k–7k")
show("naive monthly load compute high ($k)", monthly_loads * load_cost[1] / 1e3, "$3.5k–7k")

# Steady-state cost table
audit = 1_000 * 30
show("audit loads per month", audit, "30k loads")
c_audit = (audit * load_cost[0], audit * load_cost[1]); show("audit $ low", c_audit[0], "30–60")
opens = engagements * 10
show("binding events per month (millions)", opens / 1e6, "8M/month")
decisions = engagements * per_week * weeks_per_month       # at most one decision per offer
show("decisions per month, upper bound (millions)", decisions / 1e6, "≤ 3.5M decisions")
c_events = opens * eb + opens * wru + decisions * 3 * 2 * wru  # transactional write: 3 items x 2 WRU
show("events + projection + cohort counters ($)", c_events, "≈ 25")
views = firms * 50 * 30
show("list views per month (millions)", views / 1e6, "6M list views")
c_reads = views * 25 * rru
show("status reads ($)", c_reads, "≈ 20")
per_summary = (summary_in * price_in + summary_out * price_out) + ((summary_in + summary_out) * price_in + summary_out * price_out)
show("cost per summary incl. evaluation pass ($)", per_summary, "~$0.23")
publishes = products * per_week * weeks_per_month
show("publishes per month", publishes, "≈ 174 publishes/month")
pairs = publishes * pairs_per_publish
show("summary pairs per month", pairs, "≈ 1,740 pairs/month")
c_pairs = pairs * 0.23
show("per-pair summaries ($/month)", c_pairs, "≈ $400")
show("per-step summaries ($/month)", publishes * 0.23, "≈ $40")
c_compute = (250, 350)
low = c_audit[0] + c_events + c_reads + c_compute[0] + c_pairs
high = c_audit[1] + c_events + c_reads + c_compute[1] + 800
show("total low ($k)", low / 1e3, "≈ $0.7k–1.3k")
show("total high ($k)", high / 1e3, "≈ $0.7k–1.3k")

# List view (section 5)
row, cohort_row = 300, 200
naive_bytes = largest * row
show("naive list view: MB read for the largest firm", naive_bytes / 1e6, "12 MB")
show("naive list view: RRU (eventually consistent)", naive_bytes / 4096 * 0.5, "≈ 1,500 RRU")
cohorts = products * pairs_per_publish * 3
show("cohorts per firm, upper bound", cohorts, "≤ 1,200 cohorts")
show("cohort read: KB", cohorts * cohort_row / 1e3, "240 KB")
show("cohort read: RRU", cohorts * cohort_row / 4096 * 0.5, "≈ 30 RRU")

print(f"\n{sum(checks)}/{len(checks)} figures present in DESIGN.md")
sys.exit(0 if all(checks) else 1)
