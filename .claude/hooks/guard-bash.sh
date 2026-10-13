#!/usr/bin/env bash
#
# Claude Code PreToolUse 훅. 에이전트가 운영 인프라를 바꾸거나 민감한 값에 닿는 셸 명령을 막는다.
#
# 막으면 종료 코드 2로 끝나고 이유를 stderr로 돌려준다. Claude는 그 이유를 보고 명령을 바꾼다.
# 사람이 터미널에서 직접 치는 명령에는 적용되지 않는다.
#
# 상세: docs/infra/README.md#에이전트-접근-제한
# 검증: .claude/hooks/guard-bash.test.sh

set -uo pipefail

block() {
  printf 'guard-bash: %s\n상세: docs/infra/README.md#에이전트-접근-제한\n' "$1" >&2
  exit 2
}

# 2가 아닌 실패 종료는 Claude Code가 통과로 처리한다. 검사 중 오류도 막는다.
trap 'rc=$?; [ "$rc" -eq 0 ] || [ "$rc" -eq 2 ] || { echo "guard-bash: 검사 중 오류(종료 코드 $rc)로 명령을 막았다." >&2; exit 2; }' EXIT

command -v jq >/dev/null 2>&1 || block "jq가 없어 명령을 검사할 수 없다. 사용자에게 brew install jq 설치를 요청한다."
cmd=$(jq -r '.tool_input.command // empty' 2>/dev/null) || block "훅 입력을 해석할 수 없다."
[ -n "$cmd" ] || exit 0

