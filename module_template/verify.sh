#!/system/bin/sh
# ZDT-D module file verification helpers.
# Checks files that were already extracted by the root manager.

zdt_verify_print() {
  if command -v ui_print >/dev/null 2>&1; then
    ui_print "$*"
  else
    echo "$*"
  fi
}

zdt_verify_fail() {
  zdt_verify_print "! ZDT-D verify failed: $*"
  return 1
}

zdt_verify_module_files() {
  modpath="${1:-${MODPATH:-$PWD}}"
  sums_dir="$modpath/verify_sum"

  [ -d "$sums_dir" ] || zdt_verify_fail "verify_sum directory not found: $sums_dir" || return 1
  command -v sha256sum >/dev/null 2>&1 || zdt_verify_fail "sha256sum command not found" || return 1

  list_file="$modpath/.zdt_verify_sum_files.tmp"
  find "$sums_dir" -type f -name '*.sha256' 2>/dev/null | sort > "$list_file" 2>/dev/null || true
  if [ ! -s "$list_file" ]; then
    rm -f "$list_file" 2>/dev/null || true
    zdt_verify_fail "no checksum files found in verify_sum" || return 1
  fi

  checked=0
  failed=0

  while IFS= read -r sum_file; do
    [ -n "$sum_file" ] || continue
    rel="${sum_file#$sums_dir/}"
    rel="${rel%.sha256}"
    target="$modpath/$rel"

    if [ ! -f "$target" ]; then
      zdt_verify_print "! Missing file: $rel"
      failed=$((failed + 1))
      continue
    fi

    expected="$(awk 'NR==1 {print $1}' "$sum_file" 2>/dev/null)"
    actual="$(sha256sum "$target" 2>/dev/null | awk 'NR==1 {print $1}')"

    if [ -z "$expected" ] || [ -z "$actual" ] || [ "$expected" != "$actual" ]; then
      zdt_verify_print "! SHA-256 mismatch: $rel"
      zdt_verify_print "! expected: ${expected:-empty}"
      zdt_verify_print "! actual:   ${actual:-empty}"
      failed=$((failed + 1))
      continue
    fi

    checked=$((checked + 1))
    zdt_verify_print "- Verified: $rel"
  done < "$list_file"

  rm -f "$list_file" 2>/dev/null || true

  [ "$checked" -gt 0 ] || zdt_verify_fail "no module files were verified" || return 1
  [ "$failed" -eq 0 ] || zdt_verify_fail "$failed file(s) failed checksum verification" || return 1

  rm -rf "$sums_dir" 2>/dev/null || zdt_verify_fail "unable to remove verify_sum directory" || return 1
  zdt_verify_print "- Verified module files: $checked"
  return 0
}
