#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# check_build.sh — DevHive pre-build static checker
# Scans Kotlin source files for known compile-breaking patterns BEFORE running
# Gradle, so you get fast feedback without a full build.
#
# Usage:
#   ./check_build.sh            # static checks only (fast, ~2 s)
#   ./check_build.sh --compile  # static checks + real Gradle compile (~2 min)
# ─────────────────────────────────────────────────────────────────────────────

set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC="$ROOT/app/src/main/java"
ERRORS=0
WARNINGS=0

RED='\033[0;31m'; YELLOW='\033[0;33m'; GREEN='\033[0;32m'
CYAN='\033[0;36m'; BOLD='\033[1m'; RESET='\033[0m'

err()  { echo -e "${RED}[ERROR]${RESET} $*"; ((ERRORS++)) || true; }
warn() { echo -e "${YELLOW}[WARN] ${RESET} $*"; ((WARNINGS++)) || true; }
info() { echo -e "${CYAN}[INFO] ${RESET} $*"; }
ok()   { echo -e "${GREEN}[OK]   ${RESET} $*"; }

echo -e "${BOLD}═══════════════════════════════════════════════════${RESET}"
echo -e "${BOLD}  DevHive Build Checker${RESET}"
echo -e "${BOLD}═══════════════════════════════════════════════════${RESET}"
echo ""

KT_FILES=$(find "$SRC" -name "*.kt" | wc -l | tr -d ' ')
info "Scanning $KT_FILES Kotlin source files"
echo ""

# ────────────────────────────────────────────────────────────────────────────
# CHECK 1 — Material Icons that don't exist in the dependency version used
# ────────────────────────────────────────────────────────────────────────────
echo -e "${BOLD}[1/5] Material Icons — unknown icon references${RESET}"

MISSING_ICONS=(
    "FolderOpen"
    "Language"
    "OpenInBrowser"
    "DeveloperMode"
    "BugReport"
    "Dns"
    "CloudUpload"
    "CloudDownload"
    "Memory"
    "Terminal"
    "Code"
    "DataObject"
    "Javascript"
    "Html"
    "Css"
    "PhoneAndroid"
    "DesktopWindows"
    "HttpsOutlined"
    "StorageRounded"
    "Tune"
)

ICON_ERRORS=0
for icon in "${MISSING_ICONS[@]}"; do
    while IFS= read -r hit; do
        [[ -z "$hit" ]] && continue
        FILE=$(echo "$hit" | cut -d: -f1 | sed "s|$SRC/||")
        LINE=$(echo "$hit" | cut -d: -f2)
        err "Icons.Default.$icon may not exist → $FILE:$LINE"
        ((ICON_ERRORS++)) || true
    done < <(grep -rn "Icons\.Default\.$icon\b" "$SRC" 2>/dev/null || true)
done
[[ $ICON_ERRORS -eq 0 ]] && ok "No problematic icon references found"

# ────────────────────────────────────────────────────────────────────────────
# CHECK 2 — withContext/suspend called inside forEachLine (non-suspend lambda)
# ────────────────────────────────────────────────────────────────────────────
echo ""
echo -e "${BOLD}[2/5] Coroutines — withContext inside forEachLine (compile error)${RESET}"

