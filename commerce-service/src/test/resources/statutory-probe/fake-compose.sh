#!/usr/bin/env bash
# A stand-in for `docker compose …` against the commerce `db` service, for CommerceStatutoryProbeTest.
# State lives in $FAKE_STATE: `running` exists while db is up; `table.<name>` holds a table's row count
# (its absence = no such table). Every call is appended to $FAKE_STATE/calls.
st="$FAKE_STATE"
echo "$*" >> "$st/calls"
case "$1" in
  ps)   [ -e "$st/running" ] && echo fakecontainerid; exit 0 ;;
  up)   touch "$st/running"; exit 0 ;;
  exec)
    [ -e "$st/running" ] || { echo "service db is not running" >&2; exit 1; }
    shift 3   # exec -T db
    case "$1" in
      pg_isready) exit 0 ;;
      psql)
        sql="${*: -1}"
        case "$sql" in
          *to_regclass*)
            t="$(printf '%s' "$sql" | sed -n "s/.*public\.\([a-z_]*\)'.*/\1/p")"
            [ -e "$st/table.$t" ] && echo t || echo f ;;
          *"count(*) from "*)
            t="$(printf '%s' "$sql" | sed -n 's/.*from \([a-z_]*\);.*/\1/p')"
            cat "$st/table.$t" ;;
          *) echo "unexpected sql: $sql" >&2; exit 1 ;;
        esac ;;
    esac ;;
  *) echo "unexpected compose call: $*" >&2; exit 1 ;;
esac
