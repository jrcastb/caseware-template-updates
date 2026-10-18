"""Generates diagrams/1-architecture.svg (hand-laid-out; Mermaid's automatic layout was unreadable here).

Every box, arrow and label is kept in a registry so `python3 arch_svg.py --check` can verify the layout
geometrically: text width measured with real font metrics (Helvetica clone and Arial clone, the wider
wins) fits inside its box, and no arrow segment or label passes through a box it does not start or end at.
"""
import os
import sys

W, H = 1200, 830
NEW = ("#d4f0dc", "#2e7d4f"); OLD = ("#e6e6e6", "#555555")
# Helvetica- and Arial-metric fonts, wherever this machine keeps them (Linux, macOS, Windows). Each entry is
# (path, index inside a .ttc collection). The widest available measurement wins.
FONT_CANDIDATES = {
    False: [("/usr/share/texmf/fonts/opentype/public/tex-gyre/texgyreheros-regular.otf", 0),
            ("/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf", 0),
            ("/System/Library/Fonts/Helvetica.ttc", 0),
            ("/System/Library/Fonts/Supplemental/Arial.ttf", 0), ("/Library/Fonts/Arial.ttf", 0),
            ("C:/Windows/Fonts/arial.ttf", 0)],
    True: [("/usr/share/texmf/fonts/opentype/public/tex-gyre/texgyreheros-bold.otf", 0),
           ("/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf", 0),
           ("/System/Library/Fonts/Helvetica.ttc", 1),
           ("/System/Library/Fonts/Supplemental/Arial Bold.ttf", 0), ("/Library/Fonts/Arial Bold.ttf", 0),
           ("C:/Windows/Fonts/arialbd.ttf", 0)],
}
try:
    from PIL import ImageFont
    FONTS = {bold: [(f, i) for f, i in paths if os.path.exists(f)] for bold, paths in FONT_CANDIDATES.items()}
except ImportError:  # Pillow not installed: the SVG is still generated, but --check cannot measure text
    ImageFont, FONTS = None, {False: [], True: []}
out, boxes, arrows, labels = [], {}, [], []

def width(t, size, bold=False):
    if not FONTS[bold]:
        return 0.6 * size * len(t)  # rough estimate, used only to register labels
    return max(ImageFont.truetype(f, size, index=i).getlength(t) for f, i in FONTS[bold])

def esc(t): return t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

def text(x, y, t, size=12, weight="normal", anchor="middle", fill="#111", owner=None):
    out.append(f'<text x="{x}" y="{y}" font-size="{size}" font-weight="{weight}" text-anchor="{anchor}" fill="{fill}">{esc(t)}</text>')
    w = width(t, size, weight == "bold")
    x0 = {"middle": x - w / 2, "start": x, "end": x - w}[anchor]
    labels.append((t, x0, y - size * 0.8, x0 + w, y + size * 0.2, owner))

def box(key, x, y, w, h, title, sub=(), new=True, store=False):
    fill, stroke = NEW if new else OLD
    sub = [sub] if isinstance(sub, str) else list(sub)
    if store:
        ry = 6
        out.append(f'<path d="M{x},{y+ry} L{x},{y+h-ry} A{w/2},{ry} 0 0 0 {x+w},{y+h-ry} L{x+w},{y+ry}" fill="{fill}" stroke="{stroke}" stroke-width="1.4"/>')
        out.append(f'<ellipse cx="{x+w/2}" cy="{y+ry}" rx="{w/2}" ry="{ry}" fill="{fill}" stroke="{stroke}" stroke-width="1.4"/>')
        top = y + 2 * ry
    else:
        out.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="9" fill="{fill}" stroke="{stroke}" stroke-width="1.4"/>')
        top = y
    boxes[key] = (x, y, x + w, y + h, top)
    lines = 1 + len(sub)
    cy = (top + y + h) / 2 - (lines - 1) * 13 / 2
    text(x + w / 2, cy + 4, title, 12.5, "bold", owner=key)
    for i, line in enumerate(sub):
        text(x + w / 2, cy + 4 + 14 * (i + 1), line, 10.5, fill="#333", owner=key)

def arrow(src, dst, pts, label=None, lx=None, ly=None, dashed=False, anchor="middle"):
    d = " ".join(("M" if i == 0 else "L") + f"{px},{py}" for i, (px, py) in enumerate(pts))
    dash = ' stroke-dasharray="6 4"' if dashed else ""
    out.append(f'<path d="{d}" fill="none" stroke="#333" stroke-width="1.4"{dash} marker-end="url(#ah)"/>')
    arrows.append((src, dst, pts))
    if label:
        for i, line in enumerate(label.split("\n")):
            text(lx, ly + i * 13, line, 10.5, anchor=anchor, fill="#222", owner="arrow")

