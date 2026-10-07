# Dry-run stand-in for adb, sourced by gate.sh / take.sh through ADB_SHIM: a zsh function named adb that logs every
# call to $DRY_LOG and answers the reads the scripts parse. It never runs the adb binary, so no phone is touched.
#   ADB_SHIM=K/scripts/dryrun_adb_shim.zsh OUT_DIR=<dir> HOLD_FILE=<fake hold> K/scripts/gate.sh
#   DRY_MIC=1: take.sh MODE=mic gets a RESULT once screenrecord has started (a user who spoke).
#   DRY_SHORT_RECORDS=1: gate.sh's 120 ms mic press records a clip (the app has no length floor), so the tap runs too.
#   DRY_NPU_CRASH=1: an npu launch dies without an ERROR line (gate.sh stops after two such legs running).
#   DRY_MEM_LOW=1: MemAvailable reads 1,500,000 kB while a leg runs (gate.sh stops).
#   DRY_NO_DONE=1: a leg never logs DONE (gate.sh's stall and 28 min lines).
# Round 3 (the app with LAYOUT's mic_px / frame_px, PILL, PLAY_START, WARMUP, RESULT_LAYOUT, the mic floors):
#   a 1500 ms press with nobody speaking logs MIC_REJECTED reason=quiet (DRY_LOUD=1: a RESULT, a room louder than the
#   floor), a 300 ms press reason=short; an autoplay intent with a comma list plays and answers each clip;
#   `ls` of files/, files/album and files/queries lists what K has (DRY_MISSING=<name>: that one file is missing).
# The device clock is a counter: each `date '+%m-%d …'` read is one second later and remembers how many calls had been
# logged, so `logcat -T <time>` answers only for the commands sent after that time (the scripts' per-step cuts).
DRY_LOG=${DRY_LOG:-${OUT_DIR:?OUT_DIR}/adb_calls.log}
DRY_CLOCK=$DRY_LOG.clock
DRY_K=${0:A:h:h}
DRY_PKG_FILES=/storage/emulated/0/Android/data/com.mlboydaisuke.eg2demo/files
DRY_PKG_FILES_SD=/sdcard/Android/data/com.mlboydaisuke.eg2demo/files

# The commands sent after the time in a logcat's `-T` (all of them without one).
dry_since() {
  local a=$1 n L
  if [[ $a =~ '-T ([0-9]+)-([0-9]+) ([0-9]+):([0-9]+):([0-9]+)' ]]; then
    n=$(( match[4] * 60 + match[5] ))
    L=$(sed -n "${n}p" $DRY_CLOCK 2>/dev/null | cut -d' ' -f2)
    tail -n +$(( ${L:-0} + 1 )) $DRY_LOG
  else
    cat $DRY_LOG
  fi
}

