#!/usr/bin/env bash
# A single runner /32 is temporary. Existing operator CIDRs are preserved.
set -euo pipefail
: "${EKS_CLUSTER:?}" "${AWS_REGION:?}" "${RUNNER_TEMP:?}"
state="$RUNNER_TEMP/mecanica-eks-runner-cidr"
mode="${1:?Use add or remove}"
[[ "$mode" == add || "$mode" == remove ]] || exit 2

wait_update() {
  local id="$1" status
  for ((attempt=0; attempt<120; attempt++)); do
    status=$(aws eks describe-update --name "$EKS_CLUSTER" --update-id "$id" --region "$AWS_REGION" --query update.status --output text)
    case "$status" in
      Successful) return 0 ;;
      Failed|Cancelled) echo "EKS update $id: $status" >&2; return 1 ;;
    esac
    sleep 5
  done
  echo "EKS update timeout: $id" >&2
  return 1
}

if [[ "$mode" == add ]]; then
  ip=$(curl --fail --silent --show-error --max-time 15 https://checkip.amazonaws.com | tr -d '\r\n')
  [[ "$ip" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || { echo 'Invalid runner IPv4' >&2; exit 1; }
  IFS=. read -r a b c d <<< "$ip"
  for octet in "$a" "$b" "$c" "$d"; do ((10#$octet <= 255)) || exit 1; done
  cidr="$ip/32"
else
  [[ -f "$state" ]] || exit 0
  cidr=$(cat "$state")
fi

for ((attempt=0; attempt<120; attempt++)); do
  cluster=$(aws eks describe-cluster --name "$EKS_CLUSTER" --region "$AWS_REGION" --output json)
  if [[ $(jq -r '.cluster.status' <<< "$cluster") != ACTIVE ]]; then sleep 5; continue; fi
  current=$(jq -c '.cluster.resourcesVpcConfig.publicAccessCidrs' <<< "$cluster")
  if [[ "$mode" == add ]]; then
    if jq -e --arg cidr "$cidr" 'index($cidr) != null' <<< "$current" >/dev/null; then
      echo 'Runner already allowed; existing access will be preserved.'
      exit 0
    fi
    next=$(jq -c --arg cidr "$cidr" '. + [$cidr] | unique' <<< "$current")
    # Record before requesting mutation so cleanup can also handle an interrupted wait.
    printf '%s\n' "$cidr" > "$state"
  else
    next=$(jq -c --arg cidr "$cidr" 'map(select(. != $cidr))' <<< "$current")
    [[ "$next" != "$current" ]] || { rm -f "$state"; exit 0; }
    [[ $(jq length <<< "$next") -gt 0 ]] || { echo 'Refusing to remove the last CIDR' >&2; exit 1; }
  fi
  config=$(jq -cn --argjson cidrs "$next" '{publicAccessCidrs:$cidrs}')
  if id=$(aws eks update-cluster-config --name "$EKS_CLUSTER" --region "$AWS_REGION" --resources-vpc-config "$config" --query update.id --output text 2>"$RUNNER_TEMP/eks-update-error.txt"); then
    wait_update "$id"
    [[ "$mode" != remove ]] || rm -f "$state"
    echo "Runner access $mode completed: $cidr"
    exit 0
  fi
  if ! grep -q ResourceInUseException "$RUNNER_TEMP/eks-update-error.txt"; then
    cat "$RUNNER_TEMP/eks-update-error.txt" >&2
    exit 1
  fi
  sleep 5
done
echo 'EKS remained busy; deployment access was not completed.' >&2
exit 1