def connector(cx, cy, label="A"):
    out.append(f'<circle cx="{cx}" cy="{cy}" r="11" fill="#fff" stroke="#6a3fb5" stroke-width="1.6"/>')
    out.append(f'<text x="{cx}" y="{cy+4}" font-size="11" font-weight="bold" text-anchor="middle" fill="#6a3fb5">{label}</text>')
    boxes[f"conn{cx},{cy}"] = (cx - 11, cy - 11, cx + 11, cy + 11, cy - 11)

out.append(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}" font-family="Helvetica, Arial, \'Liberation Sans\', sans-serif">')
out.append('<defs><marker id="ah" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="#333"/></marker></defs>')
out.append(f'<rect width="{W}" height="{H}" fill="#ffffff"/>')
out.append('<rect x="8" y="10" width="1184" height="250" rx="12" fill="#f3f7ff" stroke="#8aa4d6" stroke-dasharray="5 4"/>')
text(22, 32, "GLOBAL: template data only (not firm-specific), no residency constraint", 13, "bold", "start", "#2b4c8c", owner="band")
out.append('<rect x="8" y="290" width="1184" height="490" rx="12" fill="#fffaf0" stroke="#d1a24a" stroke-dasharray="5 4"/>')
text(22, 312, "EACH RESIDENCY REGION (US, EU, CA): firm data never leaves it", 13, "bold", "start", "#8a5a00", owner="band")

# ---- global
box("ct", 30, 75, 120, 50, "Content teams", new=False)
box("ts", 180, 75, 150, 50, "Template Store DB", "all versions", new=False, store=True)
box("eb", 370, 75, 170, 50, "EventBridge", "publish / withdraw hook")
box("gs", 590, 40, 170, 46, "Graph Sync λ", "+ 5-min repair from store")
box("vg", 1000, 40, 180, 46, "Version Graph", "DynamoDB global table", store=True)
box("ss", 590, 135, 170, 46, "Summary Service λ", "jobs via SQS")
box("sa", 800, 135, 180, 46, "Summary Archive", "S3, Object Lock", store=True)
box("br", 590, 205, 170, 36, "Amazon Bedrock", "pinned model")
box("jd", 420, 200, 140, 36, "JSON diff tool", new=False)
arrow("ct", "ts", [(150, 100), (180, 100)])
arrow("ts", "eb", [(330, 100), (370, 100)], "hook", 350, 92)
arrow("eb", "gs", [(540, 100), (565, 100), (565, 63), (590, 63)])
arrow("eb", "ss", [(565, 100), (565, 158), (590, 158)])
arrow("gs", "vg", [(760, 63), (1000, 63)], "upsert node (idempotent)", 880, 56)
arrow("ss", "sa", [(760, 158), (800, 158)])
arrow("ss", "br", [(675, 181), (675, 205)])
arrow("ss", "jd", [(590, 172), (575, 172), (575, 218), (560, 218)])
arrow("vg", "rp", [(1090, 86), (1090, 315)], "replicate", 1097, 280, dashed=True, anchor="start")
arrow("eb", "connA1", [(400, 125), (400, 270)], None, dashed=True)
connector(400, 281)
text(386, 285, "cross-region rule: template events only", 10.5, anchor="end", fill="#6a3fb5", owner="arrow")

# ---- region
box("ui", 30, 330, 160, 50, "Web app", "indicator, list, summary", new=False)
box("api", 260, 330, 250, 50, "Status API: API Gateway + λ", "status per cohort = f(binding, graph)")
box("rp", 800, 315, 380, 50, "Version Graph replica", "DynamoDB global table, read-only")
box("bp", 560, 424, 240, 66, "Binding Projection", ["bindings, cohort counts,", "decision log by summary id"], store=True)
box("es", 30, 520, 160, 60, "Engagement Service", "load ~1 min, decisions", new=False)
box("db", 30, 680, 160, 55, "Customer DBs", "+ outbox table (new)", new=False, store=True)
box("rb", 260, 680, 200, 55, "EventBridge", "regional bus")
box("pj", 560, 600, 220, 44, "Projector λ", "binding + cohort move, one txn")
box("fw", 860, 476, 300, 78, "Fan-out Worker (Part 2)",
    ["ECS Fargate · AIMD limit ≤ grant", "shared Retry-After pause · timeout quarantine"])
box("wt", 860, 594, 300, 54, "Work table", "DynamoDB · 1 item/engagement · fair per firm", store=True)
box("pl", 860, 690, 300, 44, "Fan-out Planner λ", "SUSPECT bindings only")

