"""Builds Tenshu Calendar's store-screenshot fixture from store/fixtures/calendar/manifest.json.

    python3 store/fixtures/calendar/generate_calendar.py <out dir> [--today YYYY-MM-DD]

Writes, relative to today on this machine (or `--today`, to look at another day's fixture):

    <out>/calendars.json    the calendars, in order: name, ARGB colour, which one is the app's own
                            default, and the .ics file that fills it
    <out>/<calendar>.ics    one RFC 5545 file per calendar, which Calendar's `-seedFixture` launch
                            imports through `CalendarRepository.importIcs` — the call the calendar
                            row's "Import or export…" sheet makes

Deterministic for a given manifest and day. Times are floating (no TZID): they read the same in any
simulator time zone, and the importer stores them as civil times either way.

⚠ Nothing it writes is committed: store-screenshots.md §3 ("Nothing binary under store/").
"""

import datetime as dt
import json
import os
import re
import sys

sys.dont_write_bytecode = True  # no __pycache__ under store/ (check-store-assets.py rejects binaries)

HERE = os.path.dirname(os.path.abspath(__file__))
DAYS = ["SU", "MO", "TU", "WE", "TH", "FR", "SA"]  # index = days since Sunday


def since_sunday(day):
    return day.isoweekday() % 7


def resolve(on, today):
    """An event's `on` → its (first, for a repeat: the shown) date."""
    if "day" in on:
        return today + dt.timedelta(days=on["day"])
    if "workday" in on:
        n = on["workday"]
        day = today
        step = 1 if n >= 0 else -1
        # A weekend "today" counts from the next (or, backwards, the previous) weekday.
        while day.isoweekday() > 5:
            day += dt.timedelta(days=step)
        for _ in range(abs(n)):
            day += dt.timedelta(days=step)
            while day.isoweekday() > 5:
                day += dt.timedelta(days=step)
        return day
    if "weekday" in on:
        week_start = today - dt.timedelta(days=since_sunday(today))
        return week_start + dt.timedelta(days=DAYS.index(on["weekday"]) + 7 * on.get("week", 0))
    sys.exit(f"generate_calendar: unknown `on` {on}")


def months_back(day, n):
    """The same day of the month n months earlier — further back while that month lacks the day
    (a 31st), so DTSTART stays on the shown day and a monthly rule lands on it."""
    while True:
        y, m = divmod(day.year * 12 + day.month - 1 - n, 12)
        try:
            return dt.date(y, m + 1, day.day)
        except ValueError:
            n += 1


def years_back(day, n):
    """The same date n years earlier; a 29 February goes back to a leap year."""
    while True:
        try:
            return day.replace(year=day.year - n)
        except ValueError:
            n += 1


def series_start(event, shown, history_weeks):
    """Where a repeat's DTSTART goes, and its RRULE — or (shown, None) for a one-off."""
    repeat = event.get("repeat")
    if not repeat:
        return shown, None
    if repeat.startswith("weekly:"):
        days = repeat.split(":", 1)[1].split(",")
        start = shown - dt.timedelta(weeks=history_weeks)
        # ⚠ DTSTART must itself be an occurrence (RFC 5545 §3.8.5.3), so the first matching day.
        while DAYS[since_sunday(start)] not in days:
            start += dt.timedelta(days=1)
        return start, "FREQ=WEEKLY;BYDAY=" + ",".join(days)
    if repeat == "monthly":
        return months_back(shown, 3), "FREQ=MONTHLY"
    if repeat == "yearly":
        return years_back(shown, 6), "FREQ=YEARLY"
    sys.exit(f"generate_calendar: unknown repeat {repeat!r}")


def text(value):
    """RFC 5545 §3.3.11 TEXT escaping."""
    return value.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")


