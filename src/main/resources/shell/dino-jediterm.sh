# Dino Walker: entry point sourced by the IDE terminal's shell integration (JEDITERM_SOURCE).

# Another plugin may have wanted JEDITERM_SOURCE too (e.g. virtualenv activation). Run it first,
# exactly the way the IDE would have.
if [ -n "${DINO_WALKER_PREV_SOURCE-}" ]; then
  __dino_prev_source=$DINO_WALKER_PREV_SOURCE
  __dino_prev_args=${DINO_WALKER_PREV_SOURCE_ARGS-}
  __dino_prev_single=${DINO_WALKER_PREV_SOURCE_SINGLE_ARG-}
  unset DINO_WALKER_PREV_SOURCE DINO_WALKER_PREV_SOURCE_ARGS DINO_WALKER_PREV_SOURCE_SINGLE_ARG
  if [ -n "$__dino_prev_single" ]; then
    . "$__dino_prev_source" "$__dino_prev_args"
  elif [ -n "${ZSH_VERSION-}" ]; then
    eval '. "$__dino_prev_source" ${=__dino_prev_args}'
  else
    . "$__dino_prev_source" $__dino_prev_args
  fi
  unset __dino_prev_source __dino_prev_args __dino_prev_single
fi

if [ -n "${DINO_WALKER_INIT-}" ] && [ -r "$DINO_WALKER_INIT" ]; then
  . "$DINO_WALKER_INIT"
fi
