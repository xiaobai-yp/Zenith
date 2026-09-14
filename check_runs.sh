#!/bin/bash
TOKEN=$(cat /data/data/com.termux/files/home/gh_token.txt)

echo "=== semua run daemon (workflow 351893333), 10 terakhir ==="
curl -s -H "Authorization: token $TOKEN" -H "User-Agent: curl/8" \
  "https://api.github.com/repos/xiaobai-yp/ZenithThermal-/actions/workflows/351893333/runs?per_page=10" | python3 -c "
import sys, json
for r in json.load(sys.stdin)['workflow_runs']:
    print(r['id'], r['head_sha'][:7], r['event'], r['status'], r['conclusion'] or '-', r['created_at'])
"