# The Eg2Demo lines the app would have logged for the commands in $1.
dry_app_lines() {
  local h=$1 F=$DRY_PKG_FILES t='10-08 03:00:00.100  4242  4242 I Eg2Demo:' e='10-08 03:00:00.100  4242  4242 E Eg2Demo:'
  has() { print -r -- $h | /usr/bin/grep -q -E -- "$1"; }
  if has 'am start.*no-such\.litertlm'; then
    print -r -- "$t RUN_JSON path=$F/Documents/eg2-demo-1791400000001.json"
    print -r -- "$e ERROR bundle missing $F/no-such.litertlm"
    return 0
  fi
  if has 'am start.*--es backend'; then
    print -r -- "$t RUN_JSON path=$F/Documents/eg2-demo-1791400000000.json"
    print -r -- "$t BUNDLE name=embeddinggemma-2-740m.litertlm bytes=484622336 sha256_12=e7a8a2204b91 sha_ms=900.0"
    print -r -- "$t MODEL_INFO {\"type\":\"EMBEDDING\",\"backends_audio\":[\"CPU\",\"GPU\"]}"
    [[ ${DRY_NPU_CRASH:-0} == 1 ]] && has 'am start.*--es backend npu' && return 0
    if has 'am start.*--es backend npu.*--es audio_backend npu'; then
      print -r -- "$e ERROR init dry run: no NPU section for the audio encoder"
      return 0
    fi
    print -r -- "$t ENGINE_READY init_ms=2100.0 backend=gpu vision_backend=gpu audio_backend=cpu bundle=embeddinggemma-2-740m.litertlm"
    print -r -- "10-08 03:00:00.250  4242  4242 I tflite  : Replacing 1234 out of 1234 node(s) with delegate (TfLiteGpuDelegateV2) node, yielding 1 partitions."
    print -r -- "$t INDEX_DONE n=36 total_ms=3000.0 per_image_ms_median=80.0 cached=false reindex=true"
    print -r -- "$t WARMUP ms=180.0 seconds=1.00 audio_backend=cpu"
    print -r -- "$t PILL rgb=#5F6368 epoch_ms=1791399999000 text=READY"
    print -r -- "$t READY album=36 queries=43"
    print -r -- "$t LAYOUT {\"screen_px\":[1080,2340],\"density\":3.0,\"font_scale\":1.0,\"pill_px\":{\"left\":60,\"top\":330,\"width\":230,\"height\":84,\"pad_left\":36},\"level_px\":{\"left\":60,\"top\":1606,\"width\":960,\"height\":18},\"mic_px\":{\"left\":60,\"top\":1716,\"width\":960,\"height\":192},\"frame_px\":{\"left\":0,\"top\":132,\"width\":1080,\"height\":1920},\"status_bar_px\":132,\"title_top_px\":156,\"footer_bottom_px\":2028,\"title_to_footer_px\":1872,\"title_lines\":1,\"footer_lines\":2,\"pill_live_rgb\":\"#E53935\"}"
    if has 'run_queries all'; then
      print -r -- "$t PLAN jobs=103 delay_ms=0 gap_ms=300"
      print -r -- "$t QUERY_START epoch_ms=1791400005000 id=q01_a source=file play=0"
      print -r -- "$t RESULT kind=audio id=q01_a seconds=2.58 embed_ms=350.0 rank_ms=0.20 top1=a01 top1_cos=0.7235 top3=a01:0.7235,a34:0.5855,a12:0.5831 gold=a01 hit1=1 hit3=1"
      print -r -- "$t RESULT kind=text id=q01_t_card seconds=- embed_ms=40.0 rank_ms=0.20 top1=a01 top1_cos=0.7671 top3=a01:0.7671,a12:0.6546,a34:0.6446 gold=a01 hit1=1 hit3=1"
      [[ ${DRY_NO_DONE:-0} == 1 || ${DRY_MEM_LOW:-0} == 1 ]] || print -r -- "$t DONE json=$F/Documents/eg2-demo-1791400000000.json"
    fi
  fi
  if has 'autoplay true'; then
    local ql q n=0
    ql=$(print -r -- $h | /usr/bin/grep -o -E -- '--es query [^ ]+' | tail -1 | sed 's/--es query //')
    for q in ${(s:,:)ql}; do
      n=$(( n + 1 ))
      print -r -- "$t QUERY_START epoch_ms=$(( 1791400005000 + n * 5000 )) id=${q%.wav} source=file play=1 seconds=2.58"
      print -r -- "$t PILL rgb=#E53935 epoch_ms=$(( 1791400005000 + n * 5000 )) text=● audio file"
      print -r -- "$t PLAY_START epoch_ms=$(( 1791400005001 + n * 5000 )) id=${q%.wav}"
      print -r -- "$t PLAY_HEAD epoch_ms=$(( 1791400005090 + n * 5000 )) id=${q%.wav} frames=800"
      print -r -- "$t PILL rgb=#1565C0 epoch_ms=$(( 1791400007600 + n * 5000 )) text=EMBEDDING"
      print -r -- "$t RESULT kind=audio id=${q%.wav} seconds=2.58 embed_ms=104.0 rank_ms=0.20 top1=a01 top1_cos=0.7235 top3=a01:0.7235,a34:0.5855,a12:0.5831 gold=a01 hit1=1 hit3=1"
      print -r -- "$t PILL rgb=#2E7D32 epoch_ms=$(( 1791400007710 + n * 5000 )) text=DONE"
      (( n == 1 )) && print -r -- "$t RESULT_LAYOUT {\"photo_px\":{\"left\":60,\"top\":456,\"width\":960,\"height\":606},\"chip_gap_px\":30}"
    done
    print -r -- "$t DONE json=$F/Documents/eg2-demo-1791400000000.json"
  fi
  if [[ ${DRY_MIC:-0} == 1 ]] && has 'screenrecord'; then
    print -r -- "$t QUERY_START epoch_ms=1791400005000 id=mic-1791400005000 source=mic play=0"
    print -r -- "$t MIC_RECORDED bytes=70000 seconds=2.19 rms=0.01200 wav=$F/mic/1791400005000.wav"
    print -r -- "$t RESULT kind=audio id=mic-1791400005000 seconds=2.19 embed_ms=104.0 rank_ms=0.20 top1=a01 top1_cos=0.7235 top3=a01:0.7235,a34:0.5855,a12:0.5831 gold=- hit1=- hit3=-"
  fi
  if has 'input swipe( [0-9]+){4} 600$'; then
    print -r -- "10-08 03:00:00.100  4242  4242 W Eg2Demo: MIC_PERMISSION missing"
    has 'KEYCODE_BACK' && print -r -- "$t MIC_PERMISSION granted=true"
  fi
  if has 'input swipe( [0-9]+){4} 1500$'; then
    print -r -- "$t QUERY_START epoch_ms=1791400006000 id=mic-1791400006000 source=mic play=0"
    if [[ ${DRY_LOUD:-0} == 1 ]]; then
      print -r -- "$t MIC_RECORDED bytes=48000 seconds=1.50 rms=0.00230 wav=$F/mic/1791400006000.wav"
      print -r -- "$t RESULT kind=audio id=mic-1791400006000 seconds=1.50 embed_ms=300.0 rank_ms=0.20 top1=a15 top1_cos=0.5011 top3=a15:0.5011,a03:0.4900,a09:0.4800 gold=- hit1=- hit3=-"
    else
      print -r -- "10-08 03:00:00.100  4242  4242 W Eg2Demo: MIC_REJECTED reason=quiet bytes=43200 seconds=1.35 rms=0.00021 min_rms=0.00100 min_s=0.5 error=- wav=$F/mic/1791400006000.wav"
    fi
  fi
  if has 'input swipe( [0-9]+){4} 300$'; then
    print -r -- "$t QUERY_START epoch_ms=1791400008000 id=mic-1791400008000 source=mic play=0"
    print -r -- "10-08 03:00:00.100  4242  4242 W Eg2Demo: MIC_REJECTED reason=short bytes=8000 seconds=0.25 rms=0.00030 min_rms=0.00100 min_s=0.5 error=- wav=$F/mic/1791400008000.wav"
  fi
  if has 'input swipe( [0-9]+){4} 120$'; then
    if [[ ${DRY_SHORT_RECORDS:-0} == 1 ]]; then
      print -r -- "$t MIC_RECORDED bytes=1600 seconds=0.05 wav=$F/mic/1791400007000.wav"
      print -r -- "$t RESULT kind=audio id=mic-1791400007000 seconds=0.05 embed_ms=100.0 rank_ms=0.20 top1=a15 top1_cos=0.4011 top3=a15:0.4011,a03:0.3900,a09:0.3800 gold=- hit1=- hit3=-"
    else
      print -r -- "10-08 03:00:00.100  4242  4242 W Eg2Demo: MIC_REJECTED bytes=0 error=-"
    fi
  fi
  has 'input tap [0-9]+ [0-9]+$' && print -r -- "10-08 03:00:00.100  4242  4242 W Eg2Demo: MIC_REJECTED bytes=0 error=-"
  if has 'run_queries q01_a\.wav.*run_queries q01_a\.wav'; then
    print -r -- "$t PLAN jobs=1 delay_ms=0 gap_ms=2500"
    print -r -- "$t IGNORED busy source=intent"
    print -r -- "$t RESULT kind=audio id=q01_a seconds=2.58 embed_ms=350.0 rank_ms=0.20 top1=a01 top1_cos=0.7235 top3=a01:0.7235,a34:0.5855,a12:0.5831 gold=a01 hit1=1 hit3=1"
    print -r -- "$t DONE json=$F/Documents/eg2-demo-1791400000000.json"
  elif has 'am start.*run_queries q01_a\.wav'; then
    print -r -- "$t RESULT kind=audio id=q01_a seconds=2.58 embed_ms=350.0 rank_ms=0.20 top1=a01 top1_cos=0.7235 top3=a01:0.7235,a34:0.5855,a12:0.5831 gold=a01 hit1=1 hit3=1"
    print -r -- "$t DONE json=$F/Documents/eg2-demo-1791400000000.json"
  fi
  if has 'am start.*run_queries q13_a\.wav'; then
    print -r -- "$t RESULT kind=audio id=q13_a seconds=2.11 embed_ms=340.0 rank_ms=0.20 top1=a20 top1_cos=0.7000 top3=a20:0.7000,a03:0.6000,a34:0.5000 gold=a20 hit1=1 hit3=1"
    print -r -- "$t DONE json=$F/Documents/eg2-demo-1791400000000.json"
  fi
  return 0
}