# 명령을 단순 명령 단위(줄)로 나누고 각 줄의 단어를 \037로 구분해 출력한다.
# - 따옴표 안은 단어 하나로 둔다. 큰따옴표 안의 $( )와 백틱은 \035로 시작하는 줄로 따로 출력해 다시 검사한다.
# - ; & | ( ) { } 백틱, $(, 줄바꿈에서 명령을 나눈다. 줄 끝의 \는 다음 줄과 잇는다.
# - here-document 본문은 \034로 시작하는 줄로 따로 출력한다. 셸이 받을 때만 명령으로 검사한다.
# - 입력 줄이 끝날 때마다 \033 줄을 출력한다. # 주석은 건너뛴다. 단어 안의 줄바꿈은 \036으로 바꾼다.
TOKENIZER='
function emit_tok() { if (intok) { line = line (nt ? SEP : "") tok; nt++; tok = ""; intok = 0 } }
function emit_seg() { emit_tok(); if (nt > 0) print line; line = ""; nt = 0 }
function enc(x) { gsub(/\n/, NL, x); return x }
function close_paren(str, p,   d, ch, n) {
  d = 1; n = length(str)
  while (p <= n) { ch = substr(str, p, 1); if (ch == "(") d++; else if (ch == ")") { d--; if (d == 0) return p } p++ }
  return n + 1
}
# 문자열 안의 $( )와 백틱 내용을 다시 검사할 명령으로 모은다.
function collect_subst(str,   p, n, ch, k, j) {
  n = length(str); p = 1
  while (p <= n) {
    ch = substr(str, p, 1)
    if (ch == "$" && substr(str, p + 1, 1) == "(") { k = close_paren(str, p + 2); subs[++ns] = substr(str, p + 2, k - p - 2); p = k + 1; continue }
    if (ch == "`") { j = index(substr(str, p + 1), "`"); if (j == 0) j = n - p + 1; subs[++ns] = substr(str, p + 1, j - 1); p += j + 1; continue }
    p++
  }
}
# here-document 본문을 \034로 시작하는 줄로 출력한다. 따옴표 없는 구분자면 본문의 $( )도 검사한다.
function read_heredocs(p,   h, e, ln, body) {
  for (h = 1; h <= nh; h++) {
    body = ""
    while (p <= L) {
      e = index(substr(s, p), "\n"); if (e == 0) e = L - p + 2
      ln = substr(s, p, e - 1); p += e
      if (hs[h]) sub(/^\t+/, "", ln)
      if (ln == hd[h]) break
      body = body ln "\n"
    }
    print HEREDOC enc(body)
    if (!hq[h]) collect_subst(body)
  }
  nh = 0
  return p
}
BEGIN { SEP = "\037"; NL = "\036"; SUBST = "\035"; HEREDOC = "\034"; EOL = "\033" }
{ s = s $0 "\n" }
END {
  L = length(s); i = 1
  while (i <= L) {
    c = substr(s, i, 1)
    if (c == "\\") {
      d = substr(s, i + 1, 1); i += 2
      if (d != "\n") { tok = tok d; intok = 1 }
      continue
    }
    if (c == Q) {
      j = index(substr(s, i + 1), Q); if (j == 0) j = L - i + 1
      tok = tok enc(substr(s, i + 1, j - 1)); intok = 1; i += j + 1; continue
    }
    if (c == "\"") {
      i++; intok = 1
      while (i <= L) {
        c = substr(s, i, 1)
        if (c == "\\") { tok = tok enc(substr(s, i + 1, 1)); i += 2; continue }
        if (c == "\"") { i++; break }
        if (c == "$" && substr(s, i + 1, 1) == "(") {
          k = close_paren(s, i + 2); body = substr(s, i + 2, k - i - 2)
          subs[++ns] = body; tok = tok enc(body); i = k + 1; continue
        }
        if (c == "`") {
          j = index(substr(s, i + 1), "`"); if (j == 0) j = L - i + 1
          body = substr(s, i + 1, j - 1); subs[++ns] = body; tok = tok enc(body); i += j + 1; continue
        }
        tok = tok enc(c); i++
      }
      continue
    }
    if (c == "$" && substr(s, i + 1, 1) == "{") {
      j = index(substr(s, i), "}"); if (j == 0) j = L - i + 1
      body = substr(s, i, j); collect_subst(body)
      tok = tok body; intok = 1; i += j; continue
    }
    if (c == "$" && substr(s, i + 1, 2) == "((") {
      k = close_paren(s, i + 3); k = close_paren(s, k + 1)
      tok = tok substr(s, i, k - i + 1); intok = 1; i = k + 1; continue
    }
    if (c == "$" && substr(s, i + 1, 1) == "(") { emit_seg(); i += 2; continue }
    if (c == "<" && substr(s, i + 1, 1) == "<" && substr(s, i + 2, 1) != "<") {
      emit_tok(); i += 2; strip = 0
      if (substr(s, i, 1) == "-") { strip = 1; i++ }
      while (substr(s, i, 1) == " " || substr(s, i, 1) == "\t") i++
      delim = ""; quoted = 0
      while (i <= L) {
        c = substr(s, i, 1)
        if (c ~ /[ \t\n;&|()<>]/) break
        if (c == Q || c == "\"" || c == "\\") quoted = 1; else delim = delim c
        i++
      }
      if (delim != "") { hd[++nh] = delim; hs[nh] = strip; hq[nh] = quoted }
      continue
    }
    if (c == "#" && !intok) {
      while (i <= L && substr(s, i, 1) != "\n") i++
      continue
    }
    if (c == " " || c == "\t") { emit_tok(); i++; continue }
    if (c == "\n") { emit_seg(); i++; if (nh > 0) i = read_heredocs(i); print EOL; continue }
    if (c == "|" && substr(s, i - 1, 1) == ">") { tok = tok c; intok = 1; i++; continue }
    if (index(";&|(){}`", c) > 0) { emit_seg(); i++; continue }
    tok = tok c; intok = 1; i++
  }
  emit_seg()
  for (k = 1; k <= ns; k++) print SUBST enc(subs[k])
}'

lower() { printf '%s' "$1" | LC_ALL=C tr '[:upper:]' '[:lower:]'; }

# kubectl 전역 옵션 중 값을 하나 받는 것
is_kubectl_value_flag() {
  case "$1" in
    -n|--namespace|--context|--cluster|--user|--kubeconfig|-s|--server|--token|--as|--as-group|--as-uid|\
    --request-timeout|-v|--v|--vmodule|--cache-dir|--certificate-authority|--client-certificate|--client-key|\
    --tls-server-name|--username|--password|--profile|--profile-output|--log-file|-o|--output|-l|--selector|\
    --field-selector|--kuberc)
      return 0 ;;
  esac
  return 1
}

