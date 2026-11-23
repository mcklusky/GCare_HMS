#!/usr/bin/env bash
# Loads SYNTHETIC test studies into the local evaluation archive (task 6.1; docs/24).
# Usage: infra/imaging/load-synthetic-studies.sh <patient number> <accession number> [modality] [description]
# Example: infra/imaging/load-synthetic-studies.sh P-000001 ACC-000001 CR "Synthetic chest"
# Every value is fictional; the image is a blank test pattern made by Orthanc. Never load real images.
set -euo pipefail
url="${ORTHANC_URL:-http://127.0.0.1:${ORTHANC_HTTP_PORT:-8142}}"
auth="${ORTHANC_USER:-gcare-local}:${ORTHANC_PASSWORD:-local-dev-only-not-a-secret}"
patient="${1:?patient number}"; accession="${2:?accession number}"
modality="${3:-CR}"; description="${4:-Synthetic test study}"
for v in "$patient" "$accession" "$modality"; do
  [[ "$v" =~ ^[A-Za-z0-9._-]{1,64}$ ]] || { echo "invalid value: $v" >&2; exit 2; }
done
[[ "$description" =~ ^[A-Za-z0-9\ ._-]{1,64}$ ]] || { echo "invalid description" >&2; exit 2; }
curl -fsS -u "$auth" -X POST "$url/tools/create-dicom" -d @- <<JSON | grep -E '"(ID|Status)"'
{"Tags": {"PatientID": "$patient", "PatientName": "SYNTHETIC^TEST", "AccessionNumber": "$accession",
  "Modality": "$modality", "StudyDescription": "$description", "StudyDate": "$(date -u +%Y%m%d)",
  "StudyTime": "$(date -u +%H%M%S)"}}
JSON
