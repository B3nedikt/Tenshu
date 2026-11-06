#!/usr/bin/env bash
# IN-3m-iOS driver (docs/proposals/ios-on-device-inference.md §4.4). Not a gate, not CI.
#
#   inference-harness-ios/run-in3m.sh build [device|sim]    # the Kotlin framework, then the app
#   inference-harness-ios/run-in3m.sh install                # the device build, onto the phone
#   inference-harness-ios/run-in3m.sh push-models <dir>      # vision_fp16 / text_fp16 .mlpackage.tar
#   inference-harness-ios/run-in3m.sh push-photos <dir>      # the corpus into Documents/photos/, then its manifest
#   inference-harness-ios/run-in3m.sh run <row> [vision|text] [recompile]
#   inference-harness-ios/run-in3m.sh report [file]          # copy Documents/in3m-report.txt back
#   inference-harness-ios/run-in3m.sh uninstall
#   inference-harness-ios/run-in3m.sh self-test              # no phone: the outcome check itself
#
# Rows: coreml-ane (what ships), coreml-all, coreml-cpu (the control). ONE launch per row: a
# footprint peak is a process-lifetime high-water mark, and `run` always terminates the last one.
# `run` exits 0 only for a row that ended MEASURED.
#
# The phone must be unlocked and, for a figure that compares with Android's, plugged in. A
# simulator build exists only to prove the app links; the harness refuses to measure on one.
#
# Env: TENSHU_IOS_DEVICE (default below), TENSHU_TEAM_ID (default: local.properties' tenshu.teamId).
# ⚠⚠ Never sign with the old personal team: that registers the bundle id to it for good.
set -uo pipefail

BUNDLE="app.tenshu.in3m.ios"
DEVICE="${TENSHU_IOS_DEVICE:-00008150-0011085C1E85401C}"
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
MODE="${1:-}"

if [ -z "${TENSHU_TEAM_ID:-}" ] && [ -f "$ROOT/local.properties" ]; then
  TENSHU_TEAM_ID="$(sed -n 's/^tenshu\.teamId=//p' "$ROOT/local.properties" | head -1)"
fi
export TENSHU_TEAM_ID="${TENSHU_TEAM_ID:-}"
if [ "$TENSHU_TEAM_ID" = "FAYT82GWD7" ]; then
  echo "run-in3m: TENSHU_TEAM_ID is the old personal team; a device build would register $BUNDLE to it for good."
  exit 2
fi

# judge <console log of one launch>: 0 only when the harness reported MEASURED.
# ⚠ `IN3M_DONE` alone is not success: the harness prints it for FAILED, NO_MODEL, NO_PHOTOS and
# UNKNOWN_ROW too, and a row that measured nothing must not read as one that did.
judge() {
  local outcome
  outcome="$(sed -n 's/.*IN3M_DONE \([A-Z_]*\).*/\1/p' "$1" | tail -1)"
  if [ -z "$outcome" ]; then
    echo "run-in3m: the row did not finish (no IN3M_DONE). Is the phone unlocked?"
    return 1
  fi
  if [ "$outcome" != "MEASURED" ]; then
    echo "run-in3m: the row ended as $outcome, not MEASURED — nothing was measured."
    return 1
  fi
  echo "run-in3m: outcome MEASURED"
}

# ⚠ An export directory is a complete one only if its manifest is there AND names these bytes: the
# export removes the manifest first and writes it last, so an interrupted promotion can leave both
# tar names present as a mismatched old/new pair. Side-loading that measures a model nobody exported.
verify_export() { # <export directory>
  local dir="$1" manifest="$1/export-manifest.json" role name want got
  [ -f "$manifest" ] || { echo "run-in3m: $dir has no export-manifest.json — not a complete export"; return 1; }
  for role in vision text; do
    name="${role}_fp16.mlpackage.tar"
    [ -f "$dir/$name" ] || { echo "run-in3m: no $dir/$name"; return 1; }
    want="$(/usr/bin/python3 -c 'import json,sys; a=json.load(open(sys.argv[1]))["artifacts"][sys.argv[2]]; print(a["sha256"] if a["file"]==sys.argv[3] else "")' "$manifest" "$role" "$name" 2>/dev/null)"
    [ -n "$want" ] || { echo "run-in3m: the manifest does not name $name as its $role artifact"; return 1; }
    got="$(shasum -a 256 "$dir/$name" | cut -d' ' -f1)"
    [ "$got" = "$want" ] || { echo "run-in3m: $name is not the file the manifest recorded (sha256 $got, manifest $want)"; return 1; }
  done
}

