# Dino Walker shell integration for bash and zsh.
#
# Defines `start dino` / `exit dino` (and `dino start` / `dino exit`) for the IDE terminal.
# Everything else is passed through untouched: a plain `exit`, `exit 3`, `start foo`, ...
#
# The IDE sources this automatically (via JEDITERM_SOURCE). To load it yourself, add this
# line to ~/.bashrc or ~/.zshrc; outside the IDE terminal it does nothing:
#   [ -n "$DINO_WALKER_INIT" ] && . "$DINO_WALKER_INIT"

__dino_notify() {
  if [ -z "${DINO_WALKER_STATE_DIR-}" ] || [ ! -d "$DINO_WALKER_STATE_DIR" ]; then
    printf 'dino: not connected to the IDE (open a new terminal tab, or use Find Action: Dino: Start)\n' >&2
    return 1
  fi
  # Write to a temp file and rename it, so the IDE never reads a half-written command.
  command printf '%s\n' "$1" > "$DINO_WALKER_STATE_DIR/command.$$.tmp" &&
    command mv -f "$DINO_WALKER_STATE_DIR/command.$$.tmp" "$DINO_WALKER_STATE_DIR/command"
}

exit() {
  # Remember the caller's status first: a bare `exit` must exit with it, not with the
  # status of the test below.
  __dino_status=$?
  if [ "$#" -eq 1 ] && [ "$1" = "dino" ]; then
    __dino_notify exit
    return $?
  fi
  if [ "$#" -eq 0 ]; then
    builtin exit "$__dino_status"
  fi
  builtin exit "$@"
}

start() {
  if [ "$#" -eq 1 ] && [ "$1" = "dino" ]; then
    __dino_notify start
    return $?
  fi
  command start "$@"
}

dino() {
  case "${1-}" in
    start) __dino_notify start ;;
    exit|stop) __dino_notify exit ;;
    *) printf 'usage: dino start | dino exit\n' >&2; return 2 ;;
  esac
}