COROUTINE_ERRORS=0
while IFS= read -r kt_file; do
    # Use awk to detect: a forEachLine block that contains withContext before its closing }
    # Strategy: track brace depth after forEachLine {, flag if withContext seen inside
    if awk '
        /\.forEachLine\s*\{/ { inside=1; depth=1; next }
        inside {
            for(i=1; i<=length($0); i++) {
                c = substr($0, i, 1)
                if (c == "{") depth++
                else if (c == "}") { depth--; if (depth==0) { inside=0; break } }
            }
            if (/withContext/) { found=1 }
        }
        END { exit !found }
    ' "$kt_file" 2>/dev/null; then
        FILE=$(echo "$kt_file" | sed "s|$SRC/||")
        err "withContext inside forEachLine in $FILE — use while/readLine() instead"
        ((COROUTINE_ERRORS++)) || true
    fi
done < <(find "$SRC" -name "*.kt" -exec grep -l "forEachLine" {} \;)

[[ $COROUTINE_ERRORS -eq 0 ]] && ok "No coroutine-in-plain-lambda issues found"

# ────────────────────────────────────────────────────────────────────────────
# CHECK 3 — Package declaration vs directory mismatch
# ────────────────────────────────────────────────────────────────────────────
echo ""
echo -e "${BOLD}[3/5] Package declarations — consistency check${RESET}"

PKG_ERRORS=0
while IFS= read -r kt_file; do
    DECLARED=$(grep -m1 "^package " "$kt_file" 2>/dev/null | sed 's/package //' | tr -d '\r ')
    [[ -z "$DECLARED" ]] && continue
    EXPECTED=$(echo "$kt_file" | sed "s|$SRC/||;s|/[^/]*\.kt$||;s|/|.|g")
    if [[ "$DECLARED" != "$EXPECTED" ]]; then
        FILE=$(echo "$kt_file" | sed "s|$SRC/||")
        warn "Package mismatch: declared='$DECLARED' expected='$EXPECTED' in $FILE"
        ((PKG_ERRORS++)) || true
    fi
done < <(find "$SRC" -name "*.kt")
[[ $PKG_ERRORS -eq 0 ]] && ok "All package declarations match directory structure"

# ────────────────────────────────────────────────────────────────────────────
# CHECK 4 — Duplicate top-level class/object/enum names across files
# ────────────────────────────────────────────────────────────────────────────
echo ""
echo -e "${BOLD}[4/5] Duplicate names — top-level classes & objects${RESET}"

declare -A CLASS_SEEN
DUP_ERRORS=0
while IFS= read -r kt_file; do
    FILE=$(echo "$kt_file" | sed "s|$SRC/||")
    # Extract top-level class/object/interface names using awk (no lookbehind needed)
    while IFS= read -r NAME; do
        [[ -z "$NAME" ]] && continue
        if [[ -n "${CLASS_SEEN[$NAME]+x}" ]]; then
            warn "Duplicate top-level name '$NAME':"
            warn "  ${CLASS_SEEN[$NAME]}"
            warn "  $FILE"
            ((DUP_ERRORS++)) || true
        else
            CLASS_SEEN[$NAME]=$FILE
        fi
    done < <(awk '/^(data |sealed |enum |abstract |open |private )*(class|object|interface) [A-Z]/ {
        for(i=1;i<=NF;i++) {
            if ($i=="class"||$i=="object"||$i=="interface") { print $(i+1); break }
        }
    }' "$kt_file" 2>/dev/null | tr -d '({<' || true)
done < <(find "$SRC" -name "*.kt")
[[ $DUP_ERRORS -eq 0 ]] && ok "No duplicate top-level names found"

# ────────────────────────────────────────────────────────────────────────────
# CHECK 5 — Optional real Gradle compile
# ────────────────────────────────────────────────────────────────────────────
echo ""
echo -e "${BOLD}[5/5] Gradle compile${RESET}"

if [[ "${1:-}" == "--compile" ]]; then
    info "Running ./gradlew compileDebugKotlin (may take 1-3 minutes)…"
    echo ""
    GRADLE_OK=0
    if "$ROOT/gradlew" compileDebugKotlin --no-daemon 2>&1 \
        | tee /tmp/_devhive_gradle.txt \
        | grep -E "^(BUILD|e: |w: )" | sed "s|file:///.*ollama-project/app/src/main/java/||"; then
        GRADLE_OK=1
    fi
    if grep -q "BUILD SUCCESSFUL" /tmp/_devhive_gradle.txt; then
        ok "Gradle compile succeeded ✓"
    else
        GRADLE_ERRS=$(grep -c "^e: " /tmp/_devhive_gradle.txt 2>/dev/null || echo 0)
        err "Gradle compile failed — $GRADLE_ERRS error(s) above"
    fi
else
    info "Skipping Gradle compile — pass --compile to run it"
fi

# ────────────────────────────────────────────────────────────────────────────
# Summary
# ────────────────────────────────────────────────────────────────────────────
echo ""
echo -e "${BOLD}═══════════════════════════════════════════════════${RESET}"
if [[ $ERRORS -gt 0 ]]; then
    echo -e "${RED}${BOLD}  RESULT: $ERRORS error(s), $WARNINGS warning(s) — BUILD LIKELY FAILS${RESET}"
    echo -e "${BOLD}═══════════════════════════════════════════════════${RESET}"
    exit 1
elif [[ $WARNINGS -gt 0 ]]; then
    echo -e "${YELLOW}${BOLD}  RESULT: 0 errors, $WARNINGS warning(s) — BUILD PROBABLY OK${RESET}"
    echo -e "${BOLD}═══════════════════════════════════════════════════${RESET}"
    exit 0
else
    echo -e "${GREEN}${BOLD}  RESULT: All checks passed ✓${RESET}"
    echo -e "${BOLD}═══════════════════════════════════════════════════${RESET}"
    exit 0
fi