# corpus_manifest <corpus directory> <manifest file>: what the harness will measure, exactly.
#
# ⚠⚠ `devicectl` can copy to a container and list it, and cannot delete from it, so a push never
# removes what an earlier push left: 562 photos pushed over a 300-photo stand-in would be measured
# as their union. The harness therefore measures the files this manifest NAMES and nothing else,
# and refuses a named file that is missing or whose size or sha256 differs.
#
#   in3m-corpus 1
#   id=<sha256 of the "name<TAB>sha256" lines below, in this order>
#   count=<n>
#   <blank>
#   <sha256><TAB><bytes><TAB><basename>      one line a photo, in C-locale name order
corpus_manifest() {
  local dir="$1" out="$2" lines names photo name sum
  lines="$(mktemp)"; names="$(mktemp)"
  while IFS= read -r -d '' photo; do
    name="$(basename "$photo")"
    case "$name" in *$'\t'*|*$'\n'*) echo "run-in3m: '$name' has a tab or a newline in its name"; rm -f "$lines" "$names"; return 1 ;; esac
    sum="$(shasum -a 256 "$photo" | cut -d' ' -f1)"
    printf '%s\t%s\t%s\n' "$sum" "$(stat -f %z "$photo")" "$name" >> "$lines"
    printf '%s\t%s\n' "$name" "$sum" >> "$names"
  done < <(find "$dir" -maxdepth 1 -type f ! -name '.*' -print0 | LC_ALL=C sort -z)
  if [ ! -s "$lines" ]; then echo "run-in3m: no photos in $dir"; rm -f "$lines" "$names"; return 1; fi
  {
    echo "in3m-corpus 1"
    echo "id=$(shasum -a 256 "$names" | cut -d' ' -f1)"
    echo "count=$(wc -l < "$lines" | tr -d ' ')"
    echo
    cat "$lines"
  } > "$out"
  rm -f "$lines" "$names"
}

push() { # <source file> <destination inside the app's container>
  xcrun devicectl device copy to --device "$DEVICE" --domain-type appDataContainer \
    --domain-identifier "$BUNDLE" --source "$1" --destination "$2" >/dev/null
}

