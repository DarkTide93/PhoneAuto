#!/bin/sh
# Wraps app.html (the page body that gets published as the Claude artifact)
# into a standalone index.html you can open directly or host on GitHub Pages.
set -e
cd "$(dirname "$0")"
{
  cat <<'HEAD'
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<style>:root{color-scheme:light;padding-top:env(safe-area-inset-top,0px);padding-bottom:env(safe-area-inset-bottom,0px)}body{margin:0}img{max-width:100%}[hidden]{display:none!important}</style>
</head>
<body>
HEAD
  cat app.html
  printf '</body>\n</html>\n'
} > index.html
echo "Wrote $(pwd)/index.html"