# $1부터 처음 나오는 옵션이 아닌 단어의 위치를 출력한다. kubectl 전역 옵션의 값은 건너뛴다.
next_word() {
  local j=$1
  while [ "$j" -lt "$n" ]; do
    case "${t[$j]}" in
      -*) if is_kubectl_value_flag "${t[$j]}"; then j=$((j + 2)); else j=$((j + 1)); fi ;;
      *) echo "$j"; return ;;
    esac
  done
  echo "$n"
}

check_kubectl() {
  local i=$1 j verb sub k
  for ((k = i + 1; k < n; k++)); do
    case "${t[$k]}" in --raw|--raw=*) block "kubectl --raw는 API를 직접 호출한다. get, describe, logs로 조회한다." ;; esac
  done

  j=$(next_word $((i + 1)))
  [ "$j" -ge "$n" ] && return 0
  verb=${t[$j]}

  case "$verb" in
    get|describe)
      for ((k = j + 1; k < n; k++)); do
        if [[ "$(lower "${t[$k]}")" =~ (^|,)secrets?($|[,./]) ]]; then
          block "Secret은 조회하지 않는다. DB 비밀번호와 API 키가 들어 있다."
        fi
      done
      ;;
    logs|top|events|explain|api-resources|api-versions|version|cluster-info) ;;
    auth)
      j=$(next_word $((j + 1))); sub=${t[$j]:-}
      case "$sub" in can-i|whoami) ;; *) block "kubectl auth $sub 는 허용하지 않는다. can-i, whoami만 허용한다." ;; esac
      ;;
    config)
      j=$(next_word $((j + 1))); sub=${t[$j]:-}
      case "$sub" in current-context|get-contexts|get-clusters|view) ;; *) block "kubectl config $sub 는 kubeconfig를 바꾼다. 컨텍스트 전환은 사용자가 직접 한다." ;; esac
      ;;
    rollout)
      j=$(next_word $((j + 1))); sub=${t[$j]:-}
      case "$sub" in status|history) ;; *) block "kubectl rollout $sub 는 배포를 바꾼다. status, history만 허용한다." ;; esac
      ;;
    *)
      block "kubectl $verb 는 클러스터를 바꾸거나 컨테이너 안에서 실행한다. 조회(get, describe, logs, top, events)만 허용한다. 변경이 필요하면 명령을 사용자에게 제시하고 사용자가 직접 실행한다."
      ;;
  esac
}

check_helm() {
  local j verb
  j=$(next_word $(($1 + 1)))
  [ "$j" -ge "$n" ] && return 0
  verb=${t[$j]}
  case "$verb" in
    list|ls|status|history|version) ;;
    *) block "helm $verb 는 허용하지 않는다. list, status, history만 허용한다. helm get은 values의 민감한 값을 출력한다." ;;
  esac
}

# oci [전역 옵션] {서비스} {리소스...} {동작} [옵션]. 동작은 위치 인자의 마지막 단어다.
check_oci() {
  local k=$(($1 + 1)) action="" positional=0
  while [ "$k" -lt "$n" ]; do
    case "${t[$k]}" in
      -v|--version|-h|--help|-\?) [ "$positional" -eq 0 ] && [ $((k + 1)) -eq "$n" ] && return 0; k=$((k + 1)) ;;
      --*=*) k=$((k + 1)) ;;
      --profile|--region|--config-file|--auth|--endpoint|--cert-bundle|--output|--query|--defaults-file|\
      --connection-timeout|--read-timeout|--max-retries|--auth-purpose|--proxy|--generate-param-json-input)
        [ "$positional" -eq 0 ] && k=$((k + 2)) || break ;;
      -*) [ "$positional" -eq 0 ] && k=$((k + 1)) || break ;;
      *)
        case "${t[$k]}" in
          secrets|vault|kms) block "oci ${t[$k]} 는 민감한 값을 다룬다. 허용하지 않는다." ;;
        esac
        action=${t[$k]}; positional=$((positional + 1)); k=$((k + 1)) ;;
    esac
  done
  [ "$positional" -eq 0 ] && [ $(($1 + 1)) -lt "$n" ] && block "oci 명령을 해석할 수 없다. 서비스와 동작을 앞에 쓴다."
  case "$action" in
    ""|list|get) ;;
    *) block "oci ... $action 는 허용하지 않는다. list, get만 허용한다." ;;
  esac
}