case "$MODE" in
  build)
    target="${2:-device}"
    if [ "$target" = "device" ]; then
      sdk="iphoneos"; dest="generic/platform=iOS"
      extra=(-allowProvisioningUpdates -allowProvisioningDeviceRegistration)
      [ -n "$TENSHU_TEAM_ID" ] || { echo "run-in3m: a device build needs TENSHU_TEAM_ID (or tenshu.teamId in local.properties)"; exit 2; }
    else
      sdk="iphonesimulator"; dest="generic/platform=iOS Simulator"; extra=()
    fi
    (cd "$ROOT" && ./gradlew :inference-harness-ios:assembleIn3mHarnessKitReleaseXCFramework) || exit 1
    (cd "$HERE" && xcodegen generate) || exit 1
    xcodebuild -project "$HERE/In3mHarness.xcodeproj" -scheme In3mHarness -configuration Release \
      -sdk "$sdk" -destination "$dest" -derivedDataPath "$HERE/build/dd-$target" \
      ${extra[@]+"${extra[@]}"} build || exit 1
    echo "run-in3m: built $HERE/build/dd-$target/Build/Products/Release-$sdk/In3mHarness.app"
    ;;
  install)
    xcrun devicectl device install app --device "$DEVICE" \
      "$HERE/build/dd-device/Build/Products/Release-iphoneos/In3mHarness.app"
    ;;
  push-models)
    dir="${2:?run-in3m: push-models needs the export directory}"
    verify_export "$dir" || exit 1
    for name in vision_fp16.mlpackage.tar text_fp16.mlpackage.tar; do
      echo "run-in3m: copying $name"
      push "$dir/$name" "Documents/$name" || { echo "run-in3m: copying $name failed"; exit 1; }
    done
    ;;
  push-photos)
    dir="${2:?run-in3m: push-photos needs the corpus directory}"
    manifest="$(mktemp)"
    corpus_manifest "$dir" "$manifest" || { rm -f "$manifest"; exit 1; }
    # One file at a time: whether `copy to` takes a directory is not established.
    count=0; failed=0
    while IFS= read -r -d '' photo; do
      if push "$photo" "Documents/photos/$(basename "$photo")"; then count=$((count + 1)); else failed=$((failed + 1)); fi
    done < <(find "$dir" -maxdepth 1 -type f ! -name '.*' -print0 | LC_ALL=C sort -z)
    echo "run-in3m: copied $count photos, $failed failed"
    if [ "$failed" -ne 0 ]; then rm -f "$manifest"; exit 1; fi
    # ⚠ LAST, and only after every photo landed: until then the phone holds the previous corpus's
    # manifest, which the half-replaced files no longer match, so a run is refused, not mixed.
    push "$manifest" "Documents/in3m-corpus.txt" || { echo "run-in3m: copying the corpus manifest failed"; rm -f "$manifest"; exit 1; }
    echo "run-in3m: corpus $(sed -n 's/^id=//p' "$manifest"), $(sed -n 's/^count=//p' "$manifest") photos"
    echo "run-in3m: photos an earlier push left in Documents/photos stay there (devicectl cannot delete); the harness ignores and lists them."
    rm -f "$manifest"
    ;;
  run)
    row="${2:?run-in3m: run needs a row (coreml-ane | coreml-all | coreml-cpu)}"
    tower="${3:-vision}"
    recompile=0; [ "${4:-}" = "recompile" ] && recompile=1
    log="$(mktemp)"
    xcrun devicectl device process launch --console --terminate-existing --device "$DEVICE" \
      --environment-variables "{\"IN3M_AUTORUN\":\"1\",\"IN3M_ROW\":\"$row\",\"IN3M_TOWER\":\"$tower\",\"IN3M_RECOMPILE\":\"$recompile\"}" \
      "$BUNDLE" 2>&1 | tee "$log"
    judge "$log"; verdict=$?
    rm -f "$log"
    exit "$verdict"
    ;;
  self-test)
    # No phone: `judge` against a console log for every outcome the harness can print.
    log="$(mktemp)"; bad=0
    expect() { # <expected exit> <log line>
      printf 'Launched application\n%s\nThe app terminated with the exit code 0.\n' "$2" > "$log"
      judge "$log" >/dev/null; got=$?
      if [ "$got" -ne "$1" ]; then echo "run-in3m self-test: '$2' judged $got, expected $1"; bad=1; fi
    }
    expect 0 "IN3M_DONE MEASURED"
    for outcome in FAILED NO_MODEL NO_PHOTOS CORPUS_REFUSED UNKNOWN_ROW REFUSED_SIMULATOR; do expect 1 "IN3M_DONE $outcome"; done
    expect 1 "outcome: MEASURED"
    expect 1 ""
    rm -f "$log"
    # `verify_export`: a complete export, one with no manifest, and a mismatched pair.
    exp="$(mktemp -d)"
    manifest_for() { # writes a manifest naming the directory's current bytes
      printf '{"artifacts":{"vision":{"file":"vision_fp16.mlpackage.tar","sha256":"%s"},"text":{"file":"text_fp16.mlpackage.tar","sha256":"%s"}}}' \
        "$(shasum -a 256 "$exp/vision_fp16.mlpackage.tar" | cut -d' ' -f1)" "$(shasum -a 256 "$exp/text_fp16.mlpackage.tar" | cut -d' ' -f1)" > "$exp/export-manifest.json"
    }
    expect_export() { # <expected exit> <what>
      verify_export "$exp" >/dev/null; got=$?
      if [ "$got" -ne "$1" ]; then echo "run-in3m self-test: export '$2' judged $got, expected $1"; bad=1; fi
    }
    printf 'vision-a' > "$exp/vision_fp16.mlpackage.tar"; printf 'text-a' > "$exp/text_fp16.mlpackage.tar"
    expect_export 1 "no manifest"
    manifest_for; expect_export 0 "complete"
    printf 'vision-b' > "$exp/vision_fp16.mlpackage.tar"; expect_export 1 "a vision archive the manifest did not record"
    manifest_for; rm "$exp/text_fp16.mlpackage.tar"; expect_export 1 "a missing text archive"
    rm -rf "$exp"
    # `corpus_manifest`: the golden corpus `CorpusManifestTest` reads, so the two sides agree on
    # the format; then that the id follows the bytes and the names, and that dot-files are out.
    cor="$(mktemp -d)"; man="$(mktemp)"
    printf 'alpha' > "$cor/a.jpg"; printf 'beta' > "$cor/b.jpg"; printf 'x' > "$cor/.DS_Store"
    corpus_manifest "$cor" "$man" >/dev/null || { echo "run-in3m self-test: corpus_manifest failed"; bad=1; }
    golden="15e951bef9a4ab29c1bfc0514ffbc7caf68be0035c62386d7b5f8e88403b8109"
    id_of() { sed -n 's/^id=//p' "$man"; }
    [ "$(id_of)" = "$golden" ] || { echo "run-in3m self-test: golden corpus id is $(id_of), expected $golden"; bad=1; }
    [ "$(sed -n 's/^count=//p' "$man")" = "2" ] || { echo "run-in3m self-test: golden corpus count is not 2"; bad=1; }
    [ "$(tail -1 "$man")" = "$(printf '%s\t4\tb.jpg' "$(printf 'beta' | shasum -a 256 | cut -d' ' -f1)")" ] || { echo "run-in3m self-test: unexpected manifest line: $(tail -1 "$man")"; bad=1; }
    printf 'betb' > "$cor/b.jpg"; corpus_manifest "$cor" "$man" >/dev/null
    [ "$(id_of)" != "$golden" ] || { echo "run-in3m self-test: the corpus id did not follow a changed photo"; bad=1; }
    printf 'beta' > "$cor/b.jpg"; mv "$cor/b.jpg" "$cor/c.jpg"; corpus_manifest "$cor" "$man" >/dev/null
    [ "$(id_of)" != "$golden" ] || { echo "run-in3m self-test: the corpus id did not follow a renamed photo"; bad=1; }
    rm "$cor/a.jpg" "$cor/c.jpg"
    if corpus_manifest "$cor" "$man" >/dev/null; then echo "run-in3m self-test: an empty corpus produced a manifest"; bad=1; fi
    rm -rf "$cor" "$man"
    [ "$bad" -eq 0 ] && echo "run-in3m self-test: OK"
    exit "$bad"
    ;;
  report)
    out="${2:-./in3m-ios.txt}"
    xcrun devicectl device copy from --device "$DEVICE" --domain-type appDataContainer \
      --domain-identifier "$BUNDLE" --source Documents/in3m-report.txt --destination "$out" >/dev/null || exit 1
    echo "run-in3m: report in $out"
    ;;
  uninstall)
    xcrun devicectl device uninstall app --device "$DEVICE" "$BUNDLE"
    ;;
  *)
    echo "run-in3m: build [device|sim] | install | push-models <dir> | push-photos <dir> | run <row> [vision|text] [recompile] | report [file] | uninstall | self-test"
    exit 2
    ;;
esac