arrow("ui", "api", [(190, 345), (260, 345)])
text(225, 338, "status", 10.5, owner="arrow")
arrow("api", "sa", [(490, 330), (490, 272), (850, 272), (850, 181)])
text(858, 230, "read summary (template data)", 10.5, anchor="start", fill="#222", owner="arrow")
arrow("api", "rp", [(510, 340), (800, 340)], "graph", 655, 333)
arrow("api", "bp", [(450, 380), (450, 458), (560, 458)], "cohorts, pages", 505, 451)
arrow("ui", "es", [(110, 380), (110, 520)], "apply / decline\n(target, summaryId)", 118, 440, anchor="start")
arrow("es", "db", [(110, 580), (110, 680)], "decision + outbox row\n(same transaction)", 118, 622, anchor="start")
arrow("db", "rb", [(190, 707), (260, 707)], "relay", 225, 700)
connector(430, 615)
arrow("connA2", "rb", [(430, 626), (430, 680)])
arrow("rb", "pj", [(460, 695), (500, 695), (500, 622), (560, 622)], "Binding\nObserved", 494, 650, anchor="end")
arrow("pj", "bp", [(660, 600), (660, 490)], "revision-guarded", 668, 545, anchor="start")
arrow("rb", "pl", [(460, 720), (860, 720)], "TemplatePublished", 650, 713)
arrow("pl", "wt", [(1010, 690), (1010, 648)])
arrow("wt", "fw", [(1010, 594), (1010, 554)])
arrow("fw", "bp", [(860, 505), (800, 470)], "revision-guarded", 814, 456, anchor="start")
arrow("fw", "es", [(1160, 515), (1185, 515), (1185, 765), (15, 765), (15, 550), (30, 550)],
      "load engagement (~1 min): backfill and SUSPECT bindings only, never on the publish path", 600, 758)

out.append(f'<rect x="30" y="796" width="18" height="14" rx="3" fill="{NEW[0]}" stroke="{NEW[1]}"/>')
text(54, 807, "new", 11.5, anchor="start", owner="legend")
out.append(f'<rect x="100" y="796" width="18" height="14" rx="3" fill="{OLD[0]}" stroke="{OLD[1]}"/>')
text(124, 807, "existing", 11.5, anchor="start", owner="legend")
text(200, 807, "solid: data or control flow  ·  dashed: replication  ·  cylinder: data store  ·  A: off-page connector",
     11, anchor="start", fill="#444", owner="legend")
out.append("</svg>")

def segment_hits_rect(p, q, r, pad=2):
    (x1, y1), (x2, y2) = p, q
    rx0, ry0, rx1, ry1 = r[0] + pad, r[1] + pad, r[2] - pad, r[3] - pad
    if x1 == x2:  # vertical
        return rx0 < x1 < rx1 and max(min(y1, y2), ry0) < min(max(y1, y2), ry1)
    if y1 == y2:  # horizontal
        return ry0 < y1 < ry1 and max(min(x1, x2), rx0) < min(max(x1, x2), rx1)
    steps = 200   # diagonal: sample
    return any(rx0 < x1 + (x2 - x1) * t / steps < rx1 and ry0 < y1 + (y2 - y1) * t / steps < ry1 for t in range(steps + 1))

def check():
    problems = []
    for t, x0, y0, x1, y1, owner in labels:
        if owner in boxes:
            bx0, by0, bx1, by1, _ = boxes[owner]
            if x0 < bx0 + 4 or x1 > bx1 - 4:
                problems.append(f"text overflows box {owner}: '{t}' ({x1 - x0:.0f}px in {bx1 - bx0}px)")
        else:
            for key, b in boxes.items():
                if x0 < b[2] and x1 > b[0] and y0 < b[3] and y1 > b[1]:
                    problems.append(f"label '{t}' overlaps box {key}")
        if x1 > W - 10:
            problems.append(f"text past the right edge: '{t}'")
    for src, dst, pts in arrows:
        for p, q in zip(pts, pts[1:]):
            for key, b in boxes.items():
                if key in (src, dst) or key.startswith("conn") and (src.startswith("conn") or dst.startswith("conn")):
                    continue
                if segment_hits_rect(p, q, b):
                    problems.append(f"arrow {src}->{dst} segment {p}-{q} crosses box {key}")
            for t, x0, y0, x1, y1, owner in labels:
                if owner not in (src, dst) and segment_hits_rect(p, q, (x0, y0, x1, y1), pad=-1):
                    problems.append(f"arrow {src}->{dst} segment {p}-{q} crosses text '{t}'")
    return problems

open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "1-architecture.svg"), "w").write("\n".join(out))
if "--check" in sys.argv:
    if not (FONTS[False] and FONTS[True]):
        print("cannot check: install Pillow (pip install pillow) and a Helvetica or Arial font; the SVG was still written")
        sys.exit(2)
    problems = check()
    print("\n".join(problems) if problems else f"layout OK: {len(boxes)} boxes, {len(arrows)} arrows, {len(labels)} text runs")
    sys.exit(1 if problems else 0)