adb() {
  print -r -- "adb $*" >> $DRY_LOG
  local a="$*" n h
  case $a in
    *" get-state"*) print -r -- "device" ;;
    *" shell ls $DRY_PKG_FILES_SD") print -r -- "Documents"; print -r -- "album"; print -r -- "queries"; print -r -- "mic"
      local bn
      for bn in embeddinggemma-2-740m.litertlm embeddinggemma-2-740m_Qualcomm_SM8850.litertlm index_embeddinggemma-2-740m.litertlm_gpu.json; do
        [[ $bn == ${DRY_MISSING:-none} ]] || print -r -- $bn
      done ;;
    *" shell ls $DRY_PKG_FILES_SD/album")
      local fa
      for fa in $DRY_K/fixtures/album/*.jpg; do [[ ${fa:t} == ${DRY_MISSING:-none} ]] || print -r -- ${fa:t}; done ;;
    *" shell ls $DRY_PKG_FILES_SD/queries")
      local fq
      for fq in $DRY_K/fixtures/queries/*.wav $DRY_K/fixtures/queries.json; do [[ ${fq:t} == ${DRY_MISSING:-none} ]] || print -r -- ${fq:t}; done ;;
    *"exec screenrecord"*) print -r -- "REC_SHELL_EPOCH 1791400000050" ;;
    *"settings get secure edge_enable"*) print -r -- "${DRY_EDGE:-1}" ;;
    *"pidof screenrecord); [ -n"*) ;;
    *"echo uptime"*)
      print -r -- "uptime 1234.56"
      print -r -- "Thermal Status: 0"
      print -r -- "Temperature{mValue=35.0, mType=3, mName=SKIN, mStatus=0}"
      print -r -- "/sys/devices/system/cpu/cpufreq/policy0 2000000 2000000"
      print -r -- "kgsl: max_clock_mhz=1300 thermal_pwrlevel=0 temp=40000"
      print -r -- "mem: MemAvailable: 6000000 kB"
      print -r -- "litert_procs: 0"
      print -r -- "power: mWakefulness=Dozing"
      print -r -- "airplane_mode_on: 1"
      print -r -- "data_free: /dev/block/dm-0 220G 209G 11G 96% /data" ;;
    *"top -b -n 1"*)
      print -r -- "Tasks: 900 total,   1 running, 899 sleeping"
      print -r -- "  PID USER         PR  NI VIRT  RES  SHR S[%CPU] %MEM     TIME+ ARGS"
      print -r -- " 1234 shell        20   0  10M 4.0M 3.0M R  3.0   0.0   0:00.02 top -b -n 1" ;;
    *"scaling_max_freq) ="*) ;;                                   # ready(): nothing = ready
    *"kgsl-3d0/temp"*) print -r -- "40000" ;;
    *"sha256sum"*)
      local remote=${${a##*sha256sum \'}%\'*} f
      for f in $DRY_K/fixtures/album/${remote:t} $DRY_K/fixtures/queries/${remote:t} $DRY_K/fixtures/${remote:t} \
               ${MODELS_DIR:-$HOME/.cache/eg2demo}/${remote:t}; do
        [[ -f $f ]] && { print -r -- "$(shasum -a 256 $f | cut -d' ' -f1)  $remote"; return 0; }
      done
      print -r -- "0000  $remote" ;;
    *"pidof"*) print -r -- "4242" ;;
    *"date '+%m-%d"*)
      [[ -f $DRY_CLOCK ]] || : > $DRY_CLOCK
      n=$(( $(wc -l < $DRY_CLOCK) + 1 ))
      print -r -- "$n $(wc -l < $DRY_LOG | tr -d ' ')" >> $DRY_CLOCK
      printf '10-08 03:%02d:%02d.000\n' $(( n / 60 )) $(( n % 60 )) ;;
    *"date +%s%3N"*) print -r -- "1791400000123" ;;
    *"mWakefulness"*) print -r -- "mWakefulness=Awake" ;;
    *"font_scale"*) print -r -- "1.0" ;;
    *"dumpsys package"*)
      local lr lg
      lr=$(/usr/bin/grep -n "pm revoke" $DRY_LOG | tail -1 | cut -d: -f1)
      lg=$(/usr/bin/grep -n "pm grant" $DRY_LOG | tail -1 | cut -d: -f1)
      if (( ${lr:-0} > ${lg:-0} )); then print -r -- "android.permission.RECORD_AUDIO: granted=false, flags=[ USER_SENSITIVE_WHEN_GRANTED ]"
      else print -r -- "android.permission.RECORD_AUDIO: granted=true, flags=[ USER_SENSITIVE_WHEN_GRANTED ]"; fi ;;
    *"dumpsys activity activities"*)
      local ls lb
      ls=$(/usr/bin/grep -n -E 'input swipe( [0-9]+){4} 600$' $DRY_LOG | tail -1 | cut -d: -f1)
      lb=$(/usr/bin/grep -n "KEYCODE_BACK" $DRY_LOG | tail -1 | cut -d: -f1)
      if (( ${ls:-0} > ${lb:-0} )); then print -r -- "topResumedActivity=ActivityRecord{1 u0 com.google.android.permissioncontroller/com.android.permissioncontroller.permission.ui.GrantPermissionsActivity t9}"
      else print -r -- "topResumedActivity=ActivityRecord{2 u0 com.mlboydaisuke.eg2demo/.MainActivity t9}"; fi ;;
    "-s "*" pull "*)
      local dst=${a##* }
      print -r -- '{"dry_run": true}' > $dst
      print -r -- "1 file pulled (dry run)" ;;
    *"ls"*"/mic/"*)
      /usr/bin/grep -q -E 'input swipe( [0-9]+){4} 1500$' $DRY_LOG && print -r -- "-rw-rw---- 1 u0_a300 ext_data_rw 48044 2026-10-08 03:10 1791400006000.wav" ;;
    *"cgroup"*) print -r -- "4:cpuset:/top-app" ;;
    *"echo gone"*)
      if [[ ${DRY_NPU_CRASH:-0} == 1 ]] && /usr/bin/grep "am start" $DRY_LOG | tail -1 | /usr/bin/grep -q -- "--es backend npu"; then
        print -r -- "gone"
      else
        print -r -- "up"
      fi
      [[ ${DRY_MEM_LOW:-0} == 1 ]] && print -r -- "MemAvailable:    1500000 kB" || print -r -- "MemAvailable:    6000000 kB" ;;
    *"[ -d "*) print -r -- "yes" ;;
    *" logcat "*"-b crash"*) ;;
    *" logcat "*"lowmemorykiller"*) print -r -- "--------- beginning of main" ;;
    *" logcat "*"-b events"*)
      h=$(dry_since "$a")
      if print -r -- $h | /usr/bin/grep -q KEYCODE_BACK; then
        print -r -- "10-08 03:00:00.300  1500  1600 I wm_finish_activity: [0,12345,9,com.mlboydaisuke.eg2demo/.MainActivity,app-request]"
        print -r -- "10-08 03:00:00.900  4242  4242 I wm_on_destroy_called: [0,12345,com.mlboydaisuke.eg2demo.MainActivity,performDestroy,10]"
        print -r -- "10-08 03:00:00.950  1500  1600 I wm_destroy_activity: [0,12345,9,com.mlboydaisuke.eg2demo/.MainActivity,finish-imm:idle]"
      fi ;;
    *" logcat "*)
      h=$(dry_since "$a")
      dry_app_lines "$h"
      [[ $a != *" -s "* ]] && print -r -- "10-08 03:00:00.260  4242  4250 I QnnHtp  : (dry run) no QNN on a GPU leg" ;;
    *" exec-out screencap"*) printf '\x89PNG\r\n\x1a\n' ;;
    *" install "*) print -r -- "Success" ;;
    *"am start"*) print -r -- "Starting: Intent { cmp=com.mlboydaisuke.eg2demo/.MainActivity (has extras) }" ;;
    *) ;;
  esac
  return 0
}
