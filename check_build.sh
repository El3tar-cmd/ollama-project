#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# check_build.sh — DevHive advanced build checker
#
# Runs two layers of checks:
#   Layer 1 (always): fast static analysis — icons, coroutines, packages
#   Layer 2 (default): real Gradle compile — catches EVERYTHING
#
# Usage:
#   ./check_build.sh           # static + Gradle compile (recommended)
#   ./check_build.sh --fast    # static checks only (~3 s, no Gradle)
# ─────────────────────────────────────────────────────────────────────────────

set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC="$ROOT/app/src/main/java"
ERRORS=0
WARNINGS=0
FAST_ONLY=false
[[ "${1:-}" == "--fast" ]] && FAST_ONLY=true

RED='\033[0;31m'; YELLOW='\033[0;33m'; GREEN='\033[0;32m'
CYAN='\033[0;36m'; BOLD='\033[1m'; RESET='\033[0m'

err()  { echo -e "${RED}[ERROR]${RESET} $*"; ((ERRORS++))  || true; }
warn() { echo -e "${YELLOW}[WARN] ${RESET} $*"; ((WARNINGS++)) || true; }
info() { echo -e "${CYAN}[INFO] ${RESET} $*"; }
ok()   { echo -e "${GREEN}[OK]   ${RESET} $*"; }

echo -e "${BOLD}═══════════════════════════════════════════════════${RESET}"
echo -e "${BOLD}  DevHive Build Checker${RESET}"
echo -e "${BOLD}═══════════════════════════════════════════════════${RESET}"
echo ""

KT_FILES=$(find "$SRC" -name "*.kt" | wc -l | tr -d ' ')
info "Scanning $KT_FILES Kotlin source files"
$FAST_ONLY && info "Mode: fast (static only)" || info "Mode: full (static + Gradle)"
echo ""

# ═══════════════════════════════════════════════════════════════════════════
# LAYER 1 — Static Analysis
# ═══════════════════════════════════════════════════════════════════════════

# ── CHECK 1: Material Icons (core-only project — no extended icons) ──────────
echo -e "${BOLD}[1/5] Material Icons — only 'core' icons are available${RESET}"

# These icons exist ONLY in material-icons-extended, NOT in core.
# Rule: if your build.gradle has only "material.icons.core" (not extended),
#       any icon not in this SAFE list is a compile error.

# Icons confirmed safe in material-icons-core (checked against AOSP source):
SAFE_ICONS=(
    "Add" "ArrowBack" "ArrowForward" "ArrowDropDown" "ArrowDropUp"
    "Build" "Call" "Check" "CheckCircle" "Clear" "Close" "Create"
    "DateRange" "Delete" "Done" "Edit" "Email" "Error" "ExitToApp"
    "Favorite" "FavoriteBorder" "Face"
    "Home" "Info"
    "KeyboardArrowDown" "KeyboardArrowLeft" "KeyboardArrowRight" "KeyboardArrowUp"
    "Lock" "List"
    "Menu" "MoreVert" "MoreHoriz"
    "Notifications" "NotificationsOff"
    "Person" "Phone" "PlayArrow" "Pause" "Place"
    "Refresh" "Remove"
    "Search" "Send" "Settings" "Share" "ShoppingCart" "Star" "StarBorder"
    "ThumbUp" "ThumbDown"
    "Visibility" "VisibilityOff"
    "Warning"
    "AccountBox" "AccountCircle"
    "ExpandLess" "ExpandMore"
    "FirstPage" "LastPage"
    "InsertDriveFile" "InsertChart"
    "ModeEdit"
)

# Check if app/build.gradle.kts actually *uses* extended (not just defines it in toml)
USES_EXTENDED=$(grep -r "material.icons.extended\|material-icons-extended" \
    "$ROOT/app/build.gradle" "$ROOT/app/build.gradle.kts" 2>/dev/null || true)

ICON_ERRORS=0
if [[ -z "$USES_EXTENDED" ]]; then
    info "Project uses material-icons-core only — checking against safe list"
    # grep -rno: each match on its own line → handles multiple icons per source line
    while IFS= read -r match; do
        [[ -z "$match" ]] && continue
        FILE=$(echo "$match" | cut -d: -f1 | sed "s|$SRC/||")
        LINE=$(echo "$match" | cut -d: -f2)
        ICON=$(echo "$match" | sed 's/.*Icons\.Default\.\([A-Za-z]*\).*/\1/')
        [[ -z "$ICON" ]] && continue
        SAFE=false
        for s in "${SAFE_ICONS[@]}"; do
            [[ "$s" == "$ICON" ]] && { SAFE=true; break; }
        done
        if ! $SAFE; then
            err "Icons.Default.$ICON not in material-icons-core → $FILE:$LINE"
            ((ICON_ERRORS++)) || true
        fi
    done < <(grep -rno "Icons\.Default\.[A-Za-z]*" "$SRC" 2>/dev/null || true)