check_git() {
  local i=$1 j k tok sub="" cluster pre skip=false has_get=false has_write=false key_at=-1

  j=$((i + 1))
  while [ "$j" -lt "$n" ]; do
    case "${t[$j]}" in
      -c)
        [[ "$(lower "${t[$((j + 1))]:-}")" == core.hookspath* ]] && block "core.hooksPath를 바꾸면 pre-commit 훅이 꺼진다. 사용자가 직접 설정한다."
        j=$((j + 2)) ;;
      -C|--git-dir|--work-tree|--namespace) j=$((j + 2)) ;;
      -*) j=$((j + 1)) ;;
      *) sub=${t[$j]}; break ;;
    esac
  done

  for ((k = j + 1; k < n; k++)); do
    tok=${t[$k]}
    if $skip; then skip=false; continue; fi
    case "$tok" in
      --no-veri*) block "--no-verify로 훅을 건너뛰지 않는다. 훅 출력의 File과 Line을 사용자에게 보고한다." ;;
      -m|-F|-t|--message|--file|--template|--author|--date) skip=true ;;
      --get|--get-all|--get-regexp) has_get=true ;;
      --unset|--unset-all|--add|--replace-all|--remove-section|--rename-section|--edit) has_write=true ;;
      --*) ;;
      -[A-Za-z]*)
        cluster=${tok#-}
        pre=${cluster%%[mF]*}
        if [ "$sub" = commit ] && [[ "$pre" == *n* ]]; then
          block "git commit -n은 --no-verify와 같다. 훅을 건너뛰지 않는다."
        fi
        [ "${cluster%[mF]}" != "$cluster" ] && skip=true
        ;;
      *)
        [[ "$(lower "$tok")" == core.hookspath* ]] && key_at=$k ;;
    esac
  done

  if [ "$key_at" -ge 0 ]; then
    if [ "$sub" = config ] && ! $has_write && { $has_get || [ "$key_at" -eq $((n - 1)) ]; }; then
      return 0
    fi
    block "core.hooksPath를 바꾸면 pre-commit 훅이 꺼진다. 사용자가 직접 설정한다."
  fi
}

# git, gh는 메시지와 본문 값(-m, --title, --body 등)을 빼고 검사한다. echo, printf는 리다이렉트 대상만 검사한다.
check_env_file() {
  local word=$1 k tok base skip=false prev=""
  # git grep, git log는 추적 파일만 읽는다. .env는 추적되지 않으므로 패턴에 .env가 있어도 된다.
  if [ "$word" = git ]; then
    for ((k = 1; k < n; k++)); do
      case "${t[$k]}" in -C|-c) k=$((k + 1)) ;; -*) ;; grep|log) return 0 ;; *) break ;; esac
    done
  fi
  for ((k = 0; k < n; k++)); do
    tok=${t[$k]}
    if $skip; then skip=false; continue; fi
    case "$word" in
      echo|printf)
        case "$prev" in '>'|'>>'|'>|'|[0-9]'>'|[0-9]'>>'|'&>') ;; *) case "$tok" in '>'*) ;; *) prev=$tok; continue ;; esac ;; esac
        tok=${tok#>}; tok=${tok#>}; tok=${tok#|} ;;
    esac
    prev=${t[$k]}
    case "$word:$tok" in
      git:-m|git:--message|gh:-t|gh:--title|gh:-b|gh:--body|gh:--notes) skip=true; continue ;;
      git:--message=*|gh:--title=*|gh:--body=*|gh:--notes=*) continue ;;
      git:-[A-Za-z]*m) skip=true; continue ;;
    esac
    # 단어 안 어디에 있든 막는다. python -c, node -e의 문자열과 find -name 패턴도 여기서 걸린다.
    # process.env, .envrc처럼 뒤에 글자가 붙으면 환경 파일이 아니다.
    # 파일 시스템이 대소문자를 구분하지 않으므로(.ENV도 .env다) 대소문자를 무시한다.
    base=${tok//.env.example/}; base=${base//.env.sample/}; base=${base//.env.template/}
    shopt -s nocasematch
    if [[ "$base" =~ (^|[^A-Za-z0-9_])\.env([^A-Za-z0-9_]|$) ]]; then
      shopt -u nocasematch
      block "$tok 은 민감한 값이 들어 있는 환경 파일을 가리킨다. 읽거나 출력하지 않는다."
    fi
    shopt -u nocasematch
  done
}

