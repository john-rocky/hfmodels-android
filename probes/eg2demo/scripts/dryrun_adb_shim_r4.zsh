# Dry-run stand-in for adb for run_device_r4_cleanup.zsh, sourced through ADB_SHIM: a zsh function named adb that logs
# every call to $DRY_LOG and acts on a fake phone under $DRY_ROOT (K/out/dryrun_r4*/phone): a device path /sdcard/...
# is $DRY_ROOT/sdcard/..., and the package is installed while $DRY_ROOT/installed exists. It never runs the adb binary
# and touches nothing outside $DRY_ROOT.
#   DRY_GONE=1  the phone is not on adb (get-state fails)
#   DRY_SLOW=1  `ls -laR` takes 5 s (the watchdog test, with a small DEADLINE_S)
DRY_LOG=${DRY_LOG:-${OUT_DIR:?OUT_DIR}/adb_calls.log}
[[ ${DRY_ROOT:?DRY_ROOT} == */out/dryrun_r4*/phone && -d $DRY_ROOT ]] || { print -r -- "shim: DRY_ROOT must be an existing K/out/dryrun_r4*/phone"; exit 1; }
DRY_SAFE='^[A-Za-z0-9][A-Za-z0-9._-]*$'

# Device path -> fake path (only /sdcard/..., no ..); fails otherwise.
dry_map() {
  [[ $1 == /sdcard/* && $1 != *..* ]] || return 1
  print -r -- "$DRY_ROOT$1"
}

# ls with the flags the script sends; the output names device paths, as the phone would.
dry_ls() {  # $1 = flags ("" | -1 | -la | -laR | -d), $2 = device path, $3 = 1 when stderr is shown
  local f
  f=$(dry_map $2) || { print -r -- "shim: unmapped path $2"; return 1; }
  if [[ ! -e $f ]]; then
    (( $3 )) && print -r -- "ls: $2: No such file or directory"
    return 1
  fi
  [[ $1 == -laR && ${DRY_SLOW:-0} == 1 ]] && sleep 5
  if [[ -n $1 ]]; then command ls $1 $f; else command ls $f; fi | sed "s|$DRY_ROOT||g"
}

dry_shell() {
  local c=$1 d f n err=0
  [[ $c == *' 2>&1' ]] && err=1
  c=${c% 2>&1}
  c=${c% 2>/dev/null}
  case $c in
    'echo uptime'*)
      print -r -- "uptime 470000.00"; print -r -- "Thermal Status: 0"; print -r -- "    mWakefulness=Dozing"
      print -r -- "/dev/block/dm-50  452G  441G   11G  98% /data" ;;
    'pm list packages '*) [[ -e $DRY_ROOT/installed ]] && print -r -- "package:${c#pm list packages }" ;;
    'am force-stop '*) ;;
    'ls -laR '*) dry_ls -laR ${c#ls -laR } $err ;;
    'ls -la '*) dry_ls -la ${c#ls -la } $err ;;
    'ls -1 '*) dry_ls -1 ${c#ls -1 } $err ;;
    'ls -d '*) dry_ls -d ${c#ls -d } $err ;;
    'ls '*) dry_ls "" ${c#ls } $err ;;
    'cd '*' && rm -f '*)
      d=${${c#cd }%% && rm -f *}
      f=$(dry_map $d) || { print -r -- "shim: unmapped path $d"; return 1; }
      [[ -d $f ]] || { print -r -- "sh: cd: $d: No such file or directory"; return 1; }
      for n in ${=${c#* && rm -f }}; do
        [[ $n =~ $DRY_SAFE ]] || { print -r -- "shim: refused name $n"; continue; }
        command rm -f -- "$f/$n"
      done ;;
    'rmdir '*)
      for d in ${=${c#rmdir }}; do
        f=$(dry_map $d) || { print -r -- "shim: unmapped path $d"; continue; }
        command rmdir -- "$f" 2>/dev/null || print -r -- "rmdir '$d': $([[ -e $f ]] && print -r -- 'Directory not empty' || print -r -- 'No such file or directory')"
      done ;;
    'rm -f /sdcard/'*)
      f=$(dry_map ${c#rm -f }) || return 1
      command rm -f -- "$f" ;;
    *) print -r -- "shim: unhandled shell command: $c" ;;
  esac
}

adb() {
  print -r -- "adb $*" >> $DRY_LOG
  [[ ${1:-} == -s ]] && shift 2
  local sub=${1:-}
  shift
  case $sub in
    get-state)
      [[ ${DRY_GONE:-0} == 1 ]] && { print -r -- "error: device 'RFGL80R6A6H' not found"; return 1; }
      print -r -- device ;;
    uninstall)
      if [[ -e $DRY_ROOT/installed && $1 =~ $DRY_SAFE ]]; then
        command rm -f -- "$DRY_ROOT/installed"
        command rm -rf -- "${DRY_ROOT:?}/sdcard/Android/data/$1"
        print -r -- Success
      else
        print -r -- "Failure [DELETE_FAILED_INTERNAL_ERROR]"
      fi ;;
    shell) dry_shell "$*" ;;
    *) print -r -- "shim: unhandled adb $sub" ;;
  esac
}
