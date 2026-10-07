# list - discover profiles, modules and elements.
cmd_list() {
  printf 'profiles:\n'; ls -1 "$PROFILES_DIR" 2>/dev/null | sed 's/\.sh$//' | sed 's/^/  /'
  printf 'modules:\n';  ls -1 "$MODULES_DIR"  2>/dev/null | sed 's/\.sh$//' | sed 's/^/  /'
  printf 'elements:\n'; ls -1 "$ELEMENTS_DIR" 2>/dev/null | sed 's/\.sh$//' | sed 's/^/  /'
  return 0
}