else
    info "Project uses material-icons-extended — skipping icon check"
fi
[[ $ICON_ERRORS -eq 0 ]] && ok "All icon references are valid"

# ── CHECK 2: withContext/suspend inside non-suspend lambdas ──────────────────
echo ""
echo -e "${BOLD}[2/5] Coroutines — withContext inside forEachLine${RESET}"

COROUTINE_ERRORS=0
while IFS= read -r kt_file; do
    if awk '
        /\.forEachLine[[:space:]]*\{/ { inside=1; depth=1; next }
        inside {
            for(i=1;i<=length($0);i++){
                c=substr($0,i,1)
                if(c=="{") depth++
                else if(c=="}"){depth--;if(depth==0){inside=0;break}}
            }
            if(/withContext|suspend[[:space:]]*\(/) found=1
        }
        END{exit !found}
    ' "$kt_file" 2>/dev/null; then
        FILE=$(echo "$kt_file" | sed "s|$SRC/||")
        err "withContext inside forEachLine (not a suspend lambda) → $FILE"
        err "  Fix: replace forEachLine { } with: while(reader.readLine().also{line=it}!=null){ ... }"
        ((COROUTINE_ERRORS++)) || true
    fi
done < <(find "$SRC" -name "*.kt" -exec grep -l "forEachLine" {} \;)
[[ $COROUTINE_ERRORS -eq 0 ]] && ok "No coroutine-in-plain-lambda issues"

# ── CHECK 3: Package declaration matches directory ───────────────────────────
echo ""
echo -e "${BOLD}[3/5] Package declarations${RESET}"
PKG_ERRORS=0
while IFS= read -r kt_file; do
    DECLARED=$(grep -m1 "^package " "$kt_file" 2>/dev/null | sed 's/package //' | tr -d '\r ')
    [[ -z "$DECLARED" ]] && continue
    EXPECTED=$(echo "$kt_file" | sed "s|$SRC/||;s|/[^/]*\.kt$||;s|/|.|g")
    if [[ "$DECLARED" != "$EXPECTED" ]]; then
        FILE=$(echo "$kt_file" | sed "s|$SRC/||")
        err "Package mismatch in $FILE"
        err "  declared:  $DECLARED"
        err "  expected:  $EXPECTED"
        ((PKG_ERRORS++)) || true
    fi
done < <(find "$SRC" -name "*.kt")
[[ $PKG_ERRORS -eq 0 ]] && ok "All package declarations match"

# ── CHECK 4: Duplicate top-level names ──────────────────────────────────────
echo ""
echo -e "${BOLD}[4/5] Duplicate top-level class / object names${RESET}"
declare -A CLASS_SEEN
DUP_ERRORS=0
while IFS= read -r kt_file; do
    FILE=$(echo "$kt_file" | sed "s|$SRC/||")
    while IFS= read -r NAME; do
        [[ -z "$NAME" ]] && continue
        if [[ -n "${CLASS_SEEN[$NAME]+x}" ]]; then
            warn "Duplicate '$NAME': ${CLASS_SEEN[$NAME]} ↔ $FILE"
            ((DUP_ERRORS++)) || true
        else
            CLASS_SEEN[$NAME]=$FILE
        fi
    done < <(awk '
        /^[[:space:]]*(data |sealed |enum |abstract |open |private |internal )*(class|object|interface) [A-Z]/ {
            for(i=1;i<=NF;i++){
                if($i=="class"||$i=="object"||$i=="interface"){ gsub(/[({<].*/,"",$( i+1)); print $(i+1); break }
            }
        }' "$kt_file" 2>/dev/null || true)
done < <(find "$SRC" -name "*.kt")
[[ $DUP_ERRORS -eq 0 ]] && ok "No duplicate top-level names"

# ── CHECK 5: Common dangerous patterns ──────────────────────────────────────
echo ""
echo -e "${BOLD}[5/5] Common dangerous patterns${RESET}"
PATTERN_ERRORS=0

# 5a: !! (non-null assertion) on potentially null process streams
while IFS= read -r hit; do
    FILE=$(echo "$hit" | cut -d: -f1 | sed "s|$SRC/||")
    LINE=$(echo "$hit" | cut -d: -f2)
    warn "Unsafe !! operator (may crash) → $FILE:$LINE"
done < <(grep -rn "process!!\|stream!!" "$SRC" 2>/dev/null || true)

# 5b: runBlocking inside a coroutine (deadlock risk)
while IFS= read -r hit; do
    FILE=$(echo "$hit" | cut -d: -f1 | sed "s|$SRC/||")
    LINE=$(echo "$hit" | cut -d: -f2)
    warn "runBlocking inside coroutine scope — potential deadlock → $FILE:$LINE"
done < <(grep -rn "runBlocking" "$SRC" 2>/dev/null || true)

# 5c: GlobalScope usage (memory leak risk)
while IFS= read -r hit; do
    FILE=$(echo "$hit" | cut -d: -f1 | sed "s|$SRC/||")
    LINE=$(echo "$hit" | cut -d: -f2)
    warn "GlobalScope usage (prefer viewModelScope/lifecycleScope) → $FILE:$LINE"
done < <(grep -rn "GlobalScope\." "$SRC" 2>/dev/null || true)

# 5d: Thread.sleep in coroutine (blocks thread)
while IFS= read -r hit; do
    FILE=$(echo "$hit" | cut -d: -f1 | sed "s|$SRC/||")
    LINE=$(echo "$hit" | cut -d: -f2)
    warn "Thread.sleep in coroutine — use delay() instead → $FILE:$LINE"
done < <(grep -rn "Thread\.sleep" "$SRC" 2>/dev/null || true)

ok "Pattern checks complete"

# ═══════════════════════════════════════════════════════════════════════════
# LAYER 2 — Real Gradle Compile (catches everything else)
# ═══════════════════════════════════════════════════════════════════════════
echo ""
echo -e "${BOLD}[LAYER 2] Gradle compileDebugKotlin${RESET}"

if $FAST_ONLY; then
    info "Skipped (--fast mode). Run without --fast for full compile check."
else
    info "Running Gradle compile — this catches ALL remaining errors…"
    echo ""
    GRADLE_OUT=$(mktemp)

    set +e
    "$ROOT/gradlew" compileDebugKotlin --no-daemon --quiet 2>&1 | tee "$GRADLE_OUT"
    GRADLE_EXIT=${PIPESTATUS[0]}
    set -e

    if [[ $GRADLE_EXIT -eq 0 ]]; then
        ok "Gradle compile: BUILD SUCCESSFUL ✓"
    else
        echo ""
        # Parse and display each error cleanly
        while IFS= read -r eline; do
            CLEAN=$(echo "$eline" \
                | sed "s|file:///.*ollama-project/app/src/main/java/||" \
                | sed "s|^e: ||")
            err "Kotlin: $CLEAN"
        done < <(grep "^e: " "$GRADLE_OUT" || true)

        GRADLE_WARN_COUNT=$(grep -c "^w: " "$GRADLE_OUT" 2>/dev/null || true)
        [[ "$GRADLE_WARN_COUNT" -gt 0 ]] && warn "Gradle also reported $GRADLE_WARN_COUNT warning(s)"
    fi
    rm -f "$GRADLE_OUT"
fi

# ═══════════════════════════════════════════════════════════════════════════
# Summary
# ═══════════════════════════════════════════════════════════════════════════
echo ""
echo -e "${BOLD}═══════════════════════════════════════════════════${RESET}"
if [[ $ERRORS -gt 0 ]]; then
    echo -e "${RED}${BOLD}  RESULT: $ERRORS error(s), $WARNINGS warning(s) — BUILD FAILS${RESET}"
    echo -e "${BOLD}═══════════════════════════════════════════════════${RESET}"
    exit 1
elif [[ $WARNINGS -gt 0 ]]; then
    echo -e "${YELLOW}${BOLD}  RESULT: 0 errors, $WARNINGS warning(s) — BUILD OK (review warnings)${RESET}"
    echo -e "${BOLD}═══════════════════════════════════════════════════${RESET}"
    exit 0
else
    echo -e "${GREEN}${BOLD}  RESULT: All checks passed ✓  BUILD CLEAN${RESET}"
    echo -e "${BOLD}═══════════════════════════════════════════════════${RESET}"
    exit 0
fi