def fold(line):
    """RFC 5545 §3.1: lines longer than 75 octets continue on the next, after a space."""
    raw = line.encode("utf-8")
    if len(raw) <= 75:
        return line
    out, chunk = [], b""
    for ch in line:
        b = ch.encode("utf-8")
        if len(chunk) + len(b) > (75 if not out else 74):
            out.append(chunk.decode("utf-8"))
            chunk = b""
        chunk += b
    out.append(chunk.decode("utf-8"))
    return "\r\n ".join(out)


def stamp(day, hhmm=None):
    if hhmm is None:
        return day.strftime("%Y%m%d")
    h, m = (int(v) for v in hhmm.split(":"))
    return dt.datetime.combine(day, dt.time(h, m)).strftime("%Y%m%dT%H%M%S")


def vevent(event, uid, today, history_weeks, now_utc):
    shown = resolve(event["on"], today)
    start, rule = series_start(event, shown, history_weeks)
    lines = ["BEGIN:VEVENT", f"UID:{uid}", f"DTSTAMP:{now_utc}", f"SUMMARY:{text(event['title'])}"]
    if event.get("allDay"):
        end = start + dt.timedelta(days=event.get("days", 1))
        lines += [f"DTSTART;VALUE=DATE:{stamp(start)}", f"DTEND;VALUE=DATE:{stamp(end)}"]
    else:
        lines += [f"DTSTART:{stamp(start, event['at'])}", f"DTEND:{stamp(start, event['until'])}"]
    if rule:
        lines.append(f"RRULE:{rule}")
    if event.get("location"):
        lines.append(f"LOCATION:{text(event['location'])}")
    if event.get("notes"):
        lines.append(f"DESCRIPTION:{text(event['notes'])}")
    for minutes in event.get("reminders", []):
        lines += ["BEGIN:VALARM", "ACTION:DISPLAY", f"DESCRIPTION:{text(event['title'])}",
                  f"TRIGGER:-PT{minutes}M", "END:VALARM"]
    lines.append("END:VEVENT")
    return lines


def slug(name):
    return re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")


def main():
    args = sys.argv[1:]
    today = dt.date.today()
    if "--today" in args:
        i = args.index("--today")
        today = dt.date.fromisoformat(args[i + 1])
        del args[i:i + 2]
    if len(args) != 1:
        sys.exit(__doc__)
    out = args[0]
    manifest = json.load(open(os.path.join(HERE, "manifest.json")))
    # ⚠ The run's day, not the clock: DTSTAMP only says when the file was made, and a clock value
    # would make two runs on one day write different files.
    now_utc = today.strftime("%Y%m%dT000000Z")
    names = [c["name"] for c in manifest["calendars"]]
    for event in manifest["events"]:
        if event["calendar"] not in names:
            sys.exit(f"generate_calendar: {event['title']!r} names no calendar ({event['calendar']!r})")

    os.makedirs(out, exist_ok=True)
    listing = []
    for cal in manifest["calendars"]:
        events = [e for e in manifest["events"] if e["calendar"] == cal["name"]]
        lines = ["BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Tenshu//Store fixture//EN",
                 f"X-WR-CALNAME:{text(cal['name'])}"]
        for n, event in enumerate(events):
            lines += vevent(event, f"{slug(cal['name'])}-{n:02d}@fixture.tenshu.invalid", today,
                            manifest["historyWeeks"], now_utc)
        lines.append("END:VCALENDAR")
        file = f"{slug(cal['name'])}.ics"
        with open(os.path.join(out, file), "w", encoding="utf-8", newline="") as f:
            f.write("\r\n".join(fold(l) for l in lines) + "\r\n")
        listing.append({"name": cal["name"], "colorArgb": "FF" + cal["color"].upper(),
                        "default": bool(cal.get("default")), "file": file, "events": len(events)})
    with open(os.path.join(out, "calendars.json"), "w") as f:
        json.dump({"today": today.isoformat(), "calendars": listing}, f, indent=2)
    print(f"generate_calendar: {sum(c['events'] for c in listing)} events in {len(listing)} calendars "
          f"around {today.isoformat()} → {out}")


if __name__ == "__main__":
    main()
