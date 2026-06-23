#!/bin/bash
# DevHive — commit and push all changes to GitHub
# Run this from the project root inside Termux or any shell:
#   bash commit_and_push.sh

set -e

MSG="${1:-"Major agent architecture + Embedded Linux runtime (Debian arm64 via PRoot)"}"

echo "=== DevHive Git Push ==="
echo "Message: $MSG"
echo ""

git add -A
git status --short

echo ""
echo "Committing..."
git commit -m "$MSG"

echo ""
echo "Pushing to origin/main..."
if [ -n "$GITHUB_PERSONAL_ACCESS_TOKEN" ]; then
    REMOTE="https://${GITHUB_PERSONAL_ACCESS_TOKEN}@github.com/El3tar-cmd/ollama-project.git"
    git push "$REMOTE" main
else
    git push origin main
fi

echo ""
echo "Done!"