# 이 훅과 Claude 설정을 셸로 고치지 못하게 한다. 파일 도구로 고치면 settings.json의 ask가 사용자에게 묻는다.
check_guard_files() {
  local word=$1 k tok prev="" in_place=false
  for ((k = 0; k < n; k++)); do
    case "${t[$k]}" in --in-place*|-[A-Za-z]*i*|-i*) in_place=true ;; esac
  done
  for ((k = 0; k < n; k++)); do
    tok=${t[$k]}
    case "$tok" in
      *.claude/settings*.json|*.claude/hooks|*.claude/hooks/*)
        case "$word" in
          mv|rm|chmod|truncate|tee|dd)
            block "$tok 은 이 가드의 설정이다. 셸로 고치지 않는다." ;;
          sed|perl)
            $in_place && block "$tok 은 이 가드의 설정이다. 셸로 고치지 않는다." ;;
          cp|ln|install)
            [ "$k" -eq $((n - 1)) ] && block "$tok 은 이 가드의 설정이다. 셸로 고치지 않는다." ;;
        esac
        case "$prev" in
          '>'|'>>'|[0-9]'>'|[0-9]'>>'|'&>'|'>|') block "$tok 은 이 가드의 설정이다. 셸로 고치지 않는다." ;;
        esac
        case "$tok" in '>'*) block "$tok 은 이 가드의 설정이다. 셸로 고치지 않는다." ;; esac
        ;;
    esac
    prev=$tok
  done
}

# .gitignore를 무시하고 디렉터리를 훑는 검색은 .env 내용을 출력한다. git grep과 rg는 .gitignore를 따른다.
check_search() {
  local word=$1 k tok noignore=false hidden=false unrestricted=0
  for ((k = $2 + 1; k < n; k++)); do
    tok=${t[$k]}
    case "$word:$tok" in
      *grep:--recursive|*grep:--dereference-recursive|*grep:-[rR]*|*grep:-[!-]*[rR]*)
        block "재귀 grep은 .gitignore를 무시해 .env 내용을 출력할 수 있다. git grep이나 rg를 쓴다." ;;
      rg:-uu*) block "rg -uu는 숨김 파일과 무시된 파일(.env)까지 검색한다. 플래그 없이 rg를 쓴다." ;;
      rg:-u|rg:--unrestricted) unrestricted=$((unrestricted + 1)); noignore=true ;;
      rg:--no-ignore*) noignore=true ;;
      rg:--hidden|rg:-.) hidden=true ;;
    esac
  done
  if [ "$unrestricted" -ge 2 ] || { $noignore && $hidden; }; then
    block "rg --no-ignore --hidden은 무시된 숨김 파일(.env)까지 검색한다. 플래그 없이 rg를 쓴다."
  fi
}

# 래퍼의 옵션 중 값을 하나 받는 것
is_wrapper_value_flag() {
  case "$1:$2" in
    sudo:-u|sudo:-g|sudo:-h|sudo:-p|sudo:-C|sudo:-U|sudo:-r|sudo:-t|sudo:-D|sudo:-R|\
    env:-u|env:-C|env:-P|env:-S|nice:-n|watch:-n|watch:--interval|caffeinate:-t|caffeinate:-w|\
    timeout:-s|timeout:--signal|timeout:-k|timeout:--kill-after|gtimeout:-s|gtimeout:--signal|gtimeout:-k|gtimeout:--kill-after|\
    xargs:-I|xargs:-J|xargs:-n|xargs:-P|xargs:-L|xargs:-d|xargs:-E|xargs:-s|xargs:-a|xargs:-R|xargs:-S|\
    parallel:-j|parallel:-S|parallel:--jobs)
      return 0 ;;
  esac
  return 1
}

# 래퍼($pos) 뒤에서 실행할 명령의 위치를 출력한다. 없으면 n을 출력한다.
wrapped_command() {
  local w=$1 k=$(($2 + 1)) duration=false
  if [ "$w" = find ]; then
    for ((; k < n; k++)); do
      case "${t[$k]}" in -exec|-execdir|-ok|-okdir) echo $((k + 1)); return ;; esac
    done
    echo "$n"; return
  fi
  case "$w" in timeout|gtimeout) duration=true ;; esac
  while [ "$k" -lt "$n" ]; do
    case "${t[$k]}" in
      --) k=$((k + 1)); break ;;
      -*) if is_wrapper_value_flag "$w" "${t[$k]}"; then k=$((k + 2)); else k=$((k + 1)); fi ;;
      *)
        if $duration; then duration=false; k=$((k + 1)); continue; fi
        [ "$w" = env ] && [[ "${t[$k]}" == *=* ]] && { k=$((k + 1)); continue; }
        break ;;
    esac
  done
  echo "$k"
}

# 명령 위치(맨 앞, 셸 키워드와 래퍼 뒤, 셸 -c 뒤)의 단어를 찾아 검사한다.
check_segment() {
  local depth=$1 pos=0 tok base word k rest
  [ "$n" -eq 0 ] && return 0

  while [ "$pos" -lt "$n" ]; do
    tok=${t[$pos]}
    base=${tok##*/}
    if [[ "$tok" =~ ^[A-Za-z_][A-Za-z0-9_]*= ]]; then
      pos=$((pos + 1)); continue
    fi
    case "$base" in
      if|then|else|elif|do|while|until|'!'|coproc)
        pos=$((pos + 1)); continue ;;
      sudo|env|command|builtin|exec|nohup|nice|stdbuf|time|xargs|watch|caffeinate|timeout|gtimeout|find|parallel)
        pos=$(wrapped_command "$base" "$pos"); continue ;;
      eval)
        rest=""
        for ((k = pos + 1; k < n; k++)); do rest="$rest ${t[$k]}"; done
        analyze "$rest" $((depth + 1)); return 0 ;;
      bash|sh|zsh|dash)
        for ((k = pos + 1; k < n; k++)); do
          case "${t[$k]}" in
            --*) ;;
            -c|-[A-Za-z]*c) [ $((k + 1)) -lt "$n" ] && analyze "${t[$((k + 1))]}" $((depth + 1)); return 0 ;;
            -s|-[A-Za-z]*s*) shell_fed=true ;;
            -*) ;;
            *) break ;;
          esac
        done
        # 스크립트 파일 없이 실행한 셸은 표준 입력(here-document, 파이프)을 명령으로 읽는다.
        [ "$k" -ge "$n" ] && shell_fed=true
        ;;
    esac
    break
  done

  word=""
  [ "$pos" -lt "$n" ] && word=${t[$pos]##*/}
  check_env_file "$word"
  check_guard_files "$word"

  case "$word" in
    kubectl|kubecolor|k|oc) check_kubectl "$pos" ;;
    helm) check_helm "$pos" ;;
    oci) check_oci "$pos" ;;
    git) check_git "$pos" ;;
    grep|egrep|fgrep|ugrep|rg) check_search "$word" "$pos" ;;
    mysql|mysqlsh|mysqldump|mysqladmin|mysqlimport|mysqlpump|mariadb|mycli)
      block "DB 클라이언트를 직접 실행하지 않는다. 조회는 qa/db-scripts/readonly-query.sh \"SELECT ...\"로 한다." ;;
    ssh|scp|sftp|autossh)
      block "원격 접속과 터널은 사용자가 직접 연다." ;;
  esac
}

analyze() {
  local depth=$2 out line n shell_fed=false
  local -a t
  [ "$depth" -gt 4 ] && block "명령이 너무 깊게 중첩되어 검사할 수 없다."
  out=$(printf '%s' "$1" | LC_ALL=C awk -v Q="'" "$TOKENIZER") || block "명령을 해석할 수 없다."
  while IFS= read -r line; do
    case "$line" in
      $'\033')
        shell_fed=false
        continue ;;
      $'\034'*)
        $shell_fed && analyze "$(printf '%s' "${line#?}" | LC_ALL=C tr '\036' '\n')" $((depth + 1))
        continue ;;
      $'\035'*)
        analyze "$(printf '%s' "${line#?}" | LC_ALL=C tr '\036' '\n')" $((depth + 1))
        continue ;;
    esac
    t=()
    IFS=$'\037' read -ra t <<< "$line" || true
    n=${#t[@]}
    check_segment "$depth"
  done <<< "$out"
}

analyze "$cmd" 0
exit 0